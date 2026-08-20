package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.appliance.ApplianceLoad;
import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
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
 * Two cars, not one.
 *
 * <p>This household has a Model 3 Performance and a Model Y Long Range: different weekly
 * mileage, different consumption per hundred kilometres, different charging patterns. Modelling
 * one of them and doubling it would be wrong in both directions at once — the two cars want
 * different amounts of energy and are plugged in at different times, and on a plan with a cheap
 * window only so wide, the second car is not priced like the first.
 */
class TwoElectricVehiclesTest {

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

    /** 300 km a week at 17 kWh/100 km, charged five nights. */
    private static SchedulableLoad modelThree() {
        return car("Model 3 Performance", "300", "17", 5, 18 * 60, 7 * 60);
    }

    /** 200 km a week at 18.5 kWh/100 km, charged three nights, plugged in later. */
    private static SchedulableLoad modelY() {
        return car("Model Y Long Range", "200", "18.5", 3, 20 * 60, 6 * 60);
    }

    private static SchedulableLoad car(String label, String kmPerWeek, String kWhPerHundredKm,
            int runsPerWeek, int from, int by) {
        var perRun = ApplianceCatalogue.chargeKWh(
                new BigDecimal(kmPerWeek), new BigDecimal(kWhPerHundredKm), runsPerWeek);
        return new SchedulableLoad(label, perRun, new BigDecimal("7.4"),
                from, by, runsPerWeek, Set.of(), true, false);
    }

    private static Plan flat() {
        return new Plan("flat", "Flat", "Retailer", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal("100.00")),
                        new FlatRate(new BigDecimal("30.00"))),
                true, null, null);
    }

    private static Plan overnight() {
        return new Plan("deep", "Deep Overnight", "Retailer", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal("100.00")),
                        new TimeOfUse(List.of(
                                new Band(0, 6 * 60, DaySelector.ALL, new BigDecimal("4.99")),
                                new Band(6 * 60, 16 * 60, DaySelector.ALL, new BigDecimal("28.00")),
                                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("55.00")),
                                new Band(21 * 60, 24 * 60, DaySelector.ALL,
                                        new BigDecimal("28.00"))))),
                true, null, null);
    }

    private static BigDecimal addedCost(ApplianceService.Outcome outcome, String planId) {
        return outcome.forPlan(planId).annualApplianceCost();
    }

    @Test
    void schedulesBothCarsRatherThanCollapsingThemIntoOne() {
        var outcome = service.evaluate(
                usage(), List.of(flat(), overnight()),
                List.<ApplianceLoad>of(modelThree(), modelY()), YEAR);

        assertThat(outcome.loads()).hasSize(2);
        var scheduled = outcome.forPlan("deep").scheduled();
        assertThat(scheduled).hasSize(2);
        assertThat(scheduled).extracting(s -> s.load().label())
                .containsExactly("Model 3 Performance", "Model Y Long Range");
        // Different mileage and efficiency mean different energy per charge: 10.2 kWh against
        // 12.333 kWh. A model that averaged the two would show them equal.
        assertThat(scheduled.get(0).schedule().deliveredKWh())
                .isNotEqualByComparingTo(scheduled.get(1).schedule().deliveredKWh());
    }

    /**
     * On a flat tariff the marginal rate never changes, so two cars cost exactly what each
     * costs alone. Anything else means one of them was lost, or counted twice.
     */
    @Test
    void twoCarsCostTheSumOfTheirIndividualCostsOnAFlatTariff() {
        var plans = List.of(flat());
        var both = service.evaluate(
                usage(), plans, List.<ApplianceLoad>of(modelThree(), modelY()), YEAR);
        var first = service.evaluate(usage(), plans, modelThree(), YEAR);
        var second = service.evaluate(usage(), plans, modelY(), YEAR);

        assertThat(addedCost(both, "flat"))
                .isEqualByComparingTo(addedCost(first, "flat").add(addedCost(second, "flat")));
    }

    @Test
    void addingTheSecondCarCostsMoreThanTheFirstAlone() {
        var plans = List.of(overnight());
        var both = service.evaluate(
                usage(), plans, List.<ApplianceLoad>of(modelThree(), modelY()), YEAR);
        var alone = service.evaluate(usage(), plans, modelThree(), YEAR);

        assertThat(addedCost(both, "deep")).isGreaterThan(addedCost(alone, "deep"));
    }

    /** The single-appliance entry point still answers the single-appliance question. */
    @Test
    void oneCarBehavesAsItAlwaysHas() {
        var outcome = service.evaluate(usage(), List.of(flat()), modelThree(), YEAR);
        assertThat(outcome.loads()).hasSize(1);
        assertThat(outcome.load().label()).isEqualTo("Model 3 Performance");
        assertThat(outcome.forPlan("flat").schedule()).isNotNull();
    }
}
