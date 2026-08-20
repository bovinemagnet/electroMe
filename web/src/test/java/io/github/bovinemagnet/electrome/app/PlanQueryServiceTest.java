package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.app.PlanQuery.PlanShape;
import io.github.bovinemagnet.electrome.app.PlanQuery.RequirementFilter;
import io.github.bovinemagnet.electrome.app.PlanQuery.SortBy;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Filtering and sorting over an already-costed comparison.
 *
 * <p>Real plans costed by the real engine, so a filter can never disagree with the arithmetic it
 * is narrowing.
 */
class PlanQueryServiceTest {

    private static final DateRange RANGE =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    private final PlanQueryService service = new PlanQueryService();

    /** Two days of flat half-hourly consumption: enough to give every plan a real bill. */
    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        var start = LocalDateTime.of(2025, 1, 1, 0, 0);
        for (int i = 0; i < 96; i++) {
            readings.add(new IntervalReading(
                    start.plusMinutes(30L * i), Duration.ofMinutes(30),
                    new BigDecimal("0.25"), Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(String id, String name, String retailer, String supply,
            Charge usage) {
        return new Plan(id, name, retailer, DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal(supply)), usage), true, null, null);
    }

    private static TimeOfUse tou(String offPeak, String peak) {
        return new TimeOfUse(List.of(
                new Band(0, 16 * 60, DaySelector.ALL, new BigDecimal(offPeak)),
                new Band(16 * 60, 24 * 60, DaySelector.ALL, new BigDecimal(peak))));
    }

    /**
     * Four plans spanning every shape, two retailers and a clear cost order.
     *
     * <p>Cheapest first: budget-flat, agl-tou, origin-tou, origin-block.
     */
    private static Comparison comparison() {
        var plans = List.of(
                plan("agl-tou", "AGL Value TOU", "AGL", "100.00", tou("20.00", "45.00")),
                plan("origin-tou", "Origin Everyday", "Origin", "120.00", tou("25.00", "50.00")),
                plan("budget-flat", "Budget Flat", "AGL", "90.00", new FlatRate(new BigDecimal("22.00"))),
                plan("origin-block", "Origin Blocks", "Origin", "130.00",
                        new Tiered(ResetPeriod.QUARTERLY, List.of(
                                new Tier(new BigDecimal("100"), new BigDecimal("28.00")),
                                new Tier(null, new BigDecimal("32.00"))))));

        var engine = new CostingEngine();
        var bills = new ArrayList<io.github.bovinemagnet.electrome.core.cost.BillBreakdown>();
        for (var plan : plans) {
            bills.add(engine.cost(usage(), plan, RANGE));
        }
        bills.sort(java.util.Comparator.comparing(
                io.github.bovinemagnet.electrome.core.cost.BillBreakdown::totalRounded));

        var results = new ArrayList<PlanResult>();
        for (int i = 0; i < bills.size(); i++) {
            results.add(new PlanResult(bills.get(i),
                    bills.get(i).totalRounded().subtract(bills.get(0).totalRounded()), i == 0));
        }
        return new Comparison(RANGE, results);
    }

    /** Origin Everyday needs solar; nothing else has a requirement. */
    private static Map<String, List<String>> conditions() {
        return Map.of("origin-tou", List.of("Must have a net-metered solar PV system"));
    }

    private static List<String> ids(PlanPage page) {
        return page.results().stream().map(r -> r.bill().plan().id()).toList();
    }

    // -----------------------------------------------------------------
    // The costed figures are never touched by a filter.
    // -----------------------------------------------------------------

    @Test
    void filteringNarrowsWhatIsShownAndNeverWhatWasCosted() {
        var all = comparison();
        var page = service.apply(all, PlanQuery.defaults().withSearch("budget"), conditions());

        assertThat(ids(page)).containsExactly("budget-flat");
        // A count like "3 hidden" is only truthful if everything was still costed.
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.results().get(0).total())
                .isEqualByComparingTo(all.results().get(0).total());
    }

    // -----------------------------------------------------------------
    // Each filter on its own.
    // -----------------------------------------------------------------

    @Test
    void searchMatchesThePlanName() {
        assertThat(ids(service.apply(comparison(),
                        PlanQuery.defaults().withSearch("everyday"), Map.of())))
                .containsExactly("origin-tou");
    }

