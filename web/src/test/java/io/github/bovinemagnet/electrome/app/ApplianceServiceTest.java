package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
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
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Scheduling an appliance against every plan, and re-ranking under the new load.
 *
 * <p>The third question this phase answers, and the interesting one: adding twelve kilowatt
 * hours a night to a household whose tariff prices midnight to six at 4.99c is not a uniform
 * increase. It lands almost entirely in the cheapest window, which makes that window's rate far
 * more valuable than it is today.
 */
class ApplianceServiceTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2025, 12, 31);
    private static final DateRange YEAR = new DateRange(START, END);

    private final ApplianceService service = new ApplianceService();

    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        for (var day = START; !day.isAfter(END); day = day.plusDays(1)) {
            for (int slot = 0; slot < 48; slot++) {
                int minute = slot * 30;
                boolean evening = minute >= 16 * 60 && minute < 21 * 60;
                readings.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT).plusMinutes(minute),
                        Duration.ofMinutes(30),
                        evening ? new BigDecimal("0.8") : new BigDecimal("0.25"),
                        Quality.ACTUAL));
            }
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(String id, String name, String supply, Charge usage) {
        return new Plan(id, name, "Retailer", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal(supply)), usage), true, null, null);
    }

    /** A deep overnight trough: exactly the shape an electric vehicle exploits. */
    private static TimeOfUse deepOvernight() {
        return new TimeOfUse(List.of(
                new Band(0, 6 * 60, DaySelector.ALL, new BigDecimal("4.99")),
                new Band(6 * 60, 16 * 60, DaySelector.ALL, new BigDecimal("28.00")),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("55.00")),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal("28.00"))));
    }

    /** No overnight trough, but cheaper across the day: wins until an EV arrives. */
    private static TimeOfUse shallow() {
        return new TimeOfUse(List.of(
                new Band(0, 16 * 60, DaySelector.ALL, new BigDecimal("22.00")),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("38.00")),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal("22.00"))));
    }

    private static SchedulableLoad electricVehicle() {
        return new SchedulableLoad("Electric vehicle", new BigDecimal("11"),
                new BigDecimal("7.4"), 18 * 60, 7 * 60, 7, Set.of(), true, false);
    }

    private static List<Plan> plans() {
        return List.of(
                plan("shallow", "Shallow", "100.00", shallow()),
                plan("deep", "Deep Overnight", "100.00", deepOvernight()),
                plan("flat", "Flat", "100.00", new FlatRate(new BigDecimal("30.00"))));
    }

    // -----------------------------------------------------------------

    @Test
    void schedulesTheApplianceIntoTheCheapestWindowOfEachPlan() {
        var outcome = service.evaluate(usage(), plans(), electricVehicle(), YEAR);

        // Each plan gets its own schedule; a shared one would understate the plan whose trough
        // sits somewhere else.
        assertThat(outcome.perPlan()).hasSize(3);
        var deep = outcome.forPlan("deep");
        assertThat(deep.schedule().slots()).allSatisfy(slot -> assertThat(slot).isLessThan(12));
    }

    @Test
    void owningAnElectricVehicleChangesWhichPlanIsBest() {
        // The finding the phase exists to produce. On this household's consumption the shallow
        // tariff wins: 17.5 kWh a day, most of it outside the evening, at 22c beats a deep
        // overnight trough it barely uses. Add 11 kWh a night landing entirely in that trough
        // and the deep tariff wins comfortably.
        var outcome = service.evaluate(usage(), plans(), electricVehicle(), YEAR);

        assertThat(outcome.bestPlanBefore().id()).isEqualTo("shallow");
        assertThat(outcome.bestPlanAfter().id()).isEqualTo("deep");
        assertThat(outcome.bestPlanChanged()).isTrue();
    }

    @Test
    void saysPlainlyWhenTheBestPlanDoesNotChange() {
        var onlyOne = List.of(plan("deep", "Deep Overnight", "100.00", deepOvernight()));
        var outcome = service.evaluate(usage(), onlyOne, electricVehicle(), YEAR);

        assertThat(outcome.bestPlanChanged()).isFalse();
    }

    @Test
    void reportsWhatTheApplianceCostsOnTheRecommendedPlan() {
        var outcome = service.evaluate(usage(), plans(), electricVehicle(), YEAR);
        var best = outcome.forPlan(outcome.bestPlanAfter().id());

        // 11 kWh a night, all year, at 4.99c is roughly $200.
        assertThat(best.annualApplianceCost()).isBetween(
                new BigDecimal("150"), new BigDecimal("260"));
    }

    @Test
    void theCostComesFromAFullCostingNotTheMarginalEstimate() {
        var outcome = service.evaluate(usage(), plans(), electricVehicle(), YEAR);
        var best = outcome.forPlan("deep");

        // The discipline: choose by marginal rate, report by full costing, so the figure is
        // exact even where the chosen slot might not be.
        assertThat(best.annualApplianceCost()).isEqualByComparingTo(
                best.totalWith().subtract(best.totalWithout()));
    }

    @Test
    void showsTheCostOfTheObviousAlternative() {
        // Plugging in and charging straight away is what happens without a timer, and the gap
        // between that and the recommendation is the value of setting one.
        var outcome = service.evaluate(usage(), plans(), electricVehicle(), YEAR);
        var best = outcome.forPlan("deep");

        assertThat(best.naiveCost()).isGreaterThan(best.annualApplianceCost());
        assertThat(best.savingFromTiming()).isGreaterThan(BigDecimal.ZERO);
        assertThat(best.naiveDescription()).containsIgnoringCase("18:00");
    }

    @Test
    void aFlatPlanSaysTimingMakesNoDifferenceRatherThanNamingASlot() {
        var outcome = service.evaluate(usage(), plans(), electricVehicle(), YEAR);
        var flat = outcome.forPlan("flat");

        assertThat(flat.timingMatters()).isFalse();
        assertThat(flat.savingFromTiming()).isEqualByComparingTo("0");
    }

    @Test
    void aLoadThatCannotFitIsReportedRatherThanCosted() {
        var tooSlow = new SchedulableLoad("Slow charger", new BigDecimal("11"),
                new BigDecimal("2"), 2 * 60, 6 * 60, 7, Set.of(), true, false);
        var outcome = service.evaluate(usage(), plans(), tooSlow, YEAR);

        assertThat(outcome.fits()).isFalse();
        assertThat(outcome.shortfallKWh()).isEqualByComparingTo("3");
        assertThat(outcome.workableDeadline()).isEqualTo("07:30");
    }

    @Test
    void theRecommendationIsASpecificClockTime() {
        var outcome = service.evaluate(usage(), plans(), electricVehicle(), YEAR);
        var best = outcome.forPlan("deep");

        assertThat(best.schedule().describe()).matches(".*\\d\\d:\\d\\d.*");
        assertThat(best.schedule().startTime()).matches("\\d\\d:\\d\\d");
    }

    @Test
    void everyPlanIsStillRankedUnderTheNewLoad() {
        var outcome = service.evaluate(usage(), plans(), electricVehicle(), YEAR);

        assertThat(outcome.after().results()).hasSize(3);
        assertThat(outcome.after().results()).isSortedAccordingTo(
                java.util.Comparator.comparing(PlanResult::total));
        assertThat(outcome.before().results()).hasSize(3);
    }

    @Test
    void anEmptyPlanListIsNotAnError() {
        var outcome = service.evaluate(usage(), List.of(), electricVehicle(), YEAR);

        assertThat(outcome.perPlan()).isEmpty();
        assertThat(outcome.bestPlanAfter()).isNull();
        assertThat(outcome.bestPlanChanged()).isFalse();
    }
}
