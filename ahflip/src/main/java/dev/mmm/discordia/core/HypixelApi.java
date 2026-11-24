package dev.mmm.discordia.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Thin client for the public Hypixel endpoints. No API key needed for these two. */
public final class HypixelApi {
    public static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    /** Public static item catalog: every SkyBlock item id, including items with no auction history. */
    public static final String ITEMS_URL = "https://api.hypixel.net/resources/skyblock/items";
    /** Current v2 spelling; used as a fallback if the legacy resource route is unavailable. */
    public static final String ITEMS_V2_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    public static final String AUCTIONS_URL = "https://api.hypixel.net/v2/skyblock/auctions?page=";
    /** ~172 KB and shares the market tick with the auction dump: heartbeat plus sale feed. */
    public static final String ENDED_URL = "https://api.hypixel.net/v2/skyblock/auctions_ended";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HypixelApi() {
    }

    /**
     * One page of the auction dump. Every auction created in a tick lands on page 0 — verified
     * over one tick against the live API, 130 of 130 new listings — so page 0 is the whole
     * new-listing feed, not a 1-in-36 sample. Within the page the order is not by start time.
     */
    public static JsonObject auctionPage(int page) throws IOException, InterruptedException {
        return getJson(AUCTIONS_URL + page);
    }

    public static JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Discordia/1.0 (client-side flip finder)")
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) {
            throw new IOException("Unexpected response from " + url);
        }
        JsonObject object = parsed.getAsJsonObject();
        if (object.has("success") && !object.get("success").getAsBoolean()) {
            throw new IOException("Hypixel returned success=false for " + url);
        }
        return object;
    }

    /**
     * Fetches every auction page, in parallel. Page 0 tells us how many there are.
     * Returns pages in index order.
     */
    public static List<JsonObject> auctionPages() throws IOException, InterruptedException {
        JsonObject first = getJson(AUCTIONS_URL + 0);
        int totalPages = first.has("totalPages") ? first.get("totalPages").getAsInt() : 1;

        List<CompletableFuture<JsonObject>> futures = new ArrayList<>(Math.max(0, totalPages - 1));
        for (int page = 1; page < totalPages; page++) {
            String url = AUCTIONS_URL + page;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return getJson(url);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        List<JsonObject> pages = new ArrayList<>(totalPages);
        pages.add(first);
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(90, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Auction fetch did not complete: " + e.getMessage(), e);
        }
        for (CompletableFuture<JsonObject> future : futures) {
            pages.add(future.join());
        }
        pages.sort(Comparator.comparingInt(page -> page.has("page") ? page.get("page").getAsInt() : 0));
        return pages;
    }
}
