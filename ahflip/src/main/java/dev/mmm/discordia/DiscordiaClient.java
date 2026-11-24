package dev.mmm.discordia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.mmm.discordia.core.Flip;
import dev.mmm.discordia.core.FlipConfig;
import dev.mmm.discordia.core.FlipEngine;
import dev.mmm.discordia.core.GlobalSales;
import dev.mmm.discordia.collector.CollectorScanner;
import dev.mmm.discordia.collector.CollectorServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Client-only Discordia flip finder.
 *
 * <p>It never buys, clicks or confirms anything. Clicking a chat notification only runs the
 * {@code /viewauction} command the player could have typed themselves.
 *
 * <p>Bare {@code /discordia} toggles live chat notifications. While enabled, the best BIN flips
 * are resent after each market update. Non-BIN ending auctions stay hidden until
 * {@code /discordia toggle nonbin}.
 */
public final class DiscordiaClient implements ClientModInitializer {
    public static final FlipConfig CONFIG = new FlipConfig();
    public static final MarketService MARKET = new MarketService(CONFIG);

    private static final int TOP_FLIPS = 5;

    private static DiscordiaClient instance;
    private static volatile boolean enabled = true;
    private static volatile boolean includeNonBin;
    private static volatile double purse;
    private static Path ignoredPath;
    private static Path settingsPath;
    private static final Set<String> ignored = new HashSet<>();
    private static volatile String collectorUrl = "";
    private static volatile String collectorToken = "";
    private static volatile CollectorClient collector;
    private static volatile CollectorClient.Snapshot collectorSnapshot;
    private static volatile String collectorStatus = "off";
    /** Local receipt time of the last collector scan/heartbeat, not the market tick. */
    private static volatile long collectorScanAt;
    private static volatile CollectorServer embeddedServer;
    private static volatile CollectorScanner embeddedScanner;
    private static volatile boolean embeddedCollector;

    private final Set<String> announcedFlipIds = new HashSet<>();

    @Override
    public void onInitializeClient() {
        instance = this;
        // The in-game client is AH-only. Bazaar order flips and conversion income remain available
        // in the standalone console, but must never leak into auction chat or commands.
        CONFIG.bazaar = false;
        CONFIG.deterministic = false;
        // The requested budget is the purse itself, not a separate configured number.
        CONFIG.maxBudgetShare = 1.0d;

        Path configDir = FabricLoader.getInstance().getConfigDir();
        ignoredPath = configDir.resolve("discordia-ignored.txt");
        loadIgnored();
        settingsPath = configDir.resolve("discordia-config.json");
        loadSettings();
        if (collectorUrl.isBlank()) {
            // Zero-config default: run the collector inside the mod on loopback. No token, no
            // separate process, and no user setup. The standalone collector remains optional.
            startEmbeddedCollector();
        } else {
            startCollector();
        }

        Thread ticker = new Thread(this::tickLoop, "discordia-ticker");
        ticker.setDaemon(true);
        ticker.start();
    }

