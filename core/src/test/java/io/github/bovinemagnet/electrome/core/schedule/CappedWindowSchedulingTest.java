package io.github.bovinemagnet.electrome.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Scheduling into a window the household is already eating into.
 *
 * <p>On a capped plan the free window is not the appliance's to spend: the house, the pool and
 * everything else inside that window get there first. A scheduler blind to that would price a
 * 28 kWh car charge as free, recommend the plan on a saving that does not exist, and be wrong
 * by the most on exactly the household this tool is for.
 */
class CappedWindowSchedulingTest {

    private static final LocalDate DAY = LocalDate.of(2025, 1, 1);

    /** GloBird's shape: 50 kWh a day free inside 11:00-15:00, then 9.405c. */
    private static Plan globird() {
        List<Charge> charges = List.of(
                new DailySupply(BigDecimal.ZERO),
                new TimeOfUse(List.of(
                        Band.parseTiered("11:00", "15:00", DaySelector.ALL, ResetPeriod.DAILY,
                                List.of(new Tier(new BigDecimal("50"), BigDecimal.ZERO),
                                        new Tier(null, new BigDecimal("9.405")))),
                        Band.parseTiered("15:00", "11:00", DaySelector.ALL, ResetPeriod.DAILY,
                                List.of(new Tier(new BigDecimal("15"), new BigDecimal("31.559")),
                                        new Tier(null, new BigDecimal("33.963")))))));
        return new Plan("globird", "4 Hour Free", "GloBird", DistributionZone.AUSNET,
                charges, true, null, null);
    }

    /**
     * A day holding {@code windowKWh} inside 11:00-15:00 and a token amount everywhere else.
     */
    private static UsageData dayHolding(String windowKWh) {
        // 11:00-15:00 is eight half hours.
        var each = new BigDecimal(windowKWh).divide(new BigDecimal("8"));
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            var kWh = minute >= 660 && minute < 900 ? each : new BigDecimal("0.1");
            readings.add(new IntervalReading(DAY.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), kWh, Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    /** A 28 kWh charge at 7 kW, allowed to run only inside the free window. */
    private static SchedulableLoad carCharge() {
        return new SchedulableLoad("Car", new BigDecimal("28"), new BigDecimal("7"),
                11 * 60, 15 * 60, 7, Set.of(), false, false);
    }

    @Test
    void chargesTheBalanceRateOnceTheHouseholdHasSpentMostOfTheCap() {
        // 30 kWh of the 50 kWh cap is already gone, so only 20 kWh of the charge is free.
        var profile = MarginalRateProfile.of(globird(), dayHolding("30"), DaySelector.ALL);
        var schedule = LoadScheduler.schedule(carCharge(), profile);

        assertThat(profile.capAt(22).headroomKWh()).isEqualByComparingTo("20");
        assertThat(schedule.fits()).isTrue();
        // 20 kWh at nothing and 8 kWh at 9.405c.
        assertThat(schedule.marginalCostCents()).isEqualByComparingTo("75.24");
    }

    @Test
    void chargesNothingWhenTheWholeSessionFitsUnderTheCap() {
        var profile = MarginalRateProfile.of(globird(), dayHolding("5"), DaySelector.ALL);
        var schedule = LoadScheduler.schedule(carCharge(), profile);

        assertThat(profile.capAt(22).headroomKWh()).isEqualByComparingTo("45");
        assertThat(schedule.marginalCostCents()).isEqualByComparingTo("0");
    }

    @Test
    void aWindowTheHouseholdHasAlreadyExhaustedOffersNoFreeEnergy() {
        var profile = MarginalRateProfile.of(globird(), dayHolding("60"), DaySelector.ALL);
        var schedule = LoadScheduler.schedule(carCharge(), profile);

        assertThat(profile.capAt(22).headroomKWh()).isEqualByComparingTo("0");
        // The whole 28 kWh at the balance rate.
        assertThat(schedule.marginalCostCents()).isEqualByComparingTo("263.34");
    }

    /** Each band's allowance is its own: slots name the cap they draw on. */
    @Test
    void slotsNameWhichCapTheyDrawOn() {
        var profile = MarginalRateProfile.of(globird(), dayHolding("5"), DaySelector.ALL);
        assertThat(profile.capAt(22).band()).isEqualTo("11:00-15:00");
        assertThat(profile.capAt(30).band()).isEqualTo("15:00-11:00");
    }

    @Test
    void anUncappedPlanCarriesNoCapAtAll() {
        var flat = new Plan("flat", "Flat", "Test", DistributionZone.AUSNET,
                List.of(new DailySupply(BigDecimal.ZERO),
                        new TimeOfUse(List.of(
                                Band.parse("00:00", "24:00", DaySelector.ALL,
                                        new BigDecimal("30"))))),
                true, null, null);
        var profile = MarginalRateProfile.of(flat, dayHolding("5"), DaySelector.ALL);
        assertThat(profile.caps()).allSatisfy(cap -> assertThat(cap).isNull());
    }
}
