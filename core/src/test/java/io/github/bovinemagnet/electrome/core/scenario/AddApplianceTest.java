package io.github.bovinemagnet.electrome.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.schedule.LoadScheduler;
import io.github.bovinemagnet.electrome.core.schedule.MarginalRateProfile;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Adding load that is not in the metered data at all.
 *
 * <p>Includes the two findings the phase exists to produce: an electric vehicle lands in the
 * cheapest window on a time-of-use tariff, and moves to the middle of the day once the household
 * has solar.
 */
class AddApplianceTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2025, 12, 31);
    private static final DateRange YEAR = new DateRange(START, END);

    /** A year of even consumption, so any change is attributable to the appliance. */
    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        for (var day = START; !day.isAfter(END); day = day.plusDays(1)) {
            for (int slot = 0; slot < 48; slot++) {
                readings.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT)
                                .plusMinutes(slot * 30L),
                        Duration.ofMinutes(30), new BigDecimal("0.4"), Quality.ACTUAL));
            }
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    /** The same year, exporting between 10:00 and 15:00. */
    private static UsageData usageWithSolar() {
        var base = usage();
        var export = new ArrayList<IntervalReading>();
        for (var day = START; !day.isAfter(END); day = day.plusDays(1)) {
            for (int slot = 20; slot < 30; slot++) {
                export.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT)
                                .plusMinutes(slot * 30L),
                        Duration.ofMinutes(30), new BigDecimal("1.5"), Quality.ACTUAL));
            }
        }
        return new UsageData(base.consumption(), UsageSeries.of(export));
    }

    private static Plan plan(Charge... charges) {
        var all = new ArrayList<Charge>();
        all.add(new DailySupply(new BigDecimal("123.20")));
        all.addAll(List.of(charges));
        return new Plan("p", "Plan", "Retailer", DistributionZone.AUSNET, all, true, null, null);
    }

    /** The household's real tariff shape: 4.99c overnight against 49.54c in the evening. */
    private static TimeOfUse householdTariff() {
        return new TimeOfUse(List.of(
                new Band(0, 6 * 60, DaySelector.ALL, new BigDecimal("4.99")),
                new Band(6 * 60, 16 * 60, DaySelector.ALL, new BigDecimal("24.77")),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("49.54")),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal("24.77"))));
    }

    /** 11 kWh a night, 7.4 kW charger, plugged in 18:00, wanted by 07:00, five nights. */
    private static SchedulableLoad electricVehicle() {
        return new SchedulableLoad("Electric vehicle", new BigDecimal("11"),
                new BigDecimal("7.4"), 18 * 60, 7 * 60, 5, Set.of(), true, false);
    }

    private static AddAppliance scheduledOn(Plan plan, UsageData against, SchedulableLoad load) {
        var profile = MarginalRateProfile.of(plan, against, DaySelector.ALL);
        return new AddAppliance(load, LoadScheduler.schedule(load, profile));
    }

    // -----------------------------------------------------------------
    // The insight this phase exists to produce.
    // -----------------------------------------------------------------

    @Test
    void anElectricVehicleIsChargedInTheCheapestOvernightWindow() {
        // 4.99c against 49.54c is a 44.55c spread. If the scheduler cannot find it, it is not
        // working.
        var scenario = scheduledOn(plan(householdTariff()), usage(), electricVehicle());

        assertThat(scenario.schedule().slots())
                .allSatisfy(slot -> assertThat(slot).isLessThan(12));
        assertThat(scenario.schedule().startTime()).isBetween("00:00", "05:30");
    }

    @Test
    void onAFlatTariffTimingMakesNoDifference() {
        // There is nothing to recommend, and naming a slot would be a fabricated answer.
        var scenario = scheduledOn(
                plan(new FlatRate(new BigDecimal("31.98"))), usage(), electricVehicle());

        assertThat(scenario.schedule().timingMatters()).isFalse();
    }

    /** The same car, left on charge all day, as it is for someone working from home. */
    private static SchedulableLoad electricVehicleAlwaysPluggedIn() {
        return new SchedulableLoad("Electric vehicle", new BigDecimal("11"),
                new BigDecimal("7.4"), 0, 24 * 60, 5, Set.of(), true, false);
    }

    @Test
    void solarMovesTheRecommendationFromOvernightToTheMiddleOfTheDay() {
        // The behaviour that proves the marginal profile is solar-aware. Consuming during a
        // surplus half hour forgoes 3.3c of feed-in rather than costing 4.99c of import.
        var tariff = plan(householdTariff(), new SolarFeedIn(new BigDecimal("3.30")));

        var withoutSolar = scheduledOn(tariff, usage(), electricVehicleAlwaysPluggedIn());
        var withSolar = scheduledOn(tariff, usageWithSolar(), electricVehicleAlwaysPluggedIn());

        assertThat(withoutSolar.schedule().slots())
                .allSatisfy(slot -> assertThat(slot).isLessThan(12));
        assertThat(withSolar.schedule().slots())
                .allSatisfy(slot -> assertThat(slot).isBetween(20, 29));
    }

    @Test
    void solarDoesNotMoveACarThatIsNotPluggedInAtMidday() {
        // A recommendation to charge at midday is worthless if the car is at work. The window
        // constrains the choice before the rates do, and must keep doing so.
        var tariff = plan(householdTariff(), new SolarFeedIn(new BigDecimal("3.30")));

        var scenario = scheduledOn(tariff, usageWithSolar(), electricVehicle());

        assertThat(scenario.schedule().slots())
                .allSatisfy(slot -> assertThat(slot).isLessThan(12));
    }

    // -----------------------------------------------------------------
    // The energy added is exactly what was asked for.
    // -----------------------------------------------------------------

    @Test
    void addsExactlyTheEnergyAskedForAndNoMore() {
        var scenario = scheduledOn(plan(householdTariff()), usage(), electricVehicle());
        var before = usage();
        var after = scenario.applyTo(before);

        // Five runs a week of 11 kWh across a 365-day year.
        var added = after.consumption().totalKWh().subtract(before.consumption().totalKWh());
        var expectedRuns = new BigDecimal(365 * 5 / 7);

        assertThat(added).isCloseTo(expectedRuns.multiply(new BigDecimal("11")),
                org.assertj.core.data.Offset.offset(new BigDecimal("22")));
    }

    @Test
    void theApplianceNeverLandsOutsideItsWindow() {
        var load = new SchedulableLoad("Night load", new BigDecimal("6"), new BigDecimal("3"),
                22 * 60, 4 * 60, 7, Set.of(), true, false);
        var scenario = scheduledOn(plan(householdTariff()), usage(), load);
        var before = usage();
        var after = scenario.applyTo(before);

        // Anything added must sit in 22:00-04:00, wrapping midnight.
        var beforeBySlot = totalsBySlot(before.consumption());
        var afterBySlot = totalsBySlot(after.consumption());
        for (int slot = 0; slot < 48; slot++) {
            boolean inWindow = slot >= 44 || slot < 8;
            if (!inWindow) {
                assertThat(afterBySlot[slot])
                        .as("slot %d must be untouched", slot)
                        .isEqualByComparingTo(beforeBySlot[slot]);
            }
        }
    }

    @Test
    void existingConsumptionIsUntouched() {
        var scenario = scheduledOn(plan(householdTariff()), usage(), electricVehicle());
        var before = usage();
        var after = scenario.applyTo(before);

        // Every original reading survives; the appliance only ever adds.
        assertThat(after.consumption().readings())
                .containsAll(before.consumption().readings());
        assertThat(after.export()).isEqualTo(before.export());
    }

    @Test
    void monthsAreHonoured() {
        // A pool heater running November to March contributes nothing in June, and the annual
        // figure must reflect that rather than extrapolating a summer month across the year.
        var poolHeater = new SchedulableLoad("Pool heat pump", new BigDecimal("40"),
                new BigDecimal("5"), 8 * 60, 18 * 60, 7,
                EnumSet.of(Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY,
                        Month.MARCH),
                false, false);
        var scenario = scheduledOn(plan(householdTariff()), usage(), poolHeater);
        var after = scenario.applyTo(usage());

        var june = new DateRange(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));
        var january = new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31));

        assertThat(after.consumption().slice(june).totalKWh())
                .isEqualByComparingTo(usage().consumption().slice(june).totalKWh());
        assertThat(after.consumption().slice(january).totalKWh())
                .isGreaterThan(usage().consumption().slice(january).totalKWh());
    }

    @Test
    void aRunThatCannotFitAddsNothingRatherThanAddingLess() {
        // 11 kWh at 2 kW needs five and a half hours; a four-hour window cannot deliver it.
        var tooSlow = new SchedulableLoad("Slow charger", new BigDecimal("11"),
                new BigDecimal("2"), 2 * 60, 6 * 60, 7, Set.of(), true, false);
        var scenario = scheduledOn(plan(householdTariff()), usage(), tooSlow);

        assertThat(scenario.schedule().fits()).isFalse();
        assertThat(scenario.applyTo(usage()).consumption().totalKWh())
                .isEqualByComparingTo(usage().consumption().totalKWh());
    }

    // -----------------------------------------------------------------
    // The controlled circuit.
    // -----------------------------------------------------------------

    @Test
    void anApplianceOnTheControlledCircuitAddsToThatSeriesRatherThanToConsumption() {
        var hotWater = new SchedulableLoad("Hot water", new BigDecimal("10.8"),
                new BigDecimal("3.6"), 0, 6 * 60, 7, Set.of(), false, true);
        var tariff = plan(householdTariff(),
                new ControlledLoad(new BigDecimal("12.00"), 0, 6 * 60));
        var profile = MarginalRateProfile.controlled(tariff, DaySelector.ALL);
        var scenario = new AddAppliance(hotWater, LoadScheduler.schedule(hotWater, profile));

        var after = scenario.applyTo(usage());

        assertThat(after.consumption().totalKWh())
                .isEqualByComparingTo(usage().consumption().totalKWh());
        assertThat(after.controlled().totalKWh()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void controlledEnergyIsPricedAtTheControlledRateNotTheOrdinaryOne() {
        // The reason the controlled circuit needed modelling at all: the same energy on the
        // same plan costs a different amount depending on which circuit it is on.
        var hotWater = new SchedulableLoad("Hot water", new BigDecimal("10.8"),
                new BigDecimal("3.6"), 0, 6 * 60, 7, Set.of(), false, true);
        var tariff = plan(householdTariff(),
                new ControlledLoad(new BigDecimal("3.50"), 0, 6 * 60));

        var scenario = new AddAppliance(hotWater, LoadScheduler.schedule(
                hotWater, MarginalRateProfile.controlled(tariff, DaySelector.ALL)));
        var after = scenario.applyTo(usage());

        var bill = new CostingEngine().cost(after, tariff, YEAR);
        var controlledKWh = after.controlled().slice(YEAR).totalKWh();

        // Priced at 3.50c, not at the 4.99c overnight band it happens to run inside.
        assertThat(bill.subtotal(
                        io.github.bovinemagnet.electrome.core.cost.ChargeKind.CONTROLLED))
                .isCloseTo(
                        controlledKWh.multiply(new BigDecimal("0.035")),
                        org.assertj.core.data.Offset.offset(new BigDecimal("0.02")));
    }

    @Test
    void aCheaperControlledCircuitBeatsPricingTheSameEnergyAsConsumption() {
        var hotWater = new SchedulableLoad("Hot water", new BigDecimal("10.8"),
                new BigDecimal("3.6"), 0, 6 * 60, 7, Set.of(), false, true);
        var withControlled = plan(householdTariff(),
                new ControlledLoad(new BigDecimal("3.50"), 0, 6 * 60));
        var withoutControlled = plan(householdTariff());

        var scenario = new AddAppliance(hotWater, LoadScheduler.schedule(
                hotWater, MarginalRateProfile.controlled(withControlled, DaySelector.ALL)));
        var after = scenario.applyTo(usage());

        var engine = new CostingEngine();
        var onControlledTariff = engine.cost(after, withControlled, YEAR).totalRounded()
                .subtract(engine.cost(usage(), withControlled, YEAR).totalRounded());
        var asOrdinaryUsage = engine.cost(after, withoutControlled, YEAR).totalRounded()
                .subtract(engine.cost(usage(), withoutControlled, YEAR).totalRounded());

        assertThat(onControlledTariff).isLessThan(asOrdinaryUsage);
    }

    private static BigDecimal[] totalsBySlot(UsageSeries series) {
        var totals = new BigDecimal[48];
        java.util.Arrays.fill(totals, BigDecimal.ZERO);
        for (var reading : series.readings()) {
            int slot = reading.minuteOfDay() / 30;
            totals[slot] = totals[slot].add(reading.kWh());
        }
        return totals;
    }
}
