package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The seasonal split over the household's own year and its own plan files.
 *
 * <p>The synthetic tests prove the arithmetic. This one proves the arithmetic survives contact
 * with real tariffs and a real consumption shape, which is where a decomposition assumption
 * would actually break. It skips without the household export, which is not in the repository.
 */
@QuarkusTest
@TestProfile(SeasonalRealDataTest.RealData.class)
class SeasonalRealDataTest {

    public static class RealData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "../MyUsageData_19-08-2026.csv",
                    "electrome.plans.dir", "../plans",
                    "electrome.baseline.plan", "agl-flat-current");
        }
    }

    /** Twelve months to the day before the export was taken. */
    private static final DateRange YEAR =
            new DateRange(LocalDate.of(2025, 8, 19), LocalDate.of(2026, 8, 18));

    @Inject SeasonalService seasons;
    @Inject UsageStore usage;

    @Test
    void everyRealPlanSplitsCleanlyDownTheMonthBoundary() {
        assumeTrue(usage.loaded(), "no household export to check");

        var report = seasons.report(YEAR, SeasonSplit.DEFAULT);
        assertThat(report.empty()).isFalse();

        for (var plan : report.plans()) {
            assertThat(plan.decomposes())
                    .describedAs("%s does not split cleanly: %s", plan.planId(), plan.caveat())
                    .isTrue();
            // Within a cent: all three figures are rounded to cents independently.
            assertThat(plan.first().add(plan.second()).subtract(plan.wholeYear()).abs())
                    .describedAs("%s: %s over %s plus %s over %s against its year, %s",
                            plan.planId(), plan.first(), report.split().label(),
                            plan.second(), report.split().otherLabel(), plan.wholeYear())
                    .isLessThanOrEqualTo(new java.math.BigDecimal("0.01"));
        }
        assertThat(report.caveats()).isEmpty();
    }

    /**
     * Switching can never be worse than staying put.
     *
     * <p>The best single plan is available in both seasons, so the best-per-season pair is at
     * worst that same plan twice. A negative saving would mean the two halves had been costed
     * against a year they do not belong to.
     */
    @Test
    void switchingIsNeverWorseThanTheBestSinglePlan() {
        assumeTrue(usage.loaded(), "no household export to check");

        var report = seasons.report(YEAR, SeasonSplit.DEFAULT);
        assertThat(report.savingFromSwitching())
                .isGreaterThanOrEqualTo(java.math.BigDecimal.ZERO);
        assertThat(report.switchingTotal())
                .isLessThanOrEqualTo(report.bestSingle().wholeYear());
    }
}
