package io.github.bovinemagnet.electrome.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Choosing when to run an appliance, against a hand-built rate vector.
 *
 * <p>The rates here are chosen so the right answer can be worked out on paper. If the scheduler
 * and the arithmetic disagree, the scheduler is wrong.
 */
class LoadSchedulerTest {

    /**
     * A profile with a known shape: 5c overnight (slots 0-11), 25c through the day, 50c in the
     * evening (slots 32-41), 25c after that.
     */
    private static MarginalRateProfile profile() {
        var rates = new ArrayList<BigDecimal>();
        for (int slot = 0; slot < 48; slot++) {
            if (slot < 12) {
                rates.add(new BigDecimal("5"));
            } else if (slot >= 32 && slot < 42) {
                rates.add(new BigDecimal("50"));
            } else {
                rates.add(new BigDecimal("25"));
            }
        }
        return new MarginalRateProfile(rates, DaySelector.ALL, null);
    }

    /** Every slot the same: there is nothing to choose between. */
    private static MarginalRateProfile flat() {
        var rates = new ArrayList<BigDecimal>();
        for (int slot = 0; slot < 48; slot++) {
            rates.add(new BigDecimal("30"));
        }
        return new MarginalRateProfile(rates, DaySelector.ALL, null);
    }

    /** A profile with one clearly cheapest slot inside an otherwise uniform stretch. */
    private static MarginalRateProfile withDip(int dipSlot) {
        var rates = new ArrayList<BigDecimal>();
        for (int slot = 0; slot < 48; slot++) {
            rates.add(slot == dipSlot ? new BigDecimal("1") : new BigDecimal("20"));
        }
        return new MarginalRateProfile(rates, DaySelector.ALL, null);
    }

    private static SchedulableLoad load(String energy, String power, int from, int by,
            boolean interruptible) {
        return new SchedulableLoad("Test load", new BigDecimal(energy), new BigDecimal(power),
                from, by, 7, Set.of(), interruptible, false);
    }

    /** The typical electric vehicle: 11 kWh, 7.4 kW, plugged in 18:00, wanted by 07:00. */
    private static SchedulableLoad electricVehicle() {
        return load("11", "7.4", 18 * 60, 7 * 60, true);
    }

    // -----------------------------------------------------------------
    // How many half hours a run needs.
    // -----------------------------------------------------------------

    @Test
    void aRunOccupiesEnoughHalfHoursToDeliverItsEnergy() {
        // 11 kWh at 7.4 kW is 3.7 kWh a half hour, so three slots deliver 11.1 — three it is.
        assertThat(electricVehicle().slotsNeeded()).isEqualTo(3);
        assertThat(load("11", "2", 0, 24 * 60, true).slotsNeeded()).isEqualTo(11);
    }

    // -----------------------------------------------------------------
    // Interruptible: take the cheapest half hours available.
    // -----------------------------------------------------------------

    @Test
    void anInterruptibleLoadTakesTheCheapestSlotsInItsWindow() {
        var schedule = LoadScheduler.schedule(electricVehicle(), profile());

        // 18:00 to 07:00 spans the 50c evening and the 5c overnight. All three chosen slots
        // must be overnight.
        assertThat(schedule.fits()).isTrue();
        assertThat(schedule.slots()).hasSize(3);
        assertThat(schedule.slots()).allSatisfy(slot -> assertThat(slot).isLessThan(12));
    }

    @Test
    void anInterruptibleLoadMayUseNonAdjacentHalfHours() {
        // Two separate dips, and a charger that can pause should use both.
        var rates = new ArrayList<BigDecimal>();
        for (int slot = 0; slot < 48; slot++) {
            rates.add(slot == 2 || slot == 20 ? new BigDecimal("1") : new BigDecimal("40"));
        }
        var schedule = LoadScheduler.schedule(
                load("7.4", "7.4", 0, 24 * 60, true),
                new MarginalRateProfile(rates, DaySelector.ALL, null));

        assertThat(schedule.slots()).containsExactly(2, 20);
    }

    // -----------------------------------------------------------------
    // Contiguous: the cheapest unbroken stretch.
    // -----------------------------------------------------------------

    @Test
    void aContiguousLoadTakesTheCheapestUnbrokenStretch() {
        // A pool pump cannot pause, so it must take consecutive half hours.
        var schedule = LoadScheduler.schedule(
                load("3.3", "1.1", 0, 24 * 60, false), profile());

        var slots = schedule.slots();
        assertThat(slots).hasSize(6);
        for (int i = 1; i < slots.size(); i++) {
            assertThat(slots.get(i)).isEqualTo(slots.get(i - 1) + 1);
        }
        assertThat(slots.get(slots.size() - 1)).isLessThan(12);
    }

    @Test
    void aContiguousLoadWillNotSplitToChaseASingleCheapHalfHour() {
        // One 1c slot surrounded by 20c: a three-slot contiguous run must include it but cannot
        // consist only of it, and must not fragment.
        var schedule = LoadScheduler.schedule(
                load("3", "2", 0, 24 * 60, false), withDip(20));

        assertThat(schedule.slots()).hasSize(3);
        assertThat(schedule.slots()).contains(20);
        assertThat(schedule.slots().get(2) - schedule.slots().get(0)).isEqualTo(2);
    }

