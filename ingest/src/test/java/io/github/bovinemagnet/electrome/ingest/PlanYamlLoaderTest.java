package io.github.bovinemagnet.electrome.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.InvalidPlanException;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PlanYamlLoaderTest {

    private static final String REFERENCE = """
            id: current-tou
            name: Current tariff
            retailer: Unknown
            zone: AUSNET
            gstInclusive: true
            charges:
              - type: dailySupply
                cents: 123.20
              - type: timeOfUse
                bands:
                  - { from: "00:00", to: "06:00", days: ALL, cents: 4.99 }
                  - { from: "06:00", to: "11:00", days: ALL, cents: 24.77 }
                  - { from: "11:00", to: "16:00", days: ALL, cents: 24.77 }
                  - { from: "16:00", to: "21:00", days: ALL, cents: 49.54 }
                  - { from: "21:00", to: "24:00", days: ALL, cents: 24.77 }
            """;

    @Test
    void loadsTheReferenceTariff() {
        var plan = PlanYamlLoader.load(REFERENCE);
        assertThat(plan.id()).isEqualTo("current-tou");
        assertThat(plan.zone()).isEqualTo(DistributionZone.AUSNET);
        assertThat(plan.charges()).hasSize(2);
        assertThat(plan.charges().get(0)).isInstanceOf(DailySupply.class);
        assertThat(((DailySupply) plan.charges().get(0)).centsPerDay())
                .isEqualByComparingTo("123.20");
        var tou = (TimeOfUse) plan.charges().get(1);
        assertThat(tou.bands()).hasSize(5);
        assertThat(tou.bands().get(3).centsPerKWh()).isEqualByComparingTo("49.54");
        assertThat(tou.bands().get(3).describe()).isEqualTo("16:00-21:00");
    }

    @Test
    void treatsEndOfDaySentinelCorrectly() {
        var tou = (TimeOfUse) PlanYamlLoader.load(REFERENCE).charges().get(1);
        assertThat(tou.bands().get(4).toMinuteOfDay()).isEqualTo(1440);
    }

    @Test
    void defaultsDaySelectorToAll() {
        var yaml = """
                id: p
                name: P
                retailer: R
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: dailySupply
                    cents: 100
                  - type: timeOfUse
                    bands:
                      - { from: "00:00", to: "24:00", cents: 25 }
                """;
        var tou = (TimeOfUse) PlanYamlLoader.load(yaml).charges().get(1);
        assertThat(tou.bands().get(0).days()).isEqualTo(DaySelector.ALL);
    }

    @Test
    void grossesUpExclusiveRatesByGst() {
        var yaml = """
                id: p
                name: P
                retailer: R
                zone: AUSNET
                gstInclusive: false
                charges:
                  - type: dailySupply
                    cents: 100
                  - type: flatRate
                    cents: 20
                """;
        var plan = PlanYamlLoader.load(yaml);
        assertThat(((DailySupply) plan.charges().get(0)).centsPerDay())
                .isEqualByComparingTo("110.0");
        assertThat(plan.gstInclusive()).isTrue();
    }

    @Test
    void readsValidityDates() {
        var yaml = """
                id: vdo
                name: VDO
                retailer: R
                zone: AUSNET
                gstInclusive: true
                validFrom: 2026-07-01
                validTo: 2027-06-30
                charges:
                  - type: dailySupply
                    cents: 128.24
                  - type: flatRate
                    cents: 31.98
                """;
        var plan = PlanYamlLoader.load(yaml);
        assertThat(plan.validFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(plan.validTo()).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    void loadsEveryChargeKind() {
        var yaml = """
                id: everything
                name: Everything
                retailer: R
                zone: UNITED_ENERGY
                gstInclusive: true
                charges:
                  - type: dailySupply
                    cents: 100
                  - type: tiered
                    reset: QUARTERLY
                    tiers:
                      - { upToKWh: 1020, cents: 31.98 }
                      - { cents: 29.50 }
                  - type: demand
                    from: "16:00"
                    to: "21:00"
                    days: ALL
                    reset: MONTHLY
                    centsPerKWPerDay: 20
                  - type: solarFeedIn
                    cents: 3.3
                  - type: discount
                    name: Pay on time
                    basis: PERCENTAGE
                    scope: USAGE
                    value: 5
                    condition: pay every bill by its due date
                """;
        var plan = PlanYamlLoader.load(yaml);
        assertThat(plan.charges()).hasSize(5);
        assertThat(plan.zone()).isEqualTo(DistributionZone.UNITED_ENERGY);
    }

    private static final String CAPPED = """
            id: globird-4hr-free
            name: 4 Hour Free
            retailer: GloBird
            zone: AUSNET
            gstInclusive: true
            charges:
              - type: dailySupply
                cents: 127.49
              - type: timeOfUse
                bands:
                  - from: "11:00"
                    to: "15:00"
                    days: ALL
                    reset: DAILY
                    tiers:
                      - { upToKWh: 50, cents: 0.0 }
                      - { cents: 9.405 }
                  - from: "15:00"
                    to: "11:00"
                    days: ALL
                    reset: DAILY
                    tiers:
                      - { upToKWh: 15, cents: 31.559 }
                      - { cents: 33.963 }
            """;

    @Test
    void loadsABandCappedByDailyBlocks() {
        var tou = (TimeOfUse) PlanYamlLoader.load(CAPPED).charges().get(1);
        var free = tou.bands().get(0);
        assertThat(free.capped()).isTrue();
        assertThat(free.reset()).isEqualTo(ResetPeriod.DAILY);
        assertThat(free.tiers()).hasSize(2);
        assertThat(free.tiers().get(0).thresholdKWh()).isEqualByComparingTo("50");
        assertThat(free.tiers().get(0).centsPerKWh()).isEqualByComparingTo("0");
        assertThat(free.tiers().get(1).unbounded()).isTrue();
        assertThat(free.tiers().get(1).centsPerKWh()).isEqualByComparingTo("9.405");
        // The peak band's cap is its own, and wraps midnight.
        assertThat(tou.bands().get(1).wrapsMidnight()).isTrue();
        assertThat(tou.bands().get(1).tiers().get(0).thresholdKWh()).isEqualByComparingTo("15");
    }

    @Test
    void appliesGstToEveryBlockOfACappedBand() {
        var exclusive = CAPPED.replace("gstInclusive: true", "gstInclusive: false");
        var tou = (TimeOfUse) PlanYamlLoader.load(exclusive).charges().get(1);
        assertThat(tou.bands().get(0).tiers().get(1).centsPerKWh())
                .isEqualByComparingTo("10.3455");
    }

    @Test
    void rejectsABandDeclaringBothCentsAndTiers() {
        var yaml = CAPPED.replace(
                "        reset: DAILY\n", "        reset: DAILY\n        cents: 5.0\n");
        assertThatThrownBy(() -> PlanYamlLoader.load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of cents or tiers");
    }

    @Test
    void rejectsTiersWithNoResetPeriod() {
        var yaml = CAPPED.replace("        reset: DAILY\n", "");
        assertThatThrownBy(() -> PlanYamlLoader.load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a reset period");
    }

    @Test
    void rejectsAResetPeriodOnASingleRateBand() {
        var yaml = REFERENCE.replace(
                "days: ALL, cents: 4.99 }", "days: ALL, cents: 4.99, reset: DAILY }");
        assertThatThrownBy(() -> PlanYamlLoader.load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only applies to a band with tiers");
    }

    @Test
    void readsADiscountsCondition() {
        var yaml = """
                id: p
                name: P
                retailer: R
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: dailySupply
                    cents: 100
                  - type: flatRate
                    cents: 30
                  - type: discount
                    name: Pay on time
                    basis: PERCENTAGE
                    scope: USAGE
                    value: 5
                    condition: pay every bill by its due date
                  - type: discount
                    name: Welcome credit
                    basis: FIXED
                    scope: TOTAL
                    value: 500
                """;
        var charges = PlanYamlLoader.load(yaml).charges();
        var conditional = (Discount) charges.get(2);
        assertThat(conditional.conditional()).isTrue();
        assertThat(conditional.condition()).isEqualTo("pay every bill by its due date");
        var unconditional = (Discount) charges.get(3);
        assertThat(unconditional.conditional()).isFalse();
        assertThat(unconditional.condition()).isNull();
    }

    /**
     * A missing GST flag is an error, not a default.
     *
     * <p>Fact sheets are published both ways. A plan loaded on the wrong assumption is out by
     * 10% and every figure on the screen still looks entirely plausible, which is the worst
     * kind of wrong this application can be.
     */
    @Test
    void rejectsAPlanThatDoesNotSayWhetherItsRatesIncludeGst() {
        var yaml = REFERENCE.replace("gstInclusive: true\n", "");
        assertThatThrownBy(() -> PlanYamlLoader.load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must state gstInclusive");
    }

    @Test
    void rejectsAnUnknownChargeType() {
        var yaml = """
                id: p
                name: P
                retailer: R
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: teleportation
                    cents: 1
                """;
        assertThatThrownBy(() -> PlanYamlLoader.load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teleportation");
    }

    @Test
    void rejectsAPlanThatFailsValidation() {
        var yaml = """
                id: gappy
                name: Gappy
                retailer: R
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: dailySupply
                    cents: 100
                  - type: timeOfUse
                    bands:
                      - { from: "00:00", to: "06:00", cents: 5 }
                """;
        assertThatThrownBy(() -> PlanYamlLoader.load(yaml))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("gappy");
    }

    @Test
    void namesTheMissingFieldWhenOneIsAbsent() {
        var yaml = """
                id: p
                name: P
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: dailySupply
                    cents: 100
                  - type: flatRate
                    cents: 25
                """;
        assertThatThrownBy(() -> PlanYamlLoader.load(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retailer");
    }

    @Test
    void loadedBandsAreUsableByTheEngine() {
        var tou = (TimeOfUse) PlanYamlLoader.load(REFERENCE).charges().get(1);
        Band peak = tou.bands().get(3);
        assertThat(peak.matchesTime(1020)).isTrue();
        assertThat(peak.centsPerKWh()).isEqualByComparingTo(new BigDecimal("49.54"));
    }

    @Test
    void everyFixturePlanFileLoads() {
        // Reads the test's own fixtures rather than the application's plans/ directory, which
        // the user is meant to add to; asserting a count against that would break on first use.
        var plans = PlanYamlLoader.loadDirectory(Path.of("src/test/resources/plans"));
        assertThat(plans).hasSize(2);
        assertThat(plans).extracting(Plan::id)
                .containsExactly("reference-tou", "vdo-ausnet-2026-27");
    }

    @Test
    void everyShippedPlanFileLoads() {
        // The application's own plans/ directory must always be loadable, but its contents
        // are the user's, so this asserts validity rather than a count.
        var shipped = PlanYamlLoader.loadDirectory(Path.of("..", "plans"));
        assertThat(shipped).isNotEmpty();
        assertThat(shipped).allSatisfy(p -> assertThat(p.charges()).isNotEmpty());
    }

    // ---------- feed-in credits, exactly as the tariff reference documents them ----------

    /** The banded example printed in tariff-reference.adoc must load, or the page is wrong. */
    @Test
    void loadsTheDocumentedBandedFeedIn() {
        var plan = PlanYamlLoader.load("""
                id: banded-feed-in
                name: Banded feed in
                retailer: Test
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: flatRate
                    cents: 30.0
                  - type: solarFeedIn
                    bands:
                      - { from: "00:00", to: "10:00", days: ALL, cents: 3.85 }
                      - { from: "10:00", to: "14:00", days: ALL, cents: 1.65 }
                      - { from: "14:00", to: "16:00", days: ALL, cents: 3.85 }
                      - { from: "16:00", to: "21:00", days: ALL, cents: 11.0 }
                      - { from: "21:00", to: "24:00", days: ALL, cents: 3.85 }
                """);

        var feedIn = plan.charges().stream()
                .filter(SolarFeedIn.class::isInstance).map(SolarFeedIn.class::cast)
                .findFirst().orElseThrow();
        assertThat(feedIn.bands()).hasSize(5);
        assertThat(feedIn.bestRate()).isEqualByComparingTo("11.0");
        assertThat(feedIn.lowestRate()).isEqualByComparingTo("1.65");
        assertThat(feedIn.varies()).isTrue();
    }

    /** The capped example printed in tariff-reference.adoc, likewise. */
    @Test
    void loadsTheDocumentedCappedFeedIn() {
        var plan = PlanYamlLoader.load("""
                id: capped-feed-in
                name: Capped feed in
                retailer: Test
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: flatRate
                    cents: 30.0
                  - type: solarFeedIn
                    bands:
                      - { from: "00:00", to: "17:30", days: ALL, cents: 0 }
                      - from: "17:30"
                        to: "21:30"
                        days: ALL
                        reset: DAILY
                        tiers:
                          - { upToKWh: 15, cents: 18.7 }
                          - { cents: 2.2 }
                      - { from: "21:30", to: "24:00", days: ALL, cents: 0 }
                """);

        var feedIn = plan.charges().stream()
                .filter(SolarFeedIn.class::isInstance).map(SolarFeedIn.class::cast)
                .findFirst().orElseThrow();
        assertThat(feedIn.capped()).isTrue();
        assertThat(feedIn.bestRate()).isEqualByComparingTo("18.7");
        assertThat(feedIn.lowestRate()).isEqualByComparingTo("0");
    }

    /** Both forms at once has no single meaning, so it is refused rather than resolved. */
    @Test
    void rejectsAFeedInDeclaringBothCentsAndBands() {
        assertThatThrownBy(() -> PlanYamlLoader.load("""
                id: both
                name: Both
                retailer: Test
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: flatRate
                    cents: 30.0
                  - type: solarFeedIn
                    cents: 3.3
                    bands:
                      - { from: "00:00", to: "24:00", days: ALL, cents: 3.3 }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of cents or bands");
    }
}
