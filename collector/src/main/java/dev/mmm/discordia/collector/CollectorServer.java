package dev.mmm.discordia.collector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Minimal HTTP + WebSocket server for a private collector.
 *
 * <p>Only two routes exist: {@code /health} and the upgraded {@code /ws} feed. A standalone
 * server requires {@code Authorization: Bearer <COLLECTOR_TOKEN>}; the in-mod embedded server
 * runs without a token but is restricted to loopback. The standalone server intentionally
 * terminates plain {@code ws://}; put it behind Caddy/nginx for {@code wss://} when it is not on
 * loopback.
 */
public final class CollectorServer implements AutoCloseable {
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private final String host;
    private final int port;
    private final byte[] token;
    private final boolean requireAuth;
    private final Supplier<JsonObject> snapshots;
    private final Supplier<String> status;
    private final Consumer<JsonObject> filterHandler;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean closed;

    public CollectorServer(String host, int port, String token,
                           Supplier<JsonObject> snapshots, Supplier<String> status,
                           Consumer<JsonObject> filterHandler) {
        this(host, port, token, true, snapshots, status, filterHandler);
    }

    public CollectorServer(String host, int port, String token, boolean requireAuth,
                           Supplier<JsonObject> snapshots, Supplier<String> status,
                           Consumer<JsonObject> filterHandler) {
        if (requireAuth && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("collector token is required");
        }
        if (!requireAuth && !isLoopback(host)) {
            throw new IllegalArgumentException("tokenless collector must bind to loopback");
        }
        this.host = host;
        this.port = port;
        this.token = (token == null ? "" : token).getBytes(StandardCharsets.UTF_8);
        this.requireAuth = requireAuth;
        this.snapshots = snapshots;
        this.status = status;
        this.filterHandler = filterHandler == null ? ignored -> {
        } : filterHandler;
    }

    /** The actual local port. This differs from the configured port when port 0 is used. */
    public int port() {
        return server == null ? port : server.getLocalPort();
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(host, port));
        acceptThread = new Thread(this::acceptLoop, "collector-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void broadcast(JsonObject message) {
        if (message == null) {
            return;
        }
        String text = message.toString();
        for (Client client : clients) {
            client.send(text);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        for (Client client : clients) {
            client.close();
        }
        clients.clear();
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                Thread thread = new Thread(() -> handle(socket), "collector-client");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("collector accept failed: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    private void handle(Socket socket) {
        Client client = null;
        try {
            socket.setSoTimeout(15_000);
            Request request = readRequest(socket.getInputStream());
            String path = request.path();
            String query = "";
            int queryStart = path.indexOf('?');
            if (queryStart >= 0) {
                query = path.substring(queryStart + 1);
                path = path.substring(0, queryStart);
            }

            if (!authorized(request.headers(), query)) {
                writeHttp(socket, 401, "Unauthorized", "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }

            if ("/health".equals(path)) {
                writeHttp(socket, 200, "OK", healthJson());
                return;
            }
            if (!"/ws".equals(path)) {
                writeHttp(socket, 404, "Not Found", "{\"ok\":false,\"error\":\"not found\"}");
                return;
            }
            if (!"websocket".equalsIgnoreCase(request.headers().get("upgrade"))
                    || !containsToken(request.headers().get("connection"), "upgrade")) {
                writeHttp(socket, 400, "Bad Request", "{\"ok\":false,\"error\":\"websocket upgrade required\"}");
                return;
            }
            String key = request.headers().get("sec-websocket-key");
            if (key == null || key.isBlank()) {
                writeHttp(socket, 400, "Bad Request", "{\"ok\":false,\"error\":\"missing websocket key\"}");
                return;
            }

            OutputStream out = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            socket.setSoTimeout(0);

            client = new Client(socket);
            clients.add(client);
            JsonObject initial = snapshots.get();
            client.send(initial == null ? CollectorJson.status(status.get()).toString() : initial.toString());
            client.readLoop();
        } catch (EOFException ignored) {
            // Client disconnected during the handshake.
        } catch (Exception ignored) {
            // A private feed should not let one bad socket take down the service.
        } finally {
            if (client != null) {
                clients.remove(client);
                client.close();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private boolean authorized(Map<String, String> headers, String query) {
        if (!requireAuth) {
            return true;
        }
        String authorization = headers.getOrDefault("authorization", "").trim();
        String provided = authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")
                ? authorization.substring(7).trim() : "";
        if (provided.isEmpty() && !query.isBlank()) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
                if ("token".equals(name)) {
                    provided = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        return !provided.isEmpty()
                && MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.UTF_8));
    }

    private String healthJson() {
        JsonObject root = new JsonObject();
        JsonObject current = snapshots.get();
        root.addProperty("ok", true);
        root.addProperty("status", status.get());
        root.addProperty("clients", clients.size());
        root.addProperty("lastUpdated", current != null && current.has("lastUpdated")
                ? current.get("lastUpdated").getAsLong() : 0L);
        root.addProperty("flips", current != null && current.has("bin")
                ? current.getAsJsonArray("bin").size() : 0);
        return root.toString();
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(value) || "localhost".equals(value) || "::1".equals(value);
    }

    private static boolean containsToken(String value, String expected) {
        if (value == null) {
            return false;
        }
        for (String part : value.split(",")) {
            if (part.trim().equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String acceptKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key.trim() + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeHttp(Socket socket, int code, String reason, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        byte[] tail = new byte[4];
        int matched = 0;
        while (header.size() < MAX_HEADER_BYTES) {
            int value = in.read();
            if (value < 0) {
                throw new EOFException("connection closed before request headers");
            }
            header.write(value);
            tail[matched & 3] = (byte) value;
            matched++;
            if (header.size() >= 4) {
                int size = header.size();
                byte[] data = header.toByteArray();
                if (data[size - 4] == '\r' && data[size - 3] == '\n'
                        && data[size - 2] == '\r' && data[size - 1] == '\n') {
                    break;
                }
            }
        }
        if (header.size() >= MAX_HEADER_BYTES) {
            throw new IOException("request headers too large");
        }

        String text = header.toString(StandardCharsets.UTF_8);
        String[] lines = text.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("empty request");
        }
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length < 2) {
            throw new IOException("malformed request line");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
        }
        return new Request(requestLine[1], headers);
    }

    private record Request(String path, Map<String, String> headers) {
    }

    private final class Client {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private volatile boolean closed;

        private Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        private void send(String text) {
            if (closed) {
                return;
            }
            try {
                synchronized (out) {
                    writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                close();
            }
        }

        private void readLoop() {
            while (!closed) {
                try {
                    if (!readFrame()) {
                        return;
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        /** @return false when the peer closed or sent an invalid frame */
        private boolean readFrame() throws IOException {
            int first = in.read();
            if (first < 0) {
                return false;
            }
            int second = in.read();
            if (second < 0) {
                return false;
            }
            int opcode = first & 0x0f;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = readUnsignedShort();
            } else if (length == 127) {
                length = readLong();
            }
            if (length < 0 || length > MAX_FRAME_BYTES) {
                close();
                return false;
            }
            byte[] mask = masked ? readExactly(4) : null;
            byte[] payload = readExactly((int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }

            switch (opcode) {
                case 0x8 -> {
                    close();
                    return false;
                }
                case 0x9 -> {
                    synchronized (out) {
                        writeFrame(0xA, payload);
                    }
                }
                case 0xA -> {
                    // Pong; no action needed.
                }
                case 0x1 -> {
                    // The client sends its current filter set after connecting and after any
                    // /discordia margin, minprofit, maxcost, or nonbin change.
                    try {
                        String text = new String(payload, StandardCharsets.UTF_8);
                        JsonObject message = JsonParser.parseString(text).getAsJsonObject();
                        if (message.has("type") && "filters".equals(message.get("type").getAsString())) {
                            filterHandler.accept(message);
                        }
                    } catch (Exception ignored) {
                        // A malformed control frame must not drop a working feed.
                    }
                }
                default -> {
                    // Continuation/binary frames are not part of this protocol.
                }
            }
            return true;
        }

        private int readUnsignedShort() throws IOException {
            byte[] bytes = readExactly(2);
            return ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
        }

        private long readLong() throws IOException {
            byte[] bytes = readExactly(8);
            long value = 0;
            for (byte b : bytes) {
                value = (value << 8) | (b & 0xff);
            }
            return value < 0 ? Long.MAX_VALUE : value;
        }

        private byte[] readExactly(int length) throws IOException {
            byte[] bytes = in.readNBytes(length);
            if (bytes.length != length) {
                throw new EOFException("short websocket frame");
            }
            return bytes;
        }

        private void writeFrame(int opcode, byte[] payload) throws IOException {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length <= 0xffff) {
                out.write(126);
                out.write((payload.length >>> 8) & 0xff);
                out.write(payload.length & 0xff);
            } else {
                out.write(127);
                long length = payload.length;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((length >>> shift) & 0xff));
                }
            }
            out.write(payload);
            out.flush();
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            clients.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