    @Test
    void searchMatchesTheRetailer() {
        assertThat(ids(service.apply(comparison(),
                        PlanQuery.defaults().withSearch("agl"), Map.of())))
                .containsExactlyInAnyOrder("agl-tou", "budget-flat");
    }

    @Test
    void searchIgnoresCase() {
        assertThat(ids(service.apply(comparison(),
                        PlanQuery.defaults().withSearch("ORIGIN"), Map.of())))
                .hasSize(2);
    }

    @Test
    void retailerFilterKeepsOnlyTheNamedRetailers() {
        var query = PlanQuery.of(null, "ANY", List.of("Origin"), null, null, "all");

        assertThat(ids(service.apply(comparison(), query, Map.of())))
                .containsExactlyInAnyOrder("origin-tou", "origin-block");
    }

    @Test
    void shapeFilterDistinguishesFlatTimeOfUseAndBlock() {
        assertThat(ids(service.apply(comparison(),
                        PlanQuery.of(null, "ANY", null, "FLAT", null, "all"), Map.of())))
                .containsExactly("budget-flat");

        assertThat(ids(service.apply(comparison(),
                        PlanQuery.of(null, "ANY", null, "TIME_OF_USE", null, "all"), Map.of())))
                .containsExactlyInAnyOrder("agl-tou", "origin-tou");

        assertThat(ids(service.apply(comparison(),
                        PlanQuery.of(null, "ANY", null, "BLOCK", null, "all"), Map.of())))
                .containsExactly("origin-block");
    }

    @Test
    void limitTruncatesButLeavesTheMatchedCountIntact() {
        var query = PlanQuery.of(null, "ANY", null, null, null, "10");
        var page = service.apply(comparison(), query, Map.of());

        assertThat(page.results()).hasSize(4);
        assertThat(page.matched()).isEqualTo(4);
        assertThat(page.truncated()).isFalse();
    }

    // -----------------------------------------------------------------
    // The requirement filter and its default.
    // -----------------------------------------------------------------

    @Test
    void theDefaultHidesPlansThatNeedEquipmentOrAMembership() {
        var page = service.apply(comparison(), PlanQuery.defaults(), conditions());

        assertThat(ids(page)).doesNotContain("origin-tou");
        assertThat(page.hiddenByRequirements()).isEqualTo(1);
    }

    @Test
    void theCountOfWhatIsHiddenIsAlwaysAvailable() {
        // "37 plans hidden because they need equipment or a membership" has to be truthful
        // whichever way the filter is set.
        var showingAll = PlanQuery.of(null, "ANY", null, null, null, "all");

        assertThat(service.apply(comparison(), showingAll, conditions()).hiddenByRequirements())
                .isZero();
        assertThat(service.apply(comparison(), showingAll, conditions()).withRequirements())
                .isEqualTo(1);
    }

    @Test
    void requirementsOnlyShowsExactlyTheOppositeSet() {
        var query = PlanQuery.of(null, "REQUIREMENTS_ONLY", null, null, null, "all");

        assertThat(ids(service.apply(comparison(), query, conditions())))
                .containsExactly("origin-tou");
    }

    @Test
    void aPlanWithAnEmptyConditionListCountsAsOpen() {
        var conditions = Map.of("origin-tou", List.<String>of());

        assertThat(ids(service.apply(comparison(), PlanQuery.defaults(), conditions)))
                .contains("origin-tou");
    }

    @Test
    void hiddenCountReflectsOnlyPlansTheOtherCriteriaWouldHaveShown() {
        // Reporting a global count next to a filtered table would tell the reader they could
        // reveal a plan that the search has already excluded.
        var query = PlanQuery.defaults().withSearch("agl");

        assertThat(service.apply(comparison(), query, conditions()).hiddenByRequirements())
                .isZero();
    }

    // -----------------------------------------------------------------
    // Sorting.
    // -----------------------------------------------------------------

    @Test
    void sortsByTotalCheapestFirstByDefault() {
        var page = service.apply(comparison(),
                PlanQuery.of(null, "ANY", null, null, null, "all"), Map.of());

        assertThat(page.results()).isSortedAccordingTo(
                java.util.Comparator.comparing(PlanResult::total));
        assertThat(ids(page).get(0)).isEqualTo("budget-flat");
    }

