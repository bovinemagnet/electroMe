package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

/**
 * Harvests the live market once.
 *
 * <p>Skipped unless {@code -Delectrome.live=true}. Every other test in this module runs against
 * recorded fixtures; this one exists so the whole path can be exercised deliberately, and so a
 * change in the published data is discoverable rather than a surprise in production.
 */
class LiveHarvestTest {

    @Test
    void harvestsTheAusNetZone() {
        assumeTrue(Boolean.getBoolean("electrome.live"),
                "Live harvest is opt-in; run with -Delectrome.live=true");

        var cache = new CdrCache(Path.of(System.getProperty(
                "electrome.cache", "build/live-cdr-cache")));
        var result = new MarketHarvest(new CdrRegisterClient(), new CdrClient(), cache)
                .harvest(DistributionZone.AUSNET);

        System.out.println("\n================ live harvest ================");
        result.report().summary().forEach(l -> System.out.println("  " + l));
        System.out.println("\n  why plans were skipped:");
        result.report().topSkipReasons(8).forEach(l -> System.out.println("    " + l));

        System.out.println("\n  cheapest ten distinct tariffs by peak rate:");
        result.plans().stream()
                .filter(p -> p.charges().stream().anyMatch(TimeOfUse.class::isInstance))
                .sorted(Comparator.comparing(LiveHarvestTest::peakRate))
                .limit(10)
                .forEach(p -> System.out.printf("    %-46s %-22s peak %6sc%n",
                        truncate(p.name(), 44), truncate(p.retailer(), 20),
                        peakRate(p).setScale(2, RoundingMode.HALF_UP)));
        System.out.println("==============================================\n");

        assertThat(result.report().retailersQueried()).isGreaterThan(50);
        assertThat(result.report().plansInZone()).isGreaterThan(50);
        assertThat(result.plans()).isNotEmpty();
        // Deduplication must actually bite, or the comparison table is unusable.
        assertThat(result.report().plansAfterDeduplication())
                .isLessThan(result.report().plansMapped());
    }

    private static BigDecimal peakRate(io.github.bovinemagnet.electrome.core.tariff.Plan plan) {
        return plan.charges().stream()
                .filter(TimeOfUse.class::isInstance)
                .flatMap(c -> ((TimeOfUse) c).bands().stream())
                .map(Band::centsPerKWh)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
