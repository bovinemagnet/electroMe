package io.github.bovinemagnet.electrome.market.cdr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Discovers which retailers publish product reference data, and where. */
public final class CdrRegisterClient {

    private static final String REGISTER_URL =
            "https://api.cdr.gov.au/cdr-register/v1/energy/data-holders/brands/summary";

    /** The register's summary endpoint is version 2. */
    public static final String VERSION = "2";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String userAgent;

    public CdrRegisterClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                "electroMe/0.0.0 (household plan comparison)");
    }

    public CdrRegisterClient(HttpClient http, String userAgent) {
        this.http = http;
        this.userAgent = userAgent;
    }

    public List<RetailerBrand> brands() {
        var request = HttpRequest.newBuilder(URI.create(REGISTER_URL))
                .header("x-v", VERSION)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(
                        "Register returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the CDR register", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reading the CDR register", e);
        }
    }

    /** Separated from the fetch so it can be tested against a recorded fixture. */
    public static List<RetailerBrand> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Register response is not valid JSON", e);
        }
        var brands = new ArrayList<RetailerBrand>();
        for (JsonNode node : root.path("data")) {
            brands.add(new RetailerBrand(
                    text(node, "brandName"),
                    text(node, "productBaseUri"),
                    text(node, "lastUpdated")));
        }
        return List.copyOf(brands);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
