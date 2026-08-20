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
                plansMapped + " mapped successfully, " + skipped.size() + " skipped ("
                        + coverage() + " of this network mapped)",
                plansAfterDeduplication + " distinct tariffs after removing duplicates",
                "took " + elapsed.toSeconds() + " seconds");
    }

    /**
     * The share of this network's plans the tariff model could hold.
     *
     * <p>One number to watch across releases. A mapper that quietly reads a capped window as
     * unlimited cheap energy reports full coverage; one that refuses it reports less, and the
     * lower figure is the honest one.
     */
    public String coverage() {
        if (plansInZone == 0) {
            return "0%";
        }
        return Math.round(plansMapped * 100.0 / plansInZone) + "%";
    }

    /**
     * Every reason a plan was skipped, commonest first, with a count.
     *
     * <p>All of them, not the leading few. A truncated list of gaps reads as a complete list of
     * gaps, and the whole point of refusing a plan rather than mispricing it is that somebody
     * can see what was refused.
     */
    public List<String> skipReasons() {
        return countedReasons().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getValue() + " x " + e.getKey())
                .toList();
    }

    /** The handful of skip reasons worth showing, rather than several hundred lines. */
    public List<String> topSkipReasons(int limit) {
        return countedReasons().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> e.getValue() + " x " + e.getKey())
                .toList();
    }

    private java.util.Map<String, Integer> countedReasons() {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (var entry : skipped) {
            int colon = entry.indexOf(':');
            String reason = colon < 0 ? entry : entry.substring(colon + 1).trim();
            counts.merge(reason, 1, Integer::sum);
        }
        return counts;
    }
}
