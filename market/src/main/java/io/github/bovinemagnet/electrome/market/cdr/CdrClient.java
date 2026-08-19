package io.github.bovinemagnet.electrome.market.cdr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Fetches product reference data. Returns raw JSON; mapping happens elsewhere. */
public final class CdrClient {

    /** The plan list endpoint is version 1. */
    public static final String LIST_VERSION = "1";

    /** The plan detail endpoint is version 3. Sending version 1 fails obscurely. */
    public static final String DETAIL_VERSION = "3";

    /** The API returns HTTP 422 Field/InvalidPageSize above this. */
    public static final int MAX_PAGE_SIZE = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String userAgent;

    public CdrClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                "electroMe/0.0.0 (household plan comparison)");
    }

    public CdrClient(HttpClient http, String userAgent) {
        this.http = http;
        this.userAgent = userAgent;
    }

    public String fetchPlanList(RetailerBrand brand, int page, int pageSize) {
        int size = Math.min(pageSize, MAX_PAGE_SIZE);
        // There is no geography parameter. Unknown parameters are silently ignored, so sending
        // one would return everything while appearing to have worked.
        String url = brand.productBaseUri() + "/cds-au/v1/energy/plans"
                + "?type=ALL&fuelType=ELECTRICITY&effective=CURRENT"
                + "&page=" + page + "&page-size=" + size;
        return get(url, LIST_VERSION);
    }

    public String fetchPlanDetail(RetailerBrand brand, String planId) {
        // The @ in a Victorian plan id needs no encoding in practice, but encoding the path
        // segment costs nothing and removes the question.
        String encoded = URLEncoder.encode(planId, StandardCharsets.UTF_8).replace("+", "%20");
        return get(brand.productBaseUri() + "/cds-au/v1/energy/plans/" + encoded, DETAIL_VERSION);
    }

    private String get(String url, String version) {
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("x-v", version)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url
                        + ": " + truncate(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException("Request failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted requesting " + url, e);
        }
    }

    public static List<PlanSummary> parseList(String json) {
        JsonNode root = read(json);
        var plans = new ArrayList<PlanSummary>();
        for (JsonNode node : root.path("data").path("plans")) {
            var distributors = new ArrayList<String>();
            for (JsonNode distributor : node.path("geography").path("distributors")) {
                distributors.add(distributor.asText());
            }
            String brandName = text(node, "brandName");
            plans.add(new PlanSummary(
                    text(node, "planId"),
                    text(node, "displayName"),
                    brandName != null ? brandName : text(node, "brand"),
                    text(node, "type"),
                    text(node, "fuelType"),
                    text(node, "customerType"),
                    distributors,
                    text(node, "lastUpdated")));
        }
        return List.copyOf(plans);
    }

    public static int totalPages(String json) {
        return read(json).path("meta").path("totalPages").asInt(1);
    }

    public static int totalRecords(String json) {
        return read(json).path("meta").path("totalRecords").asInt(0);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Response is not valid JSON", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String truncate(String body) {
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
