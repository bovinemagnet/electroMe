package io.github.bovinemagnet.electrome.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.app.Comparison;
import io.github.bovinemagnet.electrome.app.PlanResult;
import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The dashboard's comparison shortlist.
 *
 * <p>Harvesting the market multiplied the comparison table's content ninety-fold without
 * changing the table, which buried the one actionable fact this software produces at row 3 of
 * 184. The dashboard's job is the answer; browsing is a different job on a different screen.
 */
class DashboardShortlistTest {

    private static final DateRange RANGE =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        var start = LocalDateTime.of(2025, 1, 1, 0, 0);
        for (int i = 0; i < 96; i++) {
            readings.add(new IntervalReading(start.plusMinutes(30L * i), Duration.ofMinutes(30),
                    new BigDecimal("0.25"), Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(String id, String usageCents) {
        return new Plan(id, "Plan " + id, "Retailer", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal("100.00")),
                        new FlatRate(new BigDecimal(usageCents))),
                true, null, null);
    }

    /**
     * Six plans. Cheapest first: needs-solar, cheap-open, mine, benchmark, filler-1, filler-2.
     */
    private static Comparison comparison() {
        var plans = List.of(
                plan("needs-solar", "10.00"),
                plan("cheap-open", "12.00"),
                plan("mine", "14.00"),
                plan("benchmark", "16.00"),
                plan("filler-1", "18.00"),
                plan("filler-2", "20.00"));

        var engine = new CostingEngine();
        var bills = new ArrayList<BillBreakdown>();
        for (var p : plans) {
            bills.add(engine.cost(usage(), p, RANGE));
        }
        bills.sort(Comparator.comparing(BillBreakdown::totalRounded));

        var results = new ArrayList<PlanResult>();
        for (int i = 0; i < bills.size(); i++) {
            results.add(new PlanResult(bills.get(i),
                    bills.get(i).totalRounded().subtract(bills.get(0).totalRounded()), i == 0));
        }
        return new Comparison(RANGE, results);
    }

    private static final Map<String, List<String>> CONDITIONS =
            Map.of("needs-solar", List.of("Must have solar"));

    /** The two plans defined as files: the household's own tariff, and the benchmark. */
    private static final Set<String> LOCAL = Set.of("mine", "benchmark");

    private static List<PlanRow> shortlist() {
        return Dashboard.shortlistOf(comparison(), CONDITIONS, LOCAL);
    }

    private static List<String> idsOf(List<PlanRow> rows) {
        return rows.stream().map(r -> r.result().bill().plan().id()).toList();
    }

    @Test
    void showsTheCheapestPlanAnyoneCanSignUpTo() {
        assertThat(idsOf(shortlist())).contains("cheap-open");
    }

    @Test
    void showsTheCheapestPlanOverallEvenWhenItHasRequirements() {
        // Omitting it entirely would hide the best available number; the requirement is named
        // beside it instead.
        assertThat(idsOf(shortlist())).contains("needs-solar");
        assertThat(shortlist())
                .filteredOn(row -> "needs-solar".equals(row.result().bill().plan().id()))
                .singleElement()
                .satisfies(row -> assertThat(row.conditional()).isTrue());
    }

    @Test
    void showsThePlansDefinedAsFiles() {
        // The household's own tariff and the regulated benchmark are what a reader compares
        // against, and are the reason those files exist.
        assertThat(idsOf(shortlist())).contains("mine", "benchmark");
    }

    @Test
    void leavesTheRestToTheBrowser() {
        assertThat(idsOf(shortlist())).doesNotContain("filler-1", "filler-2");
        assertThat(comparison().results().size() - shortlist().size()).isEqualTo(2);
    }

    @Test
    void keepsTheShortlistInCostOrder() {
        assertThat(shortlist())
                .extracting(row -> row.result().total())
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void namesAPlanOnceEvenWhenItQualifiesSeveralWays() {
        // The cheapest open plan is also a local file here, so it earns its place twice.
        var rows = Dashboard.shortlistOf(comparison(), CONDITIONS, Set.of("cheap-open"));

        assertThat(idsOf(rows)).containsOnlyOnce("cheap-open");
    }

    @Test
    void theShortlistNeverExceedsWhatItIsDrawnFrom() {
        // Nothing is invented; the rest of the rows move to the screen built for them.
        assertThat(shortlist().size()).isLessThan(comparison().results().size());
    }

    @Test
    void aSmallComparisonIsEntirelyShortlisted() {
        // Two plans need no editing down, and "4 more" would be a lie.
        var twoPlans = new Comparison(RANGE, comparison().results().subList(0, 2));

        assertThat(Dashboard.shortlistOf(twoPlans, CONDITIONS, LOCAL)).hasSize(2);
    }

    @Test
    void anEmptyComparisonHasAnEmptyShortlist() {
        var empty = new Comparison(RANGE, List.of());

        assertThat(Dashboard.shortlistOf(empty, Map.of(), Set.of())).isEmpty();
    }

    @Test
    void whenEveryPlanHasRequirementsTheCheapestIsStillShown() {
        var allConditional = Map.of(
                "needs-solar", List.of("Must have solar"),
                "cheap-open", List.of("Must have a battery"),
                "mine", List.of("Must be a member"),
                "benchmark", List.of("Must have an EV"),
                "filler-1", List.of("Must have solar"),
                "filler-2", List.of("Must have solar"));
        var rows = Dashboard.shortlistOf(comparison(), allConditional, Set.of());

        assertThat(idsOf(rows)).contains("needs-solar");
    }

    /** Twenty plans, all defined as files, so far more qualify than may be shown. */
    private static Comparison manyLocalPlans() {
        var engine = new CostingEngine();
        var bills = new ArrayList<BillBreakdown>();
        for (int i = 0; i < 20; i++) {
            bills.add(engine.cost(usage(), plan("local-" + i, (10 + i) + ".00"), RANGE));
        }
        bills.sort(Comparator.comparing(BillBreakdown::totalRounded));

        var results = new ArrayList<PlanResult>();
        for (int i = 0; i < bills.size(); i++) {
            results.add(new PlanResult(bills.get(i),
                    bills.get(i).totalRounded().subtract(bills.get(0).totalRounded()), i == 0));
        }
        return new Comparison(RANGE, results);
    }

    private static Set<String> everyId(Comparison comparison) {
        var ids = new java.util.LinkedHashSet<String>();
        for (var result : comparison.results()) {
            ids.add(result.bill().plan().id());
        }
        return ids;
    }

    @Test
    void theShortlistIsBoundedHoweverManyPlansQualify() {
        // A household usually keeps two or three plan files, but nothing stops it keeping two
        // hundred — and a "shortlist" that grows with them is the table this phase replaced.
        var many = manyLocalPlans();

        assertThat(Dashboard.shortlistOf(many, Map.of(), everyId(many)))
                .hasSizeLessThanOrEqualTo(Dashboard.SHORTLIST_LIMIT);
    }

    @Test
    void theCheapestPlansSurviveTheBound() {
        // If something must be dropped, it is the dear end — the end a reader is least likely
        // to act on, and the end the browser exists to show.
        var many = manyLocalPlans();
        var rows = Dashboard.shortlistOf(many, Map.of(), everyId(many));

        assertThat(idsOf(rows)).contains("local-0");
        assertThat(idsOf(rows)).doesNotContain("local-19");
        assertThat(rows).extracting(row -> row.result().total())
                .isSortedAccordingTo(Comparator.naturalOrder());
    }
}
