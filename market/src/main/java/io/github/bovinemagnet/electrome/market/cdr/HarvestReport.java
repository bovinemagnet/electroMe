package io.github.bovinemagnet.electrome.market.cdr;

import java.time.Duration;
import java.util.List;

/** What a harvest did, so the result can be judged rather than trusted. */
public record HarvestReport(
        int retailersQueried,
        int plansListed,
        int plansInZone,
        int plansMapped,
        int plansAfterDeduplication,
        List<String> skipped,
        Duration elapsed) {

    public HarvestReport {
        skipped = List.copyOf(skipped);
    }

    public List<String> summary() {
        return List.of(
                retailersQueried + " retailers queried",
                plansListed + " plans published, " + plansInZone + " serving this network",
                plansMapped + " mapped successfully, " + skipped.size() + " skipped",
                plansAfterDeduplication + " distinct tariffs after removing duplicates",
                "took " + elapsed.toSeconds() + " seconds");
    }

    /** The handful of skip reasons worth showing, rather than several hundred lines. */
    public List<String> topSkipReasons(int limit) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (var entry : skipped) {
            int colon = entry.indexOf(':');
            String reason = colon < 0 ? entry : entry.substring(colon + 1).trim();
            counts.merge(reason, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> e.getValue() + " x " + e.getKey())
                .toList();
    }
}
