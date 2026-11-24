package dev.mmm.discordia.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mmm.discordia.core.ItemVariant;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Contract and alias probe for the SkyCofl analysis endpoint.
 *
 * <p>Run without arguments for the deterministic alias checks. Add {@code --live} to make two
 * deliberately spaced public requests and validate the fields the client depends on. This probe
 * never writes to the global-sales cache.
 */
public final class CoflContractProbe {
    private static final String URL =
            "https://sky.coflnet.com/api/item/price/%s/analysis?days=7";

    public static void main(String[] args) throws Exception {
        ItemVariant pet = new ItemVariant("LION", "", 0, 100, 0, "0", List.of(),
                "", "LEGENDARY", false, List.of());
        check("PET_LION".equals(pet.marketTag()), "pet tag was not mapped to PET_LION");

        ItemVariant item = new ItemVariant("TITANIUM_RING", "", 0, 0, 0, "0", List.of(),
                "", "", false, List.of());
        check("TITANIUM_RING".equals(item.marketTag()), "non-pet tag was rewritten");

        System.out.println("PASS alias mapping: LION -> PET_LION; TITANIUM_RING -> TITANIUM_RING");
        if (args.length == 0 || !args[0].equalsIgnoreCase("--live")) {
            System.out.println("Offline alias checks only; pass --live for the public Cofl contract check.");
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        for (String tag : List.of("TITANIUM_RING", "PET_LION")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(URL, tag)))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Discordia/1.0 contract-probe")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("LIVE " + tag + " HTTP " + response.statusCode());
            check(response.statusCode() == 200, "contract probe got HTTP " + response.statusCode());
            JsonElement parsed = JsonParser.parseString(response.body());
            check(parsed.isJsonObject(), "contract probe got a non-object response");
            JsonObject root = parsed.getAsJsonObject();
            check(root.has("totalSales") && !root.get("totalSales").isJsonNull(),
                    "contract probe response is missing totalSales");
            int count = root.get("totalSales").getAsInt();
            check(count >= 0, "contract probe got a negative totalSales");
            if (count > 0) {
                check(root.has("medianPrice") && !root.get("medianPrice").isJsonNull(),
                        "contract probe response is missing medianPrice");
                check(root.has("salesPerDay") && !root.get("salesPerDay").isJsonNull(),
                        "contract probe response is missing salesPerDay");
                double median = root.get("medianPrice").getAsDouble();
                double perDay = root.get("salesPerDay").getAsDouble();
                check(Double.isFinite(median) && median >= 0d, "contract probe got invalid medianPrice");
                check(Double.isFinite(perDay) && perDay >= 0d, "contract probe got invalid salesPerDay");
                System.out.printf("PASS %s totalSales=%d median=%.0f salesPerDay=%.3f%n",
                        tag, count, median, perDay);
            } else {
                System.out.println("PASS " + tag + " explicit zero");
            }
            Thread.sleep(1_000L);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
