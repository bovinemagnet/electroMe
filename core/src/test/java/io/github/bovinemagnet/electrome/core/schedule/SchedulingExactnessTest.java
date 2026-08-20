package io.github.bovinemagnet.electrome.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.scenario.AddAppliance;
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
 * The marginal-rate shortcut against the thing it is a shortcut for.
 *
 * <p>Scheduling by marginal rate turns hundreds of millions of interval operations into a scan
 * over 48 numbers. That is only legitimate if it picks the same slot an exhaustive search with
 * full re-costing would pick — which is what this checks, on the tariff kinds where the shortcut
 * claims to be exact.
 */
class SchedulingExactnessTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2025, 3, 31);
    private static final DateRange RANGE = new DateRange(START, END);
    private static final CostingEngine ENGINE = new CostingEngine();

    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        for (var day = START; !day.isAfter(END); day = day.plusDays(1)) {
            for (int slot = 0; slot < 48; slot++) {
                int minute = slot * 30;
                boolean evening = minute >= 16 * 60 && minute < 21 * 60;
                readings.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT).plusMinutes(minute),
                        Duration.ofMinutes(30),
                        evening ? new BigDecimal("0.9") : new BigDecimal("0.3"),
                        Quality.ACTUAL));
            }
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(Charge... charges) {
        var all = new ArrayList<Charge>();
        all.add(new DailySupply(new BigDecimal("123.20")));
        all.addAll(List.of(charges));
        return new Plan("p", "Plan", "Retailer", DistributionZone.AUSNET, all, true, null, null);
    }

    private static TimeOfUse fourBandTariff() {
        return new TimeOfUse(List.of(
                new Band(0, 6 * 60, DaySelector.ALL, new BigDecimal("4.99")),
                new Band(6 * 60, 16 * 60, DaySelector.ALL, new BigDecimal("24.77")),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("49.54")),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal("24.77"))));
    }

    /** A contiguous load, so "which start time" is a single well-defined choice. */
    private static SchedulableLoad contiguousLoad() {
        return new SchedulableLoad("Test load", new BigDecimal("6"), new BigDecimal("3"),
                0, 24 * 60, 7, Set.of(), false, false);
    }

    /**
     * The cost of running the load starting at each of the 48 half hours, by full re-costing.
     *
     * <p>This is the expensive thing the shortcut exists to avoid, run here once so the cheap
     * thing can be checked against it.
     */
    private static int exhaustivelyCheapestStart(Plan plan, SchedulableLoad load) {
        var baseline = usage();
        BigDecimal best = null;
        int bestStart = -1;

        for (int start = 0; start < 48; start++) {
            var withLoad = addStartingAt(baseline, load, start);
            var total = ENGINE.cost(withLoad, plan, RANGE).total();
            if (best == null || total.compareTo(best) < 0) {
                best = total;
                bestStart = start;
            }
        }
        return bestStart;
    }

    /** The same load laid down starting at a given half hour, wrapping midnight. */
    private static UsageData addStartingAt(UsageData source, SchedulableLoad load, int start) {
        var readings = new ArrayList<>(source.consumption().readings());
        var perSlot = load.kWhPerSlot();
        int needed = load.slotsNeeded();

        for (var day : source.consumption().billingDays()) {
            var remaining = load.energyPerRunKWh();
            for (int offset = 0; offset < needed; offset++) {
                int slot = (start + offset) % 48;
                var delivered = remaining.min(perSlot);
                if (delivered.signum() <= 0) {
                    break;
                }
                readings.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT)
                                .plusMinutes(slot * 30L),
                        Duration.ofMinutes(30), delivered, Quality.ESTIMATED));
                remaining = remaining.subtract(delivered);
            }
        }
        return new UsageData(UsageSeries.of(readings), source.export(), source.controlled());
    }

    // -----------------------------------------------------------------

    @Test
    void onATimeOfUsePlanTheShortcutPicksWhatAnExhaustiveSearchWouldPick() {
        var tariff = plan(fourBandTariff());
        var load = contiguousLoad();

        var chosen = LoadScheduler.schedule(
                load, MarginalRateProfile.of(tariff, usage(), DaySelector.ALL));
        int exhaustive = exhaustivelyCheapestStart(tariff, load);

        assertThat(chosen.slots().get(0))
                .as("marginal-rate choice versus exhaustive re-costing")
                .isEqualTo(exhaustive);
    }

    @Test
    void onAFlatPlanEveryStartCostsTheSameAndTheShortcutSaysSo() {
        var tariff = plan(new FlatRate(new BigDecimal("31.98")));
        var load = contiguousLoad();

        var baseline = usage();
        var first = ENGINE.cost(addStartingAt(baseline, load, 0), tariff, RANGE).total();
        var later = ENGINE.cost(addStartingAt(baseline, load, 25), tariff, RANGE).total();

        assertThat(first).isEqualByComparingTo(later);
        assertThat(MarginalRateProfile.of(tariff, baseline, DaySelector.ALL).timingMatters())
                .isFalse();
    }

    @Test
    void theShortcutIsCheckedAgainstAWindowThatWrapsMidnight() {
        var tariff = plan(fourBandTariff());
        // Available 20:00, wanted by 08:00: the window spans the dear evening and the cheap
        // overnight, and the cheapest stretch lies across the boundary.
        var load = new SchedulableLoad("Overnight load", new BigDecimal("6"),
                new BigDecimal("3"), 20 * 60, 8 * 60, 7, Set.of(), false, false);

        var chosen = LoadScheduler.schedule(
                load, MarginalRateProfile.of(tariff, usage(), DaySelector.ALL));

        // Cost the chosen start against every other start the window allows.
        var chosenCost = ENGINE.cost(
                addStartingAt(usage(), load, chosen.slots().get(0)), tariff, RANGE).total();
        for (int start = 40; start < 48 + 12; start++) {
            int slot = start % 48;
            if (slot + load.slotsNeeded() > 48 && slot < 40) {
                continue;
            }
            var alternative = ENGINE.cost(
                    addStartingAt(usage(), load, slot), tariff, RANGE).total();
            assertThat(chosenCost)
                    .as("chosen start beats or matches starting at slot %d", slot)
                    .isLessThanOrEqualTo(alternative);
        }
    }

    @Test
    void theReportedCostComesFromAFullCostingNotFromTheMarginalEstimate() {
        // The discipline the design sets: choose by marginal rate, report by full costing. So
        // the reported figure is exact even where the chosen slot might not be optimal.
        var tariff = plan(fourBandTariff());
        var load = contiguousLoad();
        var schedule = LoadScheduler.schedule(
                load, MarginalRateProfile.of(tariff, usage(), DaySelector.ALL));

        var scenario = new AddAppliance(load, schedule);
        var before = ENGINE.cost(usage(), tariff, RANGE).total();
        var after = ENGINE.cost(scenario.applyTo(usage()), tariff, RANGE).total();

        var fullCosting = after.subtract(before);
        var days = new BigDecimal(usage().consumption().billingDays().size());
        var marginalEstimate = schedule.marginalCostCents()
                .multiply(days)
                .divide(new BigDecimal("100"), java.math.MathContext.DECIMAL64);

        // On an exact tariff the two agree, which is what makes the shortcut safe to use.
        assertThat(fullCosting).isCloseTo(marginalEstimate,
                org.assertj.core.data.Offset.offset(new BigDecimal("0.05")));
    }
}
