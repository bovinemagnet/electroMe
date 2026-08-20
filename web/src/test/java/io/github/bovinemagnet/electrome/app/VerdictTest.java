package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.DiscountScope;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The sentence above the table.
 *
 * <p>A ranking of two hundred tariffs is evidence. The answer is which plan this household
 * should be on, and the one way to get that wrong that matters is to name a plan they cannot
 * sign up to — or to name the second-cheapest without admitting that something cheaper was
 * passed over.
 */
class VerdictTest {

    private static final DateRange DAY =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1));

    private final PlanQueryService service = new PlanQueryService();

    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            readings.add(new IntervalReading(
                    LocalDate.of(2025, 1, 1).atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), new BigDecimal("0.5"), Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(String id, String rate, Charge... extra) {
        var charges = new ArrayList<Charge>();
        charges.add(new DailySupply(new BigDecimal("100")));
        charges.add(new FlatRate(new BigDecimal(rate)));
        charges.addAll(List.of(extra));
        return new Plan(id, id, "Retailer " + id, DistributionZone.AUSNET, charges,
                true, null, null);
    }

    /** Costs the plans and ranks them, marking the named plan as the household's own. */
    private static Comparison comparisonOf(String baselineId, Plan... plans) {
        var engine = new CostingEngine();
        var bills = new ArrayList<BillBreakdown>();
        for (var plan : plans) {
            bills.add(engine.cost(usage(), plan, DAY));
        }
        bills.sort(Comparator.comparing(BillBreakdown::totalRounded));

        BigDecimal baselineTotal = null;
        for (var bill : bills) {
            if (bill.plan().id().equals(baselineId)) {
                baselineTotal = bill.totalRounded();
            }
        }
        var results = new ArrayList<PlanResult>();
        for (int i = 0; i < bills.size(); i++) {
            var bill = bills.get(i);
            results.add(new PlanResult(
                    bill,
                    bill.totalRounded().subtract(bills.get(0).totalRounded()),
                    i == 0,
                    baselineTotal == null ? null : bill.totalRounded().subtract(baselineTotal),
                    bill.plan().id().equals(baselineId)));
        }
        return new Comparison(DAY, results, baselineId);
    }

    private static PlanQuery showingEverything() {
        return PlanQuery.of("", "ANY", List.of(), null, null, "all");
    }

    private static PlanQuery showingEverythingOwning(String... have) {
        return PlanQuery.of("", "ANY", List.of(), null, null, "all",
                List.of(have), null, null);
    }

    // -----------------------------------------------------------------

    /**
     * The acceptance case: a cheaper plan the household cannot have is named in the table but
     * never in the verdict.
     */
    @Test
    void doesNotRecommendAPlanTheHouseholdCannotSignUpTo() {
        var comparison = comparisonOf("mine",
                plan("needs-ev", "10"), plan("open", "20"), plan("mine", "30"));
        var conditions = Map.of("needs-ev", List.of("You must own an electric vehicle"));

        var page = service.apply(comparison, showingEverything(), conditions);

        assertThat(page.verdict().known()).isTrue();
        assertThat(page.verdict().planName()).isEqualTo("open");
        // Still visible: the reader can see what they are missing and why.
        assertThat(page.results()).extracting(r -> r.bill().plan().id())
                .contains("needs-ev");
        // And the sentence admits something cheaper was passed over.
        assertThat(page.verdict().cheaperThanEverythingIneligible()).isFalse();
    }

    @Test
    void recommendsThePlanOnceTheHouseholdSaysItHasTheEquipment() {
        var comparison = comparisonOf("mine",
                plan("needs-ev", "10"), plan("open", "20"), plan("mine", "30"));
        var conditions = Map.of("needs-ev", List.of("You must own an electric vehicle"));

        var page = service.apply(comparison, showingEverythingOwning("EV"), conditions);

        assertThat(page.verdict().planName()).isEqualTo("needs-ev");
        assertThat(page.verdict().requirements()).containsExactly(Requirement.ELECTRIC_VEHICLE);
        assertThat(page.verdict().cheaperThanEverythingIneligible()).isTrue();
    }

    /** An eligibility this code could not classify is never treated as satisfied. */
    @Test
    void neverRecommendsAPlanWhoseRequirementItCouldNotRead() {
        var comparison = comparisonOf("mine", plan("odd", "10"), plan("open", "20"));
        var conditions = Map.of("odd", List.of("Only available to residents of the moon"));

        var page = service.apply(comparison, showingEverythingOwning("EV", "SOLAR"), conditions);

        assertThat(page.verdict().planName()).isEqualTo("open");
    }

    @Test
    void statesWhatSwitchingToTheWinnerIsWorth() {
        var comparison = comparisonOf("mine", plan("open", "20"), plan("mine", "30"));
        var verdict = service.apply(comparison, showingEverything(), Map.of()).verdict();

        // 24 kWh a day: $4.80 against $7.20, plus $1 supply on each.
        assertThat(verdict.total()).isEqualByComparingTo("5.80");
        assertThat(verdict.comparedToBaseline()).isTrue();
        assertThat(verdict.savesMoney()).isTrue();
        assertThat(verdict.saving()).isEqualByComparingTo("2.40");
        assertThat(verdict.isCurrentPlan()).isFalse();
    }

    @Test
    void saysWhenTheWinningTotalDependsOnEarningADiscount() {
        var discounted = plan("discounted", "22",
                new Discount("Pay on time", DiscountBasis.PERCENTAGE, DiscountScope.TOTAL,
                        new BigDecimal("20"), "pay every bill by its due date"));
        var comparison = comparisonOf("mine", discounted, plan("open", "20"), plan("mine", "30"));

        var verdict = service.apply(comparison, showingEverything(), Map.of()).verdict();

        assertThat(verdict.planName()).isEqualTo("discounted");
        assertThat(verdict.assumesDiscountCondition()).isTrue();
        assertThat(verdict.discountConditions()).contains("pay every bill by its due date");
        assertThat(verdict.qualified()).isTrue();
    }

    @Test
    void countsThePlansItCouldNotPriceAtAll() {
        var comparison = comparisonOf("mine", plan("open", "20"), plan("mine", "30"));
        var page = service.apply(comparison, showingEverything(), Map.of(),
                List.of("6 x seasonal demand charges", "3 x shared allowance"), 9);

        assertThat(page.verdict().hasUnpriceable()).isTrue();
        assertThat(page.verdict().unpriceable()).isEqualTo(9);
        assertThat(page.verdict().unpriceableReasons()).hasSize(2);
    }

    @Test
    void hasNoVerdictWhenNothingWasCosted() {
        var page = service.apply(new Comparison(DAY, List.of()), showingEverything(), Map.of());
        assertThat(page.verdict().known()).isFalse();
    }

    // -----------------------------------------------------------------
    // The two new filters.
    // -----------------------------------------------------------------

    @Test
    void canHideEveryPlanThatReliesOnAConditionalDiscount() {
        var discounted = plan("discounted", "22",
                new Discount("Pay on time", DiscountBasis.PERCENTAGE, DiscountScope.TOTAL,
                        new BigDecimal("20"), "pay every bill by its due date"));
        var comparison = comparisonOf("mine", discounted, plan("open", "20"), plan("mine", "30"));

        var query = PlanQuery.of("", "ANY", List.of(), null, null, "all",
                List.of(), "true", null);
        var page = service.apply(comparison, query, Map.of());

        assertThat(page.results()).extracting(r -> r.bill().plan().id())
                .containsExactlyInAnyOrder("open", "mine");
        assertThat(page.query().activeCriteria())
                .contains("no plans relying on a conditional discount");
    }

    @Test
    void canHidePlansSavingLessThanAThreshold() {
        var comparison = comparisonOf("mine",
                plan("big", "10"), plan("small", "29"), plan("mine", "30"));

        var query = PlanQuery.of("", "ANY", List.of(), null, null, "all",
                List.of(), null, "2.00");
        var page = service.apply(comparison, query, Map.of());

        // "small" saves 24c and "mine" saves nothing; only "big" clears $2.
        assertThat(page.results()).extracting(r -> r.bill().plan().id())
                .containsExactly("big");
    }
}
