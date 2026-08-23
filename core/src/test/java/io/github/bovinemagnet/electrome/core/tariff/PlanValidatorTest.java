package io.github.bovinemagnet.electrome.core.tariff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanValidatorTest {

    private static Plan planOf(Charge... charges) {
        return new Plan("test", "Test", "Retailer", DistributionZone.AUSNET,
                List.of(charges), true, null, null);
    }

    private static Band band(String from, String to, String cents) {
        return Band.parse(from, to, DaySelector.ALL, new BigDecimal(cents));
    }

    @Test
    void acceptsTheReferenceTariff() {
        var plan = planOf(
                new DailySupply(new BigDecimal("123.20")),
                new TimeOfUse(List.of(
                        band("00:00", "06:00", "4.99"),
                        band("06:00", "11:00", "24.77"),
                        band("11:00", "16:00", "24.77"),
                        band("16:00", "21:00", "49.54"),
                        band("21:00", "24:00", "24.77"))));
        assertThatCode(() -> PlanValidator.validate(plan)).doesNotThrowAnyException();
    }

    /**
     * Capping a band changes what it charges, not when it applies.
     *
     * <p>The tiling check has to keep working over capped bands, because a plan whose free
     * window leaves part of the day unpriced would silently under-cost a household.
     */
    @Test
    void acceptsCappedBandsThatStillTileTheDay() {
        var plan = planOf(
                new DailySupply(new BigDecimal("127.49")),
                new TimeOfUse(List.of(
                        Band.parseTiered("11:00", "15:00", DaySelector.ALL, ResetPeriod.DAILY,
                                List.of(new Tier(new BigDecimal("50"), BigDecimal.ZERO),
                                        new Tier(null, new BigDecimal("9.405")))),
                        Band.parseTiered("15:00", "11:00", DaySelector.ALL, ResetPeriod.DAILY,
                                List.of(new Tier(new BigDecimal("15"), new BigDecimal("31.559")),
                                        new Tier(null, new BigDecimal("33.963")))))));
        assertThatCode(() -> PlanValidator.validate(plan)).doesNotThrowAnyException();
    }

    @Test
    void rejectsCappedBandsThatLeaveTheDayUnpriced() {
        var plan = planOf(
                new DailySupply(new BigDecimal("127.49")),
                new TimeOfUse(List.of(
                        Band.parseTiered("11:00", "15:00", DaySelector.ALL, ResetPeriod.DAILY,
                                List.of(new Tier(new BigDecimal("50"), BigDecimal.ZERO),
                                        new Tier(null, new BigDecimal("9.405")))))));
        assertThatThrownBy(() -> PlanValidator.validate(plan))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("is not priced from 00:00");
    }

    @Test
    void acceptsAMidnightWrappingBand() {
        var plan = planOf(
                new DailySupply(new BigDecimal("128.24")),
                new TimeOfUse(List.of(
                        band("16:00", "21:00", "47.64"),
                        band("11:00", "16:00", "17.59"),
                        band("21:00", "11:00", "22.60"))));
        assertThatCode(() -> PlanValidator.validate(plan)).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheSplitAtMidnightEncodingUsedByTheCdrFeed() {
        var plan = planOf(
                new DailySupply(new BigDecimal("128.24")),
                new TimeOfUse(List.of(
                        band("16:00", "21:00", "47.64"),
                        band("11:00", "16:00", "17.59"),
                        band("00:00", "11:00", "22.60"),
                        band("21:00", "24:00", "22.60"))));
        assertThatCode(() -> PlanValidator.validate(plan)).doesNotThrowAnyException();
    }

    @Test
    void acceptsWeekdayAndWeekendBandsThatTogetherTileTheWeek() {
        var plan = planOf(
                new DailySupply(new BigDecimal("100")),
                new TimeOfUse(List.of(
                        Band.parse("00:00", "16:00", DaySelector.WEEKDAYS, new BigDecimal("20")),
                        Band.parse("16:00", "24:00", DaySelector.WEEKDAYS, new BigDecimal("50")),
                        Band.parse("00:00", "24:00", DaySelector.WEEKENDS, new BigDecimal("20")))));
        assertThatCode(() -> PlanValidator.validate(plan)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAGapInBandCoverage() {
        var plan = planOf(
                new DailySupply(new BigDecimal("123.20")),
                new TimeOfUse(List.of(
                        band("00:00", "06:00", "4.99"),
                        band("07:00", "24:00", "24.77"))));
        assertThatThrownBy(() -> PlanValidator.validate(plan))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("06:00");
    }

    @Test
    void rejectsOverlappingBands() {
        var plan = planOf(
                new DailySupply(new BigDecimal("123.20")),
                new TimeOfUse(List.of(
                        band("00:00", "12:00", "4.99"),
                        band("06:00", "24:00", "24.77"))));
        assertThatThrownBy(() -> PlanValidator.validate(plan))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("06:00");
    }

    @Test
    void rejectsWeekendOnlyCoverage() {
        var plan = planOf(
                new DailySupply(new BigDecimal("123.20")),
                new TimeOfUse(List.of(
                        Band.parse("00:00", "24:00", DaySelector.WEEKENDS, new BigDecimal("20")))));
        assertThatThrownBy(() -> PlanValidator.validate(plan))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("MONDAY");
    }

    @Test
    void rejectsMoreThanOneDailySupplyCharge() {
        var plan = planOf(
                new DailySupply(new BigDecimal("100")),
                new DailySupply(new BigDecimal("23.20")),
                new FlatRate(new BigDecimal("31.98")));
        assertThatThrownBy(() -> PlanValidator.validate(plan))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("daily supply");
    }

    @Test
    void rejectsBothFlatAndTimeOfUseUsage() {
        var plan = planOf(
                new DailySupply(new BigDecimal("123.20")),
                new FlatRate(new BigDecimal("31.98")),
                new TimeOfUse(List.of(band("00:00", "24:00", "24.77"))));
        assertThatThrownBy(() -> PlanValidator.validate(plan))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("one usage charge");
    }

    @Test
    void rejectsAPlanWithNoUsageCharge() {
        var plan = planOf(new DailySupply(new BigDecimal("123.20")));
        assertThatThrownBy(() -> PlanValidator.validate(plan))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("no usage charge");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        var plan = planOf(
                new DailySupply(new BigDecimal("100")),
                new DailySupply(new BigDecimal("23.20")),
                new TimeOfUse(List.of(band("00:00", "06:00", "4.99"))));
        assertThat(PlanValidator.problems(plan)).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void namesThePlanInTheMessage() {
        var plan = planOf(new DailySupply(new BigDecimal("123.20")));
        assertThatThrownBy(() -> PlanValidator.validate(plan)).hasMessageContaining("test");
    }

    // ---------- feed-in credits ----------

    /**
     * A feed-in that covers only part of the day is refused for the same reason a usage charge
     * is: the gap is indistinguishable from a window that credits nothing, and the two mean
     * different things to a household deciding whether to install solar.
     */
    @Test
    void rejectsAFeedInThatLeavesPartOfTheDayUncredited() {
        var plan = new Plan("gap", "Gap", "Test", DistributionZone.AUSNET,
                List.of(new FlatRate(new BigDecimal("30.00")),
                        new SolarFeedIn(List.of(
                                Band.parse("16:00", "21:00", DaySelector.ALL,
                                        new BigDecimal("3.30"))))),
                true, null, null);

        assertThat(PlanValidator.problems(plan))
                .anySatisfy(problem -> assertThat(problem).contains("not credited"));
    }

    @Test
    void rejectsOverlappingFeedInBands() {
        var plan = new Plan("overlap", "Overlap", "Test", DistributionZone.AUSNET,
                List.of(new FlatRate(new BigDecimal("30.00")),
                        new SolarFeedIn(List.of(
                                Band.parse("00:00", "24:00", DaySelector.ALL,
                                        new BigDecimal("1.00")),
                                Band.parse("16:00", "21:00", DaySelector.ALL,
                                        new BigDecimal("3.30"))))),
                true, null, null);

        assertThat(PlanValidator.problems(plan))
                .anySatisfy(problem -> assertThat(problem).contains("more than one band"));
    }

    @Test
    void acceptsAFeedInWhoseBandsCoverTheWholeDay() {
        var plan = new Plan("tiled", "Tiled", "Test", DistributionZone.AUSNET,
                List.of(new FlatRate(new BigDecimal("30.00")),
                        new SolarFeedIn(List.of(
                                Band.parse("00:00", "16:00", DaySelector.ALL,
                                        new BigDecimal("0.11")),
                                Band.parse("16:00", "21:00", DaySelector.ALL,
                                        new BigDecimal("3.30")),
                                Band.parse("21:00", "24:00", DaySelector.ALL,
                                        new BigDecimal("0.11"))))),
                true, null, null);

        assertThat(PlanValidator.problems(plan)).isEmpty();
    }

    @Test
    void acceptsAFlatFeedInWhichCoversTheDayByConstruction() {
        var plan = new Plan("flat", "Flat", "Test", DistributionZone.AUSNET,
                List.of(new FlatRate(new BigDecimal("30.00")),
                        new SolarFeedIn(new BigDecimal("3.30"))),
                true, null, null);

        assertThat(PlanValidator.problems(plan)).isEmpty();
    }
}