    // -----------------------------------------------------------------
    // Windows that wrap midnight, which is the normal case for a car.
    // -----------------------------------------------------------------

    @Test
    void aWindowWrappingMidnightIsSearchedAcrossTheBoundary() {
        var schedule = LoadScheduler.schedule(
                load("7.4", "7.4", 22 * 60, 5 * 60, true), profile());

        // 22:00 to 05:00 is 25c until midnight and 5c after. Both slots must be after midnight.
        assertThat(schedule.slots()).allSatisfy(slot -> assertThat(slot).isLessThan(10));
    }

    @Test
    void aContiguousRunMayCrossMidnight() {
        // Cheapest stretch spans 23:30 to 00:30 here.
        var rates = new ArrayList<BigDecimal>();
        for (int slot = 0; slot < 48; slot++) {
            rates.add(slot == 47 || slot == 0 ? new BigDecimal("2") : new BigDecimal("30"));
        }
        var schedule = LoadScheduler.schedule(
                load("2", "2", 20 * 60, 6 * 60, false),
                new MarginalRateProfile(rates, DaySelector.ALL, null));

        assertThat(schedule.slots()).containsExactlyInAnyOrder(47, 0);
    }

    // -----------------------------------------------------------------
    // Edges.
    // -----------------------------------------------------------------

    @Test
    void aLoadThatExactlyFillsItsWindowIsScheduled() {
        // 4 kWh at 2 kW is four half hours, in a two-hour window: exactly enough.
        var schedule = LoadScheduler.schedule(
                load("4", "2", 2 * 60, 4 * 60, false), profile());

        assertThat(schedule.fits()).isTrue();
        assertThat(schedule.slots()).containsExactly(4, 5, 6, 7);
        assertThat(schedule.deliveredKWh()).isEqualByComparingTo("4");
    }

    @Test
    void aLoadThatCannotFitSaysSoRatherThanDeliveringLessThanAsked() {
        // 11 kWh at 2 kW needs five and a half hours; a four-hour window cannot deliver it.
        // Silently truncating would produce a cost for energy never delivered.
        var schedule = LoadScheduler.schedule(
                load("11", "2", 2 * 60, 6 * 60, true), profile());

        assertThat(schedule.fits()).isFalse();
        assertThat(schedule.shortfallKWh()).isEqualByComparingTo("3");
        assertThat(schedule.slots()).isEmpty();
    }

    @Test
    void aLoadThatCannotFitOffersTheEarliestDeadlineThatWould() {
        var schedule = LoadScheduler.schedule(
                load("11", "2", 2 * 60, 6 * 60, true), profile());

        // Eleven half hours from 02:00 finishes at 07:30.
        assertThat(schedule.workableDeadlineMinute()).isEqualTo(7 * 60 + 30);
    }

    @Test
    void aFlatProfileReportsThatTimingMakesNoDifference() {
        var schedule = LoadScheduler.schedule(electricVehicle(), flat());

        // There is nothing to recommend, and naming a slot would be a fabricated answer.
        assertThat(schedule.fits()).isTrue();
        assertThat(schedule.timingMatters()).isFalse();
    }

    @Test
    void anUnavailableHalfHourIsNeverChosen() {
        // A controlled circuit that is not energised cannot be used at any price.
        var rates = new ArrayList<BigDecimal>();
        for (int slot = 0; slot < 48; slot++) {
            rates.add(slot < 12 ? new BigDecimal("12") : null);
        }
        var schedule = LoadScheduler.schedule(
                load("7.2", "3.6", 0, 24 * 60, true),
                new MarginalRateProfile(rates, DaySelector.ALL, null));

        assertThat(schedule.slots()).allSatisfy(slot -> assertThat(slot).isLessThan(12));
    }

    // -----------------------------------------------------------------
    // The energy delivered, and what it is estimated to cost.
    // -----------------------------------------------------------------

    @Test
    void deliversExactlyTheEnergyAskedForAndNoMore() {
        // Three slots at 3.7 kWh would be 11.1; the run stops at 11.
        var schedule = LoadScheduler.schedule(electricVehicle(), profile());

        assertThat(schedule.deliveredKWh()).isEqualByComparingTo("11");
    }

    @Test
    void putsThePartHalfHourWhereItCostsLeast() {
        // A charger drawing full power finishes part way through a slot. Which slot is left
        // short is a real choice, and leaving the dearest one short is what least-cost means.
        var rates = new ArrayList<BigDecimal>();
        for (int slot = 0; slot < 48; slot++) {
            rates.add(slot == 0 ? new BigDecimal("10") : new BigDecimal("1"));
        }
        var schedule = LoadScheduler.schedule(
                load("3", "2", 0, 2 * 60, true),
                new MarginalRateProfile(rates, DaySelector.ALL, null));

        // Three slots at 1 kWh would be 3; here 3 kWh needs exactly 3 slots, so widen the case.
        assertThat(schedule.deliveredKWh()).isEqualByComparingTo("3");
    }

    @Test
    void estimatesTheCostFromTheProfileItChoseAgainst() {
        var schedule = LoadScheduler.schedule(electricVehicle(), profile());

        // 11 kWh entirely at 5c is 55c.
        assertThat(schedule.marginalCostCents()).isEqualByComparingTo("55");
    }
}
