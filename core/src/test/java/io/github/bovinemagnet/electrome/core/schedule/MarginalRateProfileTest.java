package io.github.bovinemagnet.electrome.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What one more kilowatt hour costs, half hour by half hour.
 *
 * <p>This is what makes scheduling affordable: the best time to run an appliance is a search
 * over 48 numbers rather than 48 re-costings of the whole year.
 */
class MarginalRateProfileTest {

    private static final LocalDate DAY = LocalDate.of(2025, 1, 1);

    private static Plan plan(Charge... charges) {
        var all = new ArrayList<Charge>();
        all.add(new DailySupply(new BigDecimal("100.00")));
        all.addAll(List.of(charges));
        return new Plan("p", "Plan", "Retailer", DistributionZone.AUSNET, all, true, null, null);
    }

    /** The household's real shape: 4.99c overnight, 49.54c in the evening. */
    private static TimeOfUse householdTariff() {
        return new TimeOfUse(List.of(
                new Band(0, 6 * 60, DaySelector.ALL, new BigDecimal("4.99")),
                new Band(6 * 60, 16 * 60, DaySelector.ALL, new BigDecimal("24.77")),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("49.54")),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal("24.77"))));
    }

    private static UsageData noSolar() {
        var readings = new ArrayList<IntervalReading>();
        for (int slot = 0; slot < 48; slot++) {
            readings.add(new IntervalReading(
                    LocalDateTime.of(DAY, java.time.LocalTime.MIDNIGHT).plusMinutes(slot * 30L),
                    Duration.ofMinutes(30), new BigDecimal("0.4"), Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    /** Exporting between 10:00 and 15:00, as a solar household does. */
    private static UsageData withMiddayExport() {
        var consumption = new ArrayList<IntervalReading>();
        var export = new ArrayList<IntervalReading>();
        for (int slot = 0; slot < 48; slot++) {
            var start = LocalDateTime.of(DAY, java.time.LocalTime.MIDNIGHT)
                    .plusMinutes(slot * 30L);
            consumption.add(new IntervalReading(
                    start, Duration.ofMinutes(30), new BigDecimal("0.4"), Quality.ACTUAL));
            if (slot >= 20 && slot < 30) {
                export.add(new IntervalReading(
                        start, Duration.ofMinutes(30), new BigDecimal("1.2"), Quality.ACTUAL));
            }
        }
        return new UsageData(UsageSeries.of(consumption), UsageSeries.of(export));
    }

    // -----------------------------------------------------------------

    @Test
    void aTimeOfUsePlanYieldsItsBandRates() {
        var profile = MarginalRateProfile.of(plan(householdTariff()), noSolar(), DaySelector.ALL);

        assertThat(profile.rates()).hasSize(48);
        assertThat(profile.rateAt(0)).isEqualByComparingTo("4.99");     // 00:00
        assertThat(profile.rateAt(11)).isEqualByComparingTo("4.99");    // 05:30
        assertThat(profile.rateAt(12)).isEqualByComparingTo("24.77");   // 06:00
        assertThat(profile.rateAt(33)).isEqualByComparingTo("49.54");   // 16:30
        assertThat(profile.rateAt(43)).isEqualByComparingTo("24.77");   // 21:30
    }

    @Test
    void aFlatPlanYieldsAConstant() {
        var profile = MarginalRateProfile.of(
                plan(new FlatRate(new BigDecimal("31.98"))), noSolar(), DaySelector.ALL);

        assertThat(profile.rates()).allSatisfy(
                rate -> assertThat(rate).isEqualByComparingTo("31.98"));
    }

    @Test
    void aFlatPlanKnowsThatTimingMakesNoDifference() {
        // There is no recommendation to make, and reporting an arbitrary slot as optimal would
        // be a fabricated answer.
        var flat = MarginalRateProfile.of(
                plan(new FlatRate(new BigDecimal("31.98"))), noSolar(), DaySelector.ALL);
        var tou = MarginalRateProfile.of(plan(householdTariff()), noSolar(), DaySelector.ALL);

        assertThat(flat.timingMatters()).isFalse();
        assertThat(tou.timingMatters()).isTrue();
    }

    @Test
    void reportsTheSpreadBetweenTheCheapestAndDearestHalfHour() {
        var profile = MarginalRateProfile.of(plan(householdTariff()), noSolar(), DaySelector.ALL);

        // 49.54 - 4.99. If the scheduler cannot find this, it is not working.
        assertThat(profile.spread()).isEqualByComparingTo("44.55");
    }

    // -----------------------------------------------------------------
    // Solar changes the answer, and must.
    // -----------------------------------------------------------------

    @Test
    void aSlotWithExportCostsTheForgoneFeedInRatherThanTheImportRate() {
        // Consuming during a surplus half hour does not buy electricity; it stops you selling
        // it. A few cents against forty is the whole reason "charge at midday" is right for a
        // solar household and wrong without one.
        var profile = MarginalRateProfile.of(
                plan(householdTariff(), new SolarFeedIn(new BigDecimal("3.30"))),
                withMiddayExport(), DaySelector.ALL);

        assertThat(profile.rateAt(24)).isEqualByComparingTo("3.30");   // 12:00, exporting
        assertThat(profile.rateAt(0)).isEqualByComparingTo("4.99");    // 00:00, importing
    }

    @Test
    void solarMakesTheMiddayWindowTheCheapestTimeToRunSomething() {
        var withoutSolar = MarginalRateProfile.of(
                plan(householdTariff(), new SolarFeedIn(new BigDecimal("3.30"))),
                noSolar(), DaySelector.ALL);
        var withSolar = MarginalRateProfile.of(
                plan(householdTariff(), new SolarFeedIn(new BigDecimal("3.30"))),
                withMiddayExport(), DaySelector.ALL);

        assertThat(withoutSolar.cheapestSlot()).isLessThan(12);        // overnight
        assertThat(withSolar.cheapestSlot()).isBetween(20, 29);        // the export window
    }

    @Test
    void aPlanWithNoFeedInMakesSurplusHalfHoursFree() {
        // Nothing is forgone by consuming energy that would otherwise be exported for nothing.
        var profile = MarginalRateProfile.of(
                plan(householdTariff()), withMiddayExport(), DaySelector.ALL);

        assertThat(profile.rateAt(24)).isEqualByComparingTo("0");
    }

    // -----------------------------------------------------------------
    // Where the marginal rate is not the true marginal cost.
    // -----------------------------------------------------------------

    @Test
    void aTimeOfUseOrFlatPlanIsExact() {
        assertThat(MarginalRateProfile.of(plan(householdTariff()), noSolar(), DaySelector.ALL)
                        .exact()).isTrue();
        assertThat(MarginalRateProfile.of(plan(new FlatRate(new BigDecimal("30"))), noSolar(),
                        DaySelector.ALL).exact()).isTrue();
    }

    @Test
    void aBlockTariffIsNotExactAndSaysSo() {
        // The marginal rate depends on how much has already been used in the reset period, so
        // the chosen slot may be marginally off. The reported cost is a full costing either way.
        var profile = MarginalRateProfile.of(
                plan(new Tiered(ResetPeriod.QUARTERLY, List.of(
                        new Tier(new BigDecimal("500"), new BigDecimal("22.00")),
                        new Tier(null, new BigDecimal("35.00"))))),
                noSolar(), DaySelector.ALL);

        assertThat(profile.exact()).isFalse();
        assertThat(profile.inexactBecause()).containsIgnoringCase("block");
    }

    @Test
    void aDemandChargeIsNotExactAndSaysSo() {
        var profile = MarginalRateProfile.of(
                plan(new FlatRate(new BigDecimal("25.00")),
                        new io.github.bovinemagnet.electrome.core.tariff.Demand(
                                16 * 60, 21 * 60, DaySelector.ALL, ResetPeriod.MONTHLY,
                                new BigDecimal("30.00"))),
                noSolar(), DaySelector.ALL);

        assertThat(profile.exact()).isFalse();
        assertThat(profile.inexactBecause()).containsIgnoringCase("demand");
    }

    /**
     * A capped window prices its first block, and says that it has.
     *
     * <p>A static 48-slot profile cannot express a rate that changes once the day's cap is
     * spent, so the honest thing is to report the cheap rate the scheduler will chase and to
     * name the cap that limits it.
     */
    @Test
    void aCappedBandReportsItsFirstBlockAndSaysSo() {
        var plan = plan(new TimeOfUse(List.of(
                Band.parseTiered("11:00", "15:00", DaySelector.ALL, ResetPeriod.DAILY, List.of(
                        new Tier(new BigDecimal("50"), BigDecimal.ZERO),
                        new Tier(null, new BigDecimal("9.405")))),
                Band.parse("15:00", "11:00", DaySelector.ALL, new BigDecimal("31.559")))));

        var profile = MarginalRateProfile.of(plan, noSolar(), DaySelector.ALL);

        assertThat(profile.rateAt(22)).isEqualByComparingTo("0");
        assertThat(profile.rateAt(29)).isEqualByComparingTo("0");
        assertThat(profile.rateAt(30)).isEqualByComparingTo("31.559");
        assertThat(profile.exact()).isFalse();
        assertThat(profile.inexactBecause())
                .isEqualTo("11:00-15:00 is capped at 50 kWh/day; beyond it a unit costs 9.405c."
                        + " The cost above deducts what your own household typically uses in "
                        + "that window, but a heavier day than usual, or a second appliance "
                        + "scheduled separately, will spend the cap sooner and cost more.");
    }

    @Test
    void weekdayAndWeekendRatesAreReadSeparately() {
        var weekdayDear = plan(new TimeOfUse(List.of(
                new Band(0, 24 * 60, DaySelector.WEEKDAYS, new BigDecimal("40.00")),
                new Band(0, 24 * 60, DaySelector.WEEKENDS, new BigDecimal("15.00")))));

        assertThat(MarginalRateProfile.of(weekdayDear, noSolar(), DaySelector.WEEKDAYS).rateAt(0))
                .isEqualByComparingTo("40.00");
        assertThat(MarginalRateProfile.of(weekdayDear, noSolar(), DaySelector.WEEKENDS).rateAt(0))
                .isEqualByComparingTo("15.00");
    }

    @Test
    void aControlledCircuitIsPricedAtItsOwnRate() {
        // Hot water on the controlled circuit is not scheduled against the ordinary bands.
        var withControlled = plan(householdTariff(),
                new io.github.bovinemagnet.electrome.core.tariff.ControlledLoad(
                        new BigDecimal("12.00"), 0, 6 * 60));

        var profile = MarginalRateProfile.controlled(withControlled, DaySelector.ALL);

        assertThat(profile.rateAt(0)).isEqualByComparingTo("12.00");
        // Outside the energised window the circuit simply is not available.
        assertThat(profile.rateAt(24)).isNull();
    }
}