    @Test
    void sortsByNameAlphabetically() {
        var query = PlanQuery.of(null, "ANY", null, null, "NAME", "all");

        assertThat(service.apply(comparison(), query, Map.of()).results())
                .extracting(PlanResult::planName)
                .containsExactly("AGL Value TOU", "Budget Flat", "Origin Blocks",
                        "Origin Everyday");
    }

    @Test
    void sortsBySupplyChargeCheapestFirst() {
        var query = PlanQuery.of(null, "ANY", null, null, "SUPPLY_CHARGE", "all");

        assertThat(ids(service.apply(comparison(), query, Map.of())))
                .containsExactly("budget-flat", "agl-tou", "origin-tou", "origin-block");
    }

    @Test
    void sortsByPeakRateUsingTheDearestUsageRateOnThePlan() {
        // A flat plan has one rate, and that rate is its peak. Ranking it as though it had no
        // peak would float every flat plan to the top of a peak-rate sort.
        var query = PlanQuery.of(null, "ANY", null, null, "PEAK_RATE", "all");

        assertThat(ids(service.apply(comparison(), query, Map.of())))
                .containsExactly("budget-flat", "origin-block", "agl-tou", "origin-tou");
    }

    @Test
    void sortsByAverageRate() {
        var query = PlanQuery.of(null, "ANY", null, null, "AVERAGE_RATE", "all");

        assertThat(service.apply(comparison(), query, Map.of()).results())
                .isSortedAccordingTo(java.util.Comparator.comparing(
                        r -> r.bill().averageCentsPerKWh()));
    }

    // -----------------------------------------------------------------
    // Combinations, and the empty state.
    // -----------------------------------------------------------------

    @Test
    void criteriaCombine() {
        var query = PlanQuery.of("origin", "ANY", List.of("Origin"), "TIME_OF_USE", "NAME", "all");

        assertThat(ids(service.apply(comparison(), query, conditions())))
                .containsExactly("origin-tou");
    }

    @Test
    void aCombinationThatMatchesNothingReportsWhyRatherThanRenderingBlank() {
        var query = PlanQuery.of("origin", "ANY", List.of("AGL"), null, null, "all");
        var page = service.apply(comparison(), query, Map.of());

        assertThat(page.results()).isEmpty();
        assertThat(page.empty()).isTrue();
        assertThat(page.query().activeCriteria())
                .contains("search “origin”")
                .contains("retailer AGL");
    }

    @Test
    void anEmptyComparisonIsNotAnError() {
        var page = service.apply(new Comparison(RANGE, List.of()), PlanQuery.defaults(), Map.of());

        assertThat(page.results()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.retailers()).isEmpty();
    }

    @Test
    void offersEveryRetailerPresentSoTheControlCanBeBuilt() {
        var page = service.apply(comparison(), PlanQuery.defaults(), conditions());

        // Every retailer, not only those surviving the current filter: a control that removes
        // its own options as you use it cannot be used to widen a search.
        assertThat(page.retailers()).containsExactly("AGL", "Origin");
    }

    @Test
    void shapeOfClassifiesTheMostDistinguishingChargeOnThePlan() {
        var timeOfUse = plan("x", "X", "R", "100.00", tou("20.00", "45.00"));

        assertThat(PlanShape.of(timeOfUse)).isEqualTo(PlanShape.TIME_OF_USE);
        assertThat(PlanShape.of(plan("y", "Y", "R", "100.00", new FlatRate(new BigDecimal("22.00")))))
                .isEqualTo(PlanShape.FLAT);
    }

    @Test
    void everySortOrderProducesTheSameSetJustInADifferentOrder() {
        for (var sort : SortBy.values()) {
            var query = new PlanQuery("", RequirementFilter.ANY, java.util.Set.of(),
                    PlanShape.ANY, sort, Integer.MAX_VALUE);

            assertThat(ids(service.apply(comparison(), query, Map.of())))
                    .as("sort by %s", sort)
                    .containsExactlyInAnyOrder(
                            "agl-tou", "origin-tou", "budget-flat", "origin-block");
        }
    }
}
