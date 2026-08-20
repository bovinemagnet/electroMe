package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.app.PlanMatrix.Component;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.DiscountScope;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a plan charges, as published.
 *
 * <p>Read from the tariff rather than from a bill, because a bill only knows what this
 * household happened to pay. Dividing a capped window's cost by its energy gives a blended
 * figure the retailer never printed and the reader cannot check against the fact sheet.
 */
class PlanRatesTest {

    private static Plan plan(String id, Charge... charges) {
        return new Plan(id, id, "Retailer", DistributionZone.AUSNET, List.of(charges),
                true, null, null);
    }

    @Test
    void readsAFlatTariff() {
        var rates = PlanRates.of(plan("flat",
                new DailySupply(new BigDecimal("128.24")),
                new FlatRate(new BigDecimal("31.98"))));

        assertThat(rates.rate(Component.DAILY_SUPPLY)).isEqualByComparingTo("128.24");
        assertThat(rates.rate(Component.FLAT)).isEqualByComparingTo("31.98");
        assertThat(rates.has(Component.PEAK)).isFalse();
        assertThat(rates.rate(Component.PEAK)).isNull();
    }

    @Test
    void placesEachTimeOfUseBandInItsPartOfTheDay() {
        var rates = PlanRates.of(plan("tou",
                new DailySupply(new BigDecimal("120")),
                new TimeOfUse(List.of(
                        Band.parse("00:00", "09:00", DaySelector.ALL, new BigDecimal("18")),
                        Band.parse("09:00", "16:00", DaySelector.ALL, new BigDecimal("22")),
                        Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("48")),
                        Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("25"))))));

        assertThat(rates.rate(Component.OFFPEAK)).isEqualByComparingTo("18");
        assertThat(rates.rate(Component.MIDDAY)).isEqualByComparingTo("22");
        assertThat(rates.rate(Component.PEAK)).isEqualByComparingTo("48");
        assertThat(rates.rate(Component.SHOULDER)).isEqualByComparingTo("25");
    }

    /**
     * A capped window reports the rate it advertises, and says the rest separately.
     *
     * <p>Averaging the two would produce a number that is neither, and that moves with the
     * household's consumption rather than with the tariff.
     */
    @Test
    void reportsACappedWindowsHeadlineRateAndWhatFollowsIt() {
        var rates = PlanRates.of(plan("capped",
                new DailySupply(new BigDecimal("134.20")),
                new TimeOfUse(List.of(
                        Band.parseTiered("11:00", "15:00", DaySelector.ALL, ResetPeriod.DAILY,
                                List.of(new Tier(new BigDecimal("50"), BigDecimal.ZERO),
                                        new Tier(null, new BigDecimal("9.9")))),
                        Band.parse("15:00", "11:00", DaySelector.ALL, new BigDecimal("33.22"))))));

        assertThat(rates.rate(Component.MIDDAY)).isEqualByComparingTo("0");
        assertThat(rates.capped(Component.MIDDAY)).isTrue();
        assertThat(rates.beyond(Component.MIDDAY)).isEqualByComparingTo("9.9");
        assertThat(rates.capped(Component.PEAK)).isFalse();
        assertThat(rates.beyond(Component.PEAK)).isNull();
    }

    /** Where two bands share a part of the day, the cheaper is the one a reader is after. */
    @Test
    void reportsTheCheaperOfTwoBandsSharingAComponent() {
        var rates = PlanRates.of(plan("two-shoulders",
                new DailySupply(new BigDecimal("120")),
                new TimeOfUse(List.of(
                        Band.parse("00:00", "06:00", DaySelector.ALL, new BigDecimal("15")),
                        Band.parse("06:00", "16:00", DaySelector.ALL, new BigDecimal("30")),
                        Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("50")),
                        Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("25"))))));

        // 06:00-16:00 is not wholly inside the middle of the day, so both it and 21:00-24:00
        // are shoulder rates; 25c is the cheaper.
        assertThat(rates.rate(Component.SHOULDER)).isEqualByComparingTo("25");
    }

    @Test
    void readsBlockDemandControlledAndFeedIn() {
        var rates = PlanRates.of(plan("everything",
                new DailySupply(new BigDecimal("110")),
                new Tiered(ResetPeriod.QUARTERLY, List.of(
                        new Tier(new BigDecimal("1020"), new BigDecimal("31.98")),
                        new Tier(null, new BigDecimal("29.50")))),
                new Demand(960, 1260, DaySelector.ALL, ResetPeriod.MONTHLY, new BigDecimal("42")),
                new ControlledLoad(new BigDecimal("22.05"), 0, 1440),
                new SolarFeedIn(new BigDecimal("3.3")),
                new Discount("Pay on time", DiscountBasis.PERCENTAGE, DiscountScope.TOTAL,
                        new BigDecimal("5"), "pay on time")));

        assertThat(rates.rate(Component.BLOCK)).isEqualByComparingTo("31.98");
        assertThat(rates.beyond(Component.BLOCK)).isEqualByComparingTo("29.50");
        assertThat(rates.rate(Component.DEMAND)).isEqualByComparingTo("42");
        assertThat(rates.rate(Component.CONTROLLED)).isEqualByComparingTo("22.05");
        assertThat(rates.rate(Component.FEED_IN)).isEqualByComparingTo("3.3");
        assertThat(rates.rate(Component.DISCOUNT)).isEqualByComparingTo("5");
    }

    /** Columns are the union across the plans shown, in the vocabulary's own order. */
    @Test
    void offersAColumnForEveryComponentAnyPlanCharges() {
        var flat = PlanRates.of(plan("flat",
                new DailySupply(new BigDecimal("120")), new FlatRate(new BigDecimal("30"))));
        var tou = PlanRates.of(plan("tou",
                new DailySupply(new BigDecimal("120")),
                new TimeOfUse(List.of(
                        Band.parse("00:00", "16:00", DaySelector.ALL, new BigDecimal("18")),
                        Band.parse("16:00", "24:00", DaySelector.ALL, new BigDecimal("48"))))));

        assertThat(PlanRates.columnsFor(List.of(flat, tou))).containsExactly(
                Component.DAILY_SUPPLY, Component.OFFPEAK, Component.PEAK, Component.FLAT);
    }
}