    /**
     * Called from the client command mixin before vanilla sends {@code /discordia ...}.
     *
     * @return true when the command was consumed locally
     */
    public static boolean handleClientCommand(String raw) {
        if (raw == null) {
            return false;
        }
        String command = raw.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String[] parts = command.split("\\s+");
        if (parts.length == 0 || !parts[0].equalsIgnoreCase("discordia")) {
            return false;
        }
        DiscordiaClient client = instance;
        if (client == null) {
            return true;
        }
        if (parts.length == 1) {
            client.setEnabled(!enabled);
            return true;
        }
        switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "toggle" -> {
                if (parts.length == 2) {
                    client.setEnabled(!enabled);
                } else if (parts.length == 3 && parts[2].equalsIgnoreCase("nonbin")) {
                    client.setIncludeNonBin(!includeNonBin);
                } else {
                    client.usage(Minecraft.getInstance(), "/discordia toggle [nonbin]");
                }
            }
            case "on" -> client.setEnabled(true);
            case "off" -> client.setEnabled(false);
            case "status" -> client.status();
            case "settings", "config" -> client.status();
            case "margin", "minmargin", "min-margin" -> client.setMargin(joinArguments(parts, 2));
            case "minprofit", "min-profit", "profit" -> client.setMinProfit(joinArguments(parts, 2));
            case "maxcost", "max-cost", "max" -> client.setMaxCost(joinArguments(parts, 2));
            case "ignore" -> client.ignore(joinArguments(parts, 2));
            case "unignore" -> client.unignore(joinArguments(parts, 2));
            case "globalfault" -> client.globalFault(joinArguments(parts, 2));
            case "list", "flips" -> client.printFlips();
            case "open", "openbest" -> client.openBest();
            case "copy", "copybest" -> client.copyBest();
            case "collector", "remote" -> client.configureCollector(joinArguments(parts, 2));
            case "help", "?" -> client.help();
            default -> client.help();
        }
        return true;
    }

    /**
     * Registers the local command tree after the server sends its command packet. This is what
     * makes {@code /discordia} and its subcommands appear in chat tab completion, instead of being
     * only a hidden intercept in {@code sendCommand}.
     */
    public static void registerClientCommands(CommandDispatcher<ClientSuggestionProvider> dispatcher) {
        if (dispatcher == null || dispatcher.getRoot().getChild("discordia") != null) {
            return;
        }
        LiteralArgumentBuilder<ClientSuggestionProvider> root = literal("discordia");
        root.executes(context -> executeClient("discordia"));
        root.then(literal("toggle")
                .executes(context -> executeClient("discordia toggle"))
                .then(literal("nonbin")
                        .executes(context -> executeClient("discordia toggle nonbin"))));
        root.then(literal("on").executes(context -> executeClient("discordia on")));
        root.then(literal("off").executes(context -> executeClient("discordia off")));
        root.then(literal("status").executes(context -> executeClient("discordia status")));
        root.then(literal("help").executes(context -> executeClient("discordia help")));
        root.then(literal("list").executes(context -> executeClient("discordia list")));
        root.then(literal("open").executes(context -> executeClient("discordia open")));
        root.then(literal("copy").executes(context -> executeClient("discordia copy")));
        root.then(literal("collector")
                .executes(context -> executeClient("discordia collector"))
                .then(literal("off").executes(context -> executeClient("discordia collector off")))
                .then(greedy("config")
                        .executes(context -> executeClient("discordia collector "
                                + StringArgumentType.getString(context, "config")))));
        root.then(literal("margin")
                .then(word("percent").executes(context -> executeClient("discordia margin "
                        + StringArgumentType.getString(context, "percent")))));
        root.then(literal("minprofit")
                .then(word("amount").executes(context -> executeClient("discordia minprofit "
                        + StringArgumentType.getString(context, "amount")))));
        root.then(literal("maxcost")
                .then(word("amount").executes(context -> executeClient("discordia maxcost "
                        + StringArgumentType.getString(context, "amount")))));
        root.then(literal("ignore")
                .then(greedy("item").executes(context -> executeClient("discordia ignore "
                        + StringArgumentType.getString(context, "item")))));
        root.then(literal("unignore")
                .then(greedy("item").executes(context -> executeClient("discordia unignore "
                        + StringArgumentType.getString(context, "item")))));
        root.then(literal("globalfault")
                .then(word("mode").executes(context -> executeClient("discordia globalfault "
                        + StringArgumentType.getString(context, "mode")))));
        dispatcher.register(root);
    }

    private static int executeClient(String command) {
        handleClientCommand(command);
        return 1;
    }

    private static LiteralArgumentBuilder<ClientSuggestionProvider> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static RequiredArgumentBuilder<ClientSuggestionProvider, String> word(String name) {
        return RequiredArgumentBuilder.argument(name, StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<ClientSuggestionProvider, String> greedy(String name) {
        return RequiredArgumentBuilder.argument(name, StringArgumentType.greedyString());
    }

    private static String joinArguments(String[] parts, int from) {
        if (from >= parts.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length)).trim();
    }

    /** Keeps purse/notifications fresh without polling or tracking keyboard input. */
    private void tickLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            dispatch(this::pulse);
        }
    }

    private void dispatch(Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> {
            if (minecraft.player == null) {
                return;
            }
            action.run();
        });
    }

    private void pulse() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        updatePurse(minecraft);
        if (enabled) {
            announceFlips(minecraft);
        }
    }

    private void updatePurse(Minecraft minecraft) {
        double seen = readPurse(minecraft);
        if (seen >= 0d) {
            boolean changed = seen != purse;
            purse = seen;
            CONFIG.budget = seen;
            CONFIG.maxBudgetShare = 1.0d;
            if (changed) {
                sendCollectorFilters();
            }
        }
    }

    private double readPurse(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return -1d;
        }
        Scoreboard scoreboard = level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return -1d;
        }
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            // Sidebar entries can have no display component (for example while an objective is
            // being replaced). Treat those as non-purse lines instead of throwing every tick.
            Component display = entry.display();
            double value = display == null ? -1d : PurseReader.parse(display.getString());
            if (value < 0d) {
                Component owner = entry.ownerName();
                if (owner != null) {
                    value = PurseReader.parse(owner.getString());
                }
            }
            if (value >= 0d) {
                return value;
            }
        }
        return -1d;
    }

    /** Developer-only in-game fault injection; it never changes the real Cofl source. */
    private void globalFault(String raw) {
        String value = raw == null ? "" : raw.trim();
        Minecraft minecraft = Minecraft.getInstance();
        if (value.isEmpty() || value.equalsIgnoreCase("status")) {
            info(minecraft, "GLOBAL", MARKET.globalHealth());
            globalFaultUsage(minecraft);
            return;
        }
        GlobalSales.FaultMode mode = GlobalSales.parseFaultMode(value);
        if (mode == null) {
            globalFaultUsage(minecraft);
            return;
        }
        MARKET.setGlobalFaultMode(mode);
        MARKET.refreshNow();
        if (mode == GlobalSales.FaultMode.NONE) {
            success(minecraft, "Fault injection OFF - using the real Cofl source.");
        } else {
            warn(minecraft, "Fault injection ON: " + mode.name().toLowerCase()
                    + "  |  /discordia globalfault off");
        }
    }

    private void setEnabled(boolean value) {
        enabled = value;
        Minecraft minecraft = Minecraft.getInstance();
        if (value) {
            send(minecraft, prefix()
                    .append(Component.literal("ON").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("  all new BIN flips  |  "
                            + (includeNonBin ? "non-BIN on" : "non-BIN hidden")).withStyle(ChatFormatting.GRAY)));
            announceFlips(minecraft);
        } else {
            send(minecraft, prefix()
                    .append(Component.literal("OFF").withStyle(ChatFormatting.RED))
                    .append(Component.literal("  alerts paused").withStyle(ChatFormatting.GRAY)));
        }
    }

    private void setIncludeNonBin(boolean value) {
        includeNonBin = value;
        saveSettings();
        sendCollectorFilters();
        Minecraft minecraft = Minecraft.getInstance();
        send(minecraft, prefix()
                .append(Component.literal(value ? "NON-BIN ON" : "NON-BIN OFF")
                        .withStyle(value ? ChatFormatting.GOLD : ChatFormatting.GRAY))
                .append(Component.literal(value ? "  ending auctions included" : "  BIN only")
                        .withStyle(ChatFormatting.GRAY)));
        if (enabled) {
            announceFlips(minecraft);
        }
    }

    private void status() {
        Minecraft minecraft = Minecraft.getInstance();
        if (usesCollector()) {
            statusCollector(minecraft);
            return;
        }
        long age = MARKET.lastUpdated() == 0 ? -1 : (System.currentTimeMillis() - MARKET.lastUpdated()) / 1000L;
        int bin = visible(MARKET.auctions()).size();
        int snipes = includeNonBin ? visible(MARKET.sniping()).size() : 0;
        send(minecraft, prefix()
                .append(Component.literal(enabled ? "ON" : "OFF")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("  " + (includeNonBin ? "BIN + non-BIN" : "BIN only")
                        + "  |  purse " + (purse > 0d ? coins(purse) : "waiting"))
                        .withStyle(ChatFormatting.GRAY)));
        statusRow(minecraft, "feed", MARKET.status()
                + (age < 0 ? "  |  tick waiting" : "  |  tick " + age + "s"));
        statusRow(minecraft, "flips", bin + " BIN" + (includeNonBin ? "  |  " + snipes + " non-BIN" : ""));
        statusRow(minecraft, "filters", "margin >= " + trimNumber(CONFIG.minMarginPct)
                + "%  |  profit >= " + coins(CONFIG.minProfit)
                + "  |  cap " + (CONFIG.maxItemCost > 0d ? coins(CONFIG.maxItemCost) : "purse"));
        statusRow(minecraft, "data", MARKET.catalogItems() + " items  |  "
                + MARKET.trackedItems() + " tracked  |  " + MARKET.saleSamples() + " sales");
        statusRow(minecraft, "global", MARKET.globalCached() + " cached  |  "
                + MARKET.globalQueued() + " queued");
        statusRow(minecraft, "sales", MARKET.globalPendingSales() + " pending  |  "
                + MARKET.globalUnavailableSales() + " unavailable");
        statusRow(minecraft, "health", MARKET.globalHealth());
        statusRow(minecraft, "pending", MARKET.unresolvedFlips() + " unverified flips");
        statusRow(minecraft, "why", MARKET.rejectedCandidates() + " rejected  |  "
                + MARKET.rejectionSummary());
        sendActions(minecraft);
    }

    private void refreshDisplay(Minecraft minecraft) {
        if (usesCollector()) {
            if (enabled) {
                announceFlips(minecraft);
            }
        } else {
            MARKET.refreshNow();
        }
    }

    private void statusCollector(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        long updated = sourceUpdated();
        long tickAge = updated == 0 ? -1 : (now - updated) / 1000L;
        long scanAt = collectorScanAt;
        long scanAge = scanAt == 0 ? -1 : (now - scanAt) / 1000L;
        int bin = sourceBin().size();
        int snipes = includeNonBin ? sourceSnipes().size() : 0;
        send(minecraft, prefix()
                .append(Component.literal(enabled ? "ON" : "OFF")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("  collector  |  "
                        + (includeNonBin ? "BIN + non-BIN" : "BIN only")
                        + "  |  purse " + (purse > 0d ? coins(purse) : "waiting"))
                        .withStyle(ChatFormatting.GRAY)));
        StringBuilder feed = new StringBuilder(usesCollector() ? collectorStatus : "local scanner");
        if (scanAge >= 0) {
            feed.append("  |  scan ").append(scanAge).append("s");
        } else if (usesCollector()) {
            feed.append("  |  waiting for first scan");
        }
        feed.append(tickAge < 0 ? "  |  tick waiting" : "  |  tick " + tickAge + "s");
        statusRow(minecraft, "feed", feed.toString());
        statusRow(minecraft, "flips", bin + " BIN" + (includeNonBin ? "  |  " + snipes + " non-BIN" : ""));
        statusRow(minecraft, "filters", "margin >= " + trimNumber(CONFIG.minMarginPct)
                + "%  |  profit >= " + coins(CONFIG.minProfit)
                + "  |  cap " + (CONFIG.maxItemCost > 0d ? coins(CONFIG.maxItemCost) : "purse"));
        statusRow(minecraft, "source", displayCollectorUrl());
        statusRow(minecraft, "pending", sourcePending() + " unverified flips");
        sendActions(minecraft);
    }

    private void setMargin(String raw) {
        Double value = parsePercent(raw);
        Minecraft minecraft = Minecraft.getInstance();
        if (value == null) {
            usage(minecraft, "/discordia margin <0-100>%");
            return;
        }
        CONFIG.minMarginPct = value;
        CONFIG.minSnipeMarginPct = value;
        // Keep the stricter book-only fallback from becoming weaker than the normal floor.
        CONFIG.bookOnlyMinMarginPct = Math.max(8d, value);
        saveSettings();
        sendCollectorFilters();
        refreshDisplay(minecraft);
        success(minecraft, "Margin floor: " + trimNumber(value) + "%.");
    }

    private void setMinProfit(String raw) {
        Double value = parseCoins(raw);
        Minecraft minecraft = Minecraft.getInstance();
        if (value == null) {
            usage(minecraft, "/discordia minprofit <500k|1m|2b>");
            return;
        }
        CONFIG.minProfit = value;
        CONFIG.minSnipeProfit = value;
        // Book-only flips keep at least their usual 1M safety floor.
        CONFIG.bookOnlyMinProfit = Math.max(1_000_000d, value);
        saveSettings();
        sendCollectorFilters();
        refreshDisplay(minecraft);
        success(minecraft, "Minimum profit: " + coins(value) + ".");
    }

    private void setMaxCost(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        Double value;
        if (text.equals("off") || text.equals("none") || text.equals("unlimited")) {
            value = 0d;
        } else {
            value = parseCoins(text);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (value == null) {
            usage(minecraft, "/discordia maxcost <50k|100m|2b|off>");
            return;
        }
        CONFIG.maxItemCost = value;
        saveSettings();
        sendCollectorFilters();
        refreshDisplay(minecraft);
        success(minecraft, value > 0d
                ? "Item cap: " + coins(value) + " (purse still applies)."
                : "Item cap removed - using purse.");
    }

    /** Parses 10 or 10% (percent, not coins). */
    private static Double parsePercent(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.endsWith("%")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) && value >= 0d && value <= 100d ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses coin amounts with optional K/M/B suffixes and commas. */
    private static Double parseCoins(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace(",", "").replace("_", "");
        if (text.isEmpty()) {
            return null;
        }
        double multiplier = 1d;
        char suffix = text.charAt(text.length() - 1);
        if (suffix == 'k' || suffix == 'm' || suffix == 'b') {
            multiplier = suffix == 'k' ? 1_000d : suffix == 'm' ? 1_000_000d : 1_000_000_000d;
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            double value = Double.parseDouble(text) * multiplier;
            return Double.isFinite(value) && value >= 0d ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.format("%.0f", value) : String.format("%.2f", value);
    }

    private void ignore(String rawName) {
        Minecraft minecraft = Minecraft.getInstance();
        String name = decodeIgnoreName(rawName);
        if (name.isEmpty()) {
            usage(minecraft, "/discordia ignore <item>");
            return;
        }
        if (ignored.add(ignoreKey(name))) {
            saveIgnored();
            success(minecraft, "Ignoring " + name + ".");
        } else {
            info(minecraft, "IGNORE", name + " is already hidden.");
        }
    }

    private void unignore(String rawName) {
        Minecraft minecraft = Minecraft.getInstance();
        String name = decodeIgnoreName(rawName);
        if (name.isEmpty()) {
            usage(minecraft, "/discordia unignore <item>");
            return;
        }
        if (ignored.remove(ignoreKey(name))) {
            saveIgnored();
            success(minecraft, "Showing " + name + " again.");
        } else {
            info(minecraft, "IGNORE", name + " was not hidden.");
        }
    }

    private static String decodeIgnoreName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return URLDecoder.decode(raw.trim(), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException ignored) {
            return raw.trim();
        }
    }

    private static String ignoreKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isIgnored(Flip flip) {
        return ignored.contains(ignoreKey(flip.name()));
    }

    private static String ignoreCommand(Flip flip) {
        return "/discordia ignore " + URLEncoder.encode(flip.name(), StandardCharsets.UTF_8);
    }

    private static void loadIgnored() {
        ignored.clear();
        if (ignoredPath == null || !Files.exists(ignoredPath)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(ignoredPath, StandardCharsets.UTF_8)) {
                String key = ignoreKey(line);
                if (!key.isEmpty()) {
                    ignored.add(key);
                }
            }
        } catch (IOException ignoredError) {
            // A missing/corrupt ignore list only affects convenience; keep running.
        }
    }

    private static void saveIgnored() {
        if (ignoredPath == null) {
            return;
        }
        try {
            if (ignoredPath.getParent() != null) {
                Files.createDirectories(ignoredPath.getParent());
            }
            Files.write(ignoredPath, ignored.stream().sorted().toList(), StandardCharsets.UTF_8);
        } catch (IOException ignoredError) {
            // Ignoring still works for this session if the config cannot be written.
        }
    }

    private static void loadSettings() {
        if (settingsPath == null || !Files.exists(settingsPath)) {
            return;
        }
        try (var reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("minMarginPct")) {
                double value = root.get("minMarginPct").getAsDouble();
                if (Double.isFinite(value) && value >= 0d && value <= 100d) {
                    CONFIG.minMarginPct = value;
                    CONFIG.minSnipeMarginPct = value;
                }
            }
            if (root.has("minProfit")) {
                double value = root.get("minProfit").getAsDouble();
                if (Double.isFinite(value) && value >= 0d) {
                    CONFIG.minProfit = value;
                    CONFIG.minSnipeProfit = value;
                }
            }
            if (root.has("maxItemCost")) {
                double value = root.get("maxItemCost").getAsDouble();
                if (Double.isFinite(value) && value >= 0d) {
                    CONFIG.maxItemCost = value;
                }
            }
            if (root.has("showNonBin")) {
                includeNonBin = root.get("showNonBin").getAsBoolean();
            }
            if (root.has("collectorUrl")) {
                collectorUrl = root.get("collectorUrl").getAsString().trim();
            }
            if (root.has("collectorToken")) {
                collectorToken = root.get("collectorToken").getAsString().trim();
            }
            String envUrl = System.getenv("DISCORDIA_COLLECTOR_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                collectorUrl = envUrl.trim();
            }
            String envToken = System.getenv("DISCORDIA_COLLECTOR_TOKEN");
            if (envToken != null && !envToken.isBlank()) {
                collectorToken = envToken.trim();
            }
            CONFIG.bookOnlyMinMarginPct = Math.max(8d, CONFIG.minMarginPct);
            CONFIG.bookOnlyMinProfit = Math.max(1_000_000d, CONFIG.minProfit);
        } catch (Exception ignoredError) {
            // Invalid settings must not prevent the mod from starting.
        }
    }

    private static void saveSettings() {
        if (settingsPath == null) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("minMarginPct", CONFIG.minMarginPct);
            root.addProperty("minProfit", CONFIG.minProfit);
            root.addProperty("maxItemCost", CONFIG.maxItemCost);
            root.addProperty("showNonBin", includeNonBin);
            if (!embeddedCollector && !collectorUrl.isBlank()) {
                root.addProperty("collectorUrl", collectorUrl);
                root.addProperty("collectorToken", collectorToken);
            }
            if (settingsPath.getParent() != null) {
                Files.createDirectories(settingsPath.getParent());
            }
            Files.writeString(settingsPath, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignoredError) {
            // Changes still apply for this session if the config cannot be written.
        }
    }

    private void announceFlips(Minecraft minecraft) {
        if (!enabled || minecraft == null || minecraft.player == null) {
            return;
        }
        List<Flip> candidates = new ArrayList<>(sourceBin());
        if (includeNonBin) {
            candidates.addAll(sourceSnipes());
        }
        candidates.sort(Comparator.comparingDouble(Flip::profit).reversed());
        for (Flip flip : candidates) {
            if (flip.command().isEmpty() || isIgnored(flip)) {
                continue;
            }
            if (!announcedFlipIds.add(flip.id())) {
                continue;
            }
            sendFlip(minecraft, flip);
        }
    }

    private void sendFlip(Minecraft minecraft, Flip flip) {
        MutableComponent line = prefix().append(Component.literal(flip.name()).withStyle(ChatFormatting.AQUA));
        if (!"-".equals(flip.tier())) {
            line.append(Component.literal(" [" + flip.tier() + "]").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (flip.kind() == Flip.Kind.AUCTION) {
            line.append(Component.literal(" [BIN]").withStyle(ChatFormatting.GREEN));
            if (flip.medianPrice() <= 0d) {
                line.append(Component.literal(" [book]").withStyle(ChatFormatting.DARK_GRAY));
            }
        } else if (flip.kind() == Flip.Kind.SNIPE) {
            line.append(Component.literal(" [non-BIN]").withStyle(ChatFormatting.GOLD));
        }
        String unresolved = unresolvedTag(flip);
        if (!unresolved.isEmpty()) {
            line.append(Component.literal(unresolved).withStyle(
                    flip.detail().contains("global volume pending") ? ChatFormatting.YELLOW : ChatFormatting.RED));
        }
        line.append(Component.literal("  " + coins(flip.buyPrice())).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" -> ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(coins(flip.targetPrice())).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  +" + coins(flip.profit())).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(String.format(" (%.0f%%)", flip.marginPct()))
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("  " + timeToSell(flip)).withStyle(ChatFormatting.YELLOW));
        ClickEvent click = new ClickEvent.RunCommand(flip.command());
        HoverEvent hover = new HoverEvent.ShowText(
                Component.literal("Click to run " + flip.command()));
        line.withStyle(style -> style.withClickEvent(click).withHoverEvent(hover));
        // Apply the same click target to each colored segment so the whole line works.
        for (Component child : line.getSiblings()) {
            if (child instanceof MutableComponent mutable) {
                mutable.withStyle(style -> style.withClickEvent(click).withHoverEvent(hover));
            }
        }
        MutableComponent ignoreButton = Component.literal(" [ignore]")
                .withStyle(ChatFormatting.RED)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(ignoreCommand(flip)))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Hide " + flip.name() + " from notifications"))));
        // Append after the view-auction click is applied so the button has its own action.
        line.append(ignoreButton);
        minecraft.player.sendSystemMessage(line);
    }

    private static String unresolvedTag(Flip flip) {
        if (flip.kind() != Flip.Kind.AUCTION) {
            return "";
        }
        if (flip.detail().contains("global volume pending")) {
            return " [pending]";
        }
        if (flip.detail().contains("global volume unavailable")) {
            return " [unavailable]";
        }
        return "";
    }

    private static String timeToSell(Flip flip) {
        if (flip.minutesToSell() <= 0d) {
            return "-";
        }
        String duration = FlipEngine.duration(flip.minutesToSell());
        return flip.kind() == Flip.Kind.SNIPE ? "ends " + duration : "~" + duration;
    }

    private List<Flip> combined() {
        // Keep the in-game action list, key bindings, and notifications auction-only.
        List<Flip> all = new ArrayList<>(sourceBin());
        if (includeNonBin) {
            all.addAll(sourceSnipes());
        }
        return all;
    }

    private static List<Flip> visible(List<Flip> flips) {
        return flips.stream().filter(flip -> !isIgnored(flip)).toList();
    }

    private void printFlips() {
        Minecraft minecraft = Minecraft.getInstance();
        List<Flip> bin = sourceBin();
        List<Flip> snipes = includeNonBin ? sourceSnipes() : List.of();
        int total = bin.size() + snipes.size();
        long updated = sourceUpdated();
        long age = updated == 0 ? -1 : (System.currentTimeMillis() - updated) / 1000L;
        String counts = includeNonBin
                ? String.format("%d BIN, %d non-BIN", bin.size(), snipes.size())
                : String.format("%d %s", bin.size(), bin.size() == 1 ? "BIN flip" : "BIN flips");
        send(minecraft, prefix()
                .append(Component.literal(counts).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  |  " + sourceStatus() + "  |  "
                        + (age < 0 ? "no data" : "tick " + age + "s")).withStyle(ChatFormatting.DARK_GRAY)));
        if (usesCollector() && collectorSnapshot == null) {
            warn(minecraft, "Collector has not sent a snapshot yet: " + collectorStatus + ".");
            sendActions(minecraft);
            return;
        }
        if (total == 0) {
            warn(minecraft, "No flips pass the filters.");
            send(minecraft, Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("/discordia minprofit <amount>").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" lowers the floor").withStyle(ChatFormatting.GRAY)));
            if (!includeNonBin) {
                info(minecraft, "NON-BIN", "Hidden - use /discordia toggle nonbin.");
            }
            sendActions(minecraft);
            return;
        }
        printRows(minecraft, bin);
        if (includeNonBin && !snipes.isEmpty()) {
            send(minecraft, prefix().append(Component.literal("NON-BIN SNIPES (" + snipes.size() + ")")
                    .withStyle(ChatFormatting.GOLD)));
            printRows(minecraft, snipes);
        }
        if (sourcePending() > 0) {
            info(minecraft, "PENDING", "[pending] and [unavailable] mean book-only evidence; "
                    + "global volume is unverified.");
        }
        sendActions(minecraft);
    }

    private void printRows(Minecraft minecraft, List<Flip> flips) {
        for (int i = 0; i < Math.min(TOP_FLIPS, flips.size()); i++) {
            Flip flip = flips.get(i);
            String timing = flip.kind() == Flip.Kind.SNIPE
                    ? (flip.minutesToSell() > 0d ? "ends " + FlipEngine.duration(flip.minutesToSell()) : "")
                    : (flip.minutesToSell() > 0d ? "~" + FlipEngine.duration(flip.minutesToSell()) : "");
            MutableComponent line = Component.literal(String.format("%d) ", i + 1))
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(flip.name()).withStyle(ChatFormatting.AQUA));
            if (!"-".equals(flip.tier())) {
                line.append(Component.literal(" [" + flip.tier() + "]").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(Component.literal(flip.kind() == Flip.Kind.SNIPE ? " [S]" : " [BIN]")
                    .withStyle(flip.kind() == Flip.Kind.SNIPE ? ChatFormatting.GOLD : ChatFormatting.GREEN));
            String unresolved = unresolvedTag(flip);
            if (!unresolved.isEmpty()) {
                line.append(Component.literal(unresolved).withStyle(
                        flip.detail().contains("global volume pending") ? ChatFormatting.YELLOW : ChatFormatting.RED));
            }
            line.append(Component.literal("  " + coins(flip.buyPrice())).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" -> ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(coins(flip.targetPrice())).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("  +" + coins(flip.profit())).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(String.format(" (%.0f%%)", flip.marginPct()))
                            .withStyle(ChatFormatting.DARK_GRAY));
            if (!timing.isEmpty()) {
                line.append(Component.literal("  " + timing).withStyle(ChatFormatting.YELLOW));
            }
            if (!flip.command().isEmpty()) {
                ClickEvent click = new ClickEvent.RunCommand(flip.command());
                HoverEvent hover = new HoverEvent.ShowText(
                        Component.literal("Click to run " + flip.command()));
                line.withStyle(style -> style.withClickEvent(click).withHoverEvent(hover));
                for (Component child : line.getSiblings()) {
                    if (child instanceof MutableComponent mutable) {
                        mutable.withStyle(style -> style.withClickEvent(click).withHoverEvent(hover));
                    }
                }
            }
            send(minecraft, line);
        }
    }

    private void sendActions(Minecraft minecraft) {
        MutableComponent actions = prefix()
                .append(button("open", "/discordia open", "Open the best BIN flip"))
                .append(Component.literal("  "))
                .append(button("copy", "/discordia copy", "Copy the best flip command"))
                .append(Component.literal("  "))
                .append(button("list", "/discordia list", "Refresh this list"))
                .append(Component.literal("  "))
                .append(button(includeNonBin ? "non-BIN on" : "non-BIN off",
                        "/discordia toggle nonbin",
                        includeNonBin ? "Hide non-BIN ending auctions" : "Show non-BIN ending auctions"));
        send(minecraft, actions);
    }

    private static MutableComponent prefix() {
        return Component.literal("[Discordia] ").withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    private static MutableComponent button(String label, String command, String hover) {
        return Component.literal("[" + label + "]").withStyle(ChatFormatting.GRAY)
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private void send(Minecraft minecraft, Component message) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.sendSystemMessage(message);
        }
    }

    private void openBest() {
        List<Flip> all = combined();
        Minecraft minecraft = Minecraft.getInstance();
        if (all.isEmpty()) {
            warn(minecraft, "No flips yet - use /discordia list.");
            return;
        }
        Flip flip = all.get(0);
        String command = flip.command();
        if (command.isEmpty()) {
            String where = flip.kind() == Flip.Kind.KAT ? "open Kat in the Pet Care" : "open the forge";
            warn(minecraft, flip.name() + " is a conversion - " + where + " and use its inputs.");
            return;
        }
        minecraft.player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
        success(minecraft, "Opening " + flip.name() + " - buy it yourself.");
    }

    private void copyBest() {
        List<Flip> all = combined();
        Minecraft minecraft = Minecraft.getInstance();
        if (all.isEmpty()) {
            warn(minecraft, "No flips yet - use /discordia list.");
            return;
        }
        Flip flip = all.get(0);
        if (flip.command().isEmpty()) {
            warn(minecraft, flip.name() + " is a conversion - no auction command.");
            return;
        }
        minecraft.keyboardHandler.setClipboard(flip.command());
        success(minecraft, "Copied " + flip.command());
    }

    private void configureCollector(String raw) {
        Minecraft minecraft = Minecraft.getInstance();
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("status")) {
            if (embeddedCollector) {
                info(minecraft, "COLLECTOR", collectorStatus + "  |  embedded in this mod");
            } else if (collectorUrl.isBlank()) {
                info(minecraft, "COLLECTOR", "off - local scanner active");
            } else {
                info(minecraft, "COLLECTOR", collectorStatus + "  |  " + displayCollectorUrl());
            }
            return;
        }
        if (text.equalsIgnoreCase("off") || text.equalsIgnoreCase("disable")) {
            stopCollector();
            stopEmbeddedCollector();
            collectorUrl = "";
            collectorToken = "";
            embeddedCollector = false;
            saveSettings();
            MARKET.start();
            success(minecraft, "Collector off - local scanner resumed.");
            return;
        }

        String[] parts = text.split("\\s+", 2);
        String url = parts[0].trim();
        String token = parts.length > 1 ? parts[1].trim() : "";
        if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
            usage(minecraft, "/discordia collector <ws://host:port/ws> [token]");
            return;
        }
        try {
            URI.create(url);
        } catch (Exception error) {
            usage(minecraft, "/discordia collector <ws://host:port/ws> [token]");
            return;
        }

        stopCollector();
        stopEmbeddedCollector();
        collectorUrl = url;
        collectorToken = token;
        embeddedCollector = false;
        saveSettings();
        startCollector();
        success(minecraft, "Collector saved - connecting to " + url + ".");
    }

    private void startCollector() {
        if (collectorUrl.isBlank()) {
            MARKET.start();
            return;
        }
        CollectorClient current = collector;
        if (current != null) {
            current.stop();
        }
        collector = null;
        collectorSnapshot = null;
        collectorScanAt = 0L;
        collectorStatus = "connecting";
        MARKET.stop();
        try {
            CollectorClient client = new CollectorClient(URI.create(collectorUrl), collectorToken,
                    new CollectorClient.Events() {
                        @Override
                        public void onSnapshot(CollectorClient.Snapshot snapshot) {
                            collectorSnapshot = snapshot;
                            collectorScanAt = System.currentTimeMillis();
                            collectorStatus = "connected";
                        }

                        @Override
                        public void onHeartbeat(long generatedAt, long lastUpdated) {
                            collectorScanAt = System.currentTimeMillis();
                            collectorStatus = "connected";
                        }

                        @Override
                        public void onStatus(String status) {
                            collectorStatus = status;
                            if ("connected".equals(status)) {
                                sendCollectorFilters();
                            }
                        }
                    });
            collector = client;
            client.start();
        } catch (Exception error) {
            collectorStatus = "invalid URL";
        }
    }

    /**
     * Zero-config mode. The scanner and WebSocket server live in this JVM, bind an ephemeral
     * loopback port, and require no token or external process. The client still speaks the same
     * protocol so filters and snapshots behave identically to an external collector.
     */
    private void startEmbeddedCollector() {
        if (embeddedServer != null) {
            return;
        }
        CollectorScanner scanner = null;
        CollectorServer server = null;
        try {
            FlipConfig config = new FlipConfig();
            config.bazaar = false;
            config.deterministic = false;
            config.kat = false;
            config.auctions = true;
            config.snipes = true;
            config.maxBudgetShare = 1.0d;
            config.budget = 10_000_000_000d;
            config.minMarginPct = 0d;
            config.minSnipeMarginPct = 0d;
            config.minProfit = 0d;
            config.minSnipeProfit = 0d;
            config.maxItemCost = 0d;
            config.scanIntervalMs = 3_000L;
            config.fullRefreshMs = 600_000L;
            config.bookOnlyMinMarginPct = 8d;
            config.bookOnlyMinProfit = 1_000_000d;

            Path stateDir = FabricLoader.getInstance().getConfigDir().resolve("discordia-collector");
            scanner = new CollectorScanner(config, stateDir);
            server = new CollectorServer("127.0.0.1", 0, "", false,
                    scanner::snapshot, scanner::status, scanner::applyFilters);
            server.start();
            scanner.start(server::broadcast, server::broadcast);

            embeddedScanner = scanner;
            embeddedServer = server;
            embeddedCollector = true;
            collectorUrl = "ws://127.0.0.1:" + server.port() + "/ws";
            collectorToken = "";
            startCollector();
        } catch (Exception error) {
            if (scanner != null) {
                scanner.stop();
            }
            if (server != null) {
                server.close();
            }
            embeddedScanner = null;
            embeddedServer = null;
            embeddedCollector = false;
            collectorUrl = "";
            collectorToken = "";
            collectorStatus = "embedded unavailable";
            MARKET.start();
        }
    }

    private void stopEmbeddedCollector() {
        CollectorScanner scanner = embeddedScanner;
        CollectorServer server = embeddedServer;
        embeddedScanner = null;
        embeddedServer = null;
        embeddedCollector = false;
        if (scanner != null) {
            scanner.stop();
        }
        if (server != null) {
            server.close();
        }
    }

    private void sendCollectorFilters() {
        CollectorClient current = collector;
        if (current != null) {
            current.sendFilters(collectorFilters());
        }
    }

    private static JsonObject collectorFilters() {
        JsonObject filters = new JsonObject();
        filters.addProperty("type", "filters");
        filters.addProperty("budget", CONFIG.budget);
        filters.addProperty("minMarginPct", CONFIG.minMarginPct);
        filters.addProperty("minSnipeMarginPct", CONFIG.minSnipeMarginPct);
        filters.addProperty("minProfit", CONFIG.minProfit);
        filters.addProperty("minSnipeProfit", CONFIG.minSnipeProfit);
        filters.addProperty("maxItemCost", CONFIG.maxItemCost);
        filters.addProperty("includeNonBin", includeNonBin);
        return filters;
    }

    private void stopCollector() {
        CollectorClient current = collector;
        collector = null;
        if (current != null) {
            current.stop();
        }
        collectorSnapshot = null;
        collectorScanAt = 0L;
        collectorStatus = "off";
    }

    private boolean usesCollector() {
        return !collectorUrl.isBlank();
    }

    private static String displayCollectorUrl() {
        if (embeddedCollector) {
            return "embedded (in-mod)";
        }
        int query = collectorUrl.indexOf('?');
        return query < 0 ? collectorUrl : collectorUrl.substring(0, query) + "?…";
    }

    private List<Flip> sourceBin() {
        if (!usesCollector()) {
            return visible(MARKET.auctions());
        }
        CollectorClient.Snapshot snapshot = collectorSnapshot;
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.bin().stream()
                .filter(flip -> !isIgnored(flip))
                .filter(flip -> remoteFilter(flip, false))
                .toList();
    }

    private List<Flip> sourceSnipes() {
        if (!usesCollector()) {
            return visible(MARKET.sniping());
        }
        CollectorClient.Snapshot snapshot = collectorSnapshot;
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.nonBin().stream()
                .filter(flip -> !isIgnored(flip))
                .filter(flip -> remoteFilter(flip, true))
                .toList();
    }

    private boolean remoteFilter(Flip flip, boolean snipe) {
        double minProfit = snipe ? CONFIG.minSnipeProfit : CONFIG.minProfit;
        double minMargin = snipe ? CONFIG.minSnipeMarginPct : CONFIG.minMarginPct;
        return flip.buyPrice() <= CONFIG.maxCost()
                && flip.profit() >= minProfit
                && flip.marginPct() >= minMargin;
    }

    private long sourceUpdated() {
        if (!usesCollector()) {
            return MARKET.lastUpdated();
        }
        CollectorClient.Snapshot snapshot = collectorSnapshot;
        return snapshot == null ? 0L : snapshot.lastUpdated();
    }

    private String sourceStatus() {
        return usesCollector() ? collectorStatus : MARKET.status();
    }

    private int sourcePending() {
        if (!usesCollector()) {
            return MARKET.unresolvedFlips();
        }
        CollectorClient.Snapshot snapshot = collectorSnapshot;
        return snapshot == null ? 0 : snapshot.pending();
    }

    private void info(Minecraft minecraft, String label, String text) {
        send(minecraft, prefix()
                .append(Component.literal(label).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  " + text).withStyle(ChatFormatting.GRAY)));
    }

    private void success(Minecraft minecraft, String text) {
        send(minecraft, prefix()
                .append(Component.literal("OK").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  " + text).withStyle(ChatFormatting.GRAY)));
    }

    private void warn(Minecraft minecraft, String text) {
        send(minecraft, prefix()
                .append(Component.literal("!").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  " + text).withStyle(ChatFormatting.GRAY)));
    }

    private void usage(Minecraft minecraft, String text) {
        send(minecraft, prefix()
                .append(Component.literal("USAGE").withStyle(ChatFormatting.RED))
                .append(Component.literal("  " + text).withStyle(ChatFormatting.GRAY)));
    }

    private void statusRow(Minecraft minecraft, String label, String text) {
        send(minecraft, Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(String.format("%-7s ", label)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    private void globalFaultUsage(Minecraft minecraft) {
        usage(minecraft, "/discordia globalfault <mode>");
        info(minecraft, "MODES", "429, timeout, 500, malformed, 404, off");
    }

    private void help() {
        Minecraft minecraft = Minecraft.getInstance();
        send(minecraft, prefix().append(Component.literal("COMMANDS").withStyle(ChatFormatting.AQUA)));
        helpRow(minecraft, "/discordia", "toggle alerts");
        helpRow(minecraft, "/discordia list", "top BIN flips");
        helpRow(minecraft, "/discordia toggle nonbin", "show/hide non-BIN");
        helpRow(minecraft, "/discordia status", "feed and filters");
        helpRow(minecraft, "/discordia open", "open the best flip");
        helpRow(minecraft, "/discordia copy", "copy the best command");
        helpRow(minecraft, "/discordia margin <pct>", "minimum margin");
        helpRow(minecraft, "/discordia minprofit <amount>", "minimum profit");
        helpRow(minecraft, "/discordia maxcost <amount|off>", "item cost cap");
        helpRow(minecraft, "/discordia collector", "embedded collector status");
        helpRow(minecraft, "/discordia collector <url> [token]", "optional external collector");
        helpRow(minecraft, "/discordia ignore <item>", "hide an item");
        helpRow(minecraft, "/discordia unignore <item>", "show an item");
        helpRow(minecraft, "/discordia globalfault <mode>", "developer feed fault");
    }

    private void helpRow(Minecraft minecraft, String command, String description) {
        send(minecraft, Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(command).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  " + description).withStyle(ChatFormatting.GRAY)));
    }

    private static String coins(double value) {
        if (value >= 1_000_000_000d) {
            return String.format("%.2fB", value / 1_000_000_000d);
        }
        if (value >= 1_000_000d) {
            return String.format("%.2fM", value / 1_000_000d);
        }
        if (value >= 1_000d) {
            return String.format("%.1fk", value / 1_000d);
        }
        return String.format("%.0f", value);
    }
}
