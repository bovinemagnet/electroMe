package io.github.bovinemagnet.electrome.core.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
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
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostingEngineTest {

    private static final CostingEngine ENGINE = new CostingEngine();
    private static final LocalDate JAN1 = LocalDate.of(2025, 1, 1);

    private static Plan planOf(Charge... charges) {
        return new Plan("t", "Test", "Retailer", DistributionZone.AUSNET,
                List.of(charges), true, null, null);
    }

    private static UsageData day(LocalDate date, String eachKWh) {
        return days(date, date, eachKWh);
    }

    private static UsageData days(LocalDate from, LocalDate to, String eachKWh) {
        var readings = new ArrayList<IntervalReading>();
        for (var d = from; !d.isAfter(to); d = d.plusDays(1)) {
            for (int minute = 0; minute < 1440; minute += 30) {
                readings.add(new IntervalReading(
                        d.atStartOfDay().plusMinutes(minute), Duration.ofMinutes(30),
                        new BigDecimal(eachKWh), Quality.ACTUAL));
            }
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    /** One day of half-hourly readings, each of the same size. */
    private static List<IntervalReading> dayAt(LocalDate date, String eachKWh) {
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            readings.add(new IntervalReading(date.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), new BigDecimal(eachKWh), Quality.ACTUAL));
        }
        return readings;
    }

    private static DateRange on(LocalDate date) {
        return new DateRange(date, date);
    }

    @Test
    void chargesSupplyPerBillingDay() {
        var plan = planOf(new DailySupply(new BigDecimal("123.20")), new FlatRate(BigDecimal.ZERO));
        var bill = ENGINE.cost(days(JAN1, JAN1.plusDays(9), "1"), plan,
                new DateRange(JAN1, JAN1.plusDays(9)));
        assertThat(bill.billingDays()).isEqualTo(10);
        assertThat(bill.subtotal(ChargeKind.SUPPLY)).isEqualByComparingTo("12.32");
    }

    @Test
    void supplyFollowsDistinctDatesNotIntervalCount() {
        var readings = new ArrayList<IntervalReading>();
        var springForward = LocalDate.of(2025, 10, 5);
        for (int minute = 0; minute < 1440; minute += 30) {
            if (minute >= 120 && minute < 180) {
                continue;
            }
            readings.add(new IntervalReading(springForward.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), BigDecimal.ONE, Quality.ACTUAL));
        }
        var plan = planOf(new DailySupply(new BigDecimal("100")), new FlatRate(BigDecimal.ZERO));
        var bill = ENGINE.cost(UsageData.consumptionOnly(UsageSeries.of(readings)), plan,
                on(springForward));
        assertThat(bill.billingDays()).isEqualTo(1);
        assertThat(bill.subtotal(ChargeKind.SUPPLY)).isEqualByComparingTo("1.00");
    }

    @Test
    void costsFlatRateUsage() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new FlatRate(new BigDecimal("31.98")));
        var bill = ENGINE.cost(day(JAN1, "0.5"), plan, on(JAN1));
        assertThat(bill.subtotal(ChargeKind.USAGE)).isEqualByComparingTo("7.6752");
        assertThat(bill.totalRounded()).isEqualByComparingTo("7.68");
    }

    @Test
    void producesOneLinePerTimeOfUseBand() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new TimeOfUse(List.of(
                Band.parse("00:00", "06:00", DaySelector.ALL, new BigDecimal("4.99")),
                Band.parse("06:00", "16:00", DaySelector.ALL, new BigDecimal("24.77")),
                Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("49.54")),
                Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("24.77")))));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        var usageLines = bill.lines().stream().filter(l -> l.kind() == ChargeKind.USAGE).toList();
        assertThat(usageLines).hasSize(4);
        assertThat(usageLines).extracting(ChargeLine::label)
                .containsExactly("Usage 00:00-06:00", "Usage 06:00-16:00",
                        "Usage 16:00-21:00", "Usage 21:00-24:00");
        assertThat(usageLines.get(0).quantity()).isEqualByComparingTo("12");
        assertThat(usageLines.get(2).quantity()).isEqualByComparingTo("10");
        assertThat(usageLines.get(2).cost()).isEqualByComparingTo("4.954");
    }

    @Test
    void reportsIntervalsNoBandCovers() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new TimeOfUse(List.of(
                Band.parse("00:00", "06:00", DaySelector.ALL, new BigDecimal("4.99")))));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        assertThat(bill.complete()).isFalse();
        assertThat(bill.uncoveredIntervals()).hasSize(36);
    }

    @Test
    void completeBillHasNoUncoveredIntervals() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new TimeOfUse(List.of(
                Band.parse("00:00", "24:00", DaySelector.ALL, new BigDecimal("24.77")))));
        assertThat(ENGINE.cost(day(JAN1, "1"), plan, on(JAN1)).complete()).isTrue();
    }

    @Test
    void costsTieredUsageAcrossBlocks() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new Tiered(ResetPeriod.DAILY, List.of(
                new Tier(new BigDecimal("20"), new BigDecimal("30")),
                new Tier(null, new BigDecimal("20")))));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        assertThat(bill.subtotal(ChargeKind.USAGE)).isEqualByComparingTo("11.60");
    }

    @Test
    void tieredBlocksResetEachPeriod() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new Tiered(ResetPeriod.DAILY, List.of(
                new Tier(new BigDecimal("20"), new BigDecimal("30")),
                new Tier(null, new BigDecimal("20")))));
        var bill = ENGINE.cost(days(JAN1, JAN1.plusDays(1), "1"), plan,
                new DateRange(JAN1, JAN1.plusDays(1)));
        assertThat(bill.subtotal(ChargeKind.USAGE)).isEqualByComparingTo("23.20");
    }

    /**
     * A capped window, over and under its cap.
     *
     * <p>GloBird's free window is the reason this exists: the first block of a day's
     * consumption inside the window is priced differently from the rest of it.
     */
    @Test
    void costsACappedBandBlockByBlockWithinEachDay() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new TimeOfUse(List.of(
                Band.parseTiered("11:00", "15:00", DaySelector.ALL, ResetPeriod.DAILY, List.of(
                        new Tier(new BigDecimal("5"), BigDecimal.ZERO),
                        new Tier(null, new BigDecimal("10")))),
                Band.parse("15:00", "11:00", DaySelector.ALL, new BigDecimal("20")))));

        // A light day stays under the 5 kWh cap; a heavy one spills past it.
        var readings = new ArrayList<IntervalReading>();
        readings.addAll(dayAt(JAN1, "0.5"));
        readings.addAll(dayAt(JAN1.plusDays(1), "1"));
        var bill = ENGINE.cost(UsageData.consumptionOnly(UsageSeries.of(readings)), plan,
                new DateRange(JAN1, JAN1.plusDays(1)));

        var capped = bill.lines().stream()
                .filter(l -> l.label().startsWith("Usage 11:00-15:00")).toList();
        assertThat(capped).extracting(ChargeLine::label)
                .containsExactly("Usage 11:00-15:00 to 5 kWh", "Usage 11:00-15:00 balance");
        // 4 kWh on the light day and the whole 5 kWh cap on the heavy one.
        assertThat(capped.get(0).quantity()).isEqualByComparingTo("9");
        assertThat(capped.get(0).cost()).isEqualByComparingTo("0");
        // Only the heavy day spills, and only by 3 kWh.
        assertThat(capped.get(1).quantity()).isEqualByComparingTo("3");
        assertThat(capped.get(1).cost()).isEqualByComparingTo("0.30");
        assertThat(bill.complete()).isTrue();
    }

    @Test
    void twoCappedBandsDoNotSpendEachOthersCap() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new TimeOfUse(List.of(
                Band.parseTiered("00:00", "12:00", DaySelector.ALL, ResetPeriod.DAILY, List.of(
                        new Tier(new BigDecimal("3"), new BigDecimal("1")),
                        new Tier(null, new BigDecimal("2")))),
                Band.parseTiered("12:00", "24:00", DaySelector.ALL, ResetPeriod.DAILY, List.of(
                        new Tier(new BigDecimal("3"), new BigDecimal("10")),
                        new Tier(null, new BigDecimal("20")))))));

        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));

        // 24 kWh falls in each half of the day, and each band fills its own 3 kWh block.
        assertThat(bill.lines()).extracting(ChargeLine::label, l -> l.quantity().stripTrailingZeros())
                .contains(
                        tuple("Usage 00:00-12:00 to 3 kWh", new BigDecimal("3")),
                        tuple("Usage 00:00-12:00 balance", new BigDecimal("21")),
                        tuple("Usage 12:00-24:00 to 3 kWh", new BigDecimal("3")),
                        tuple("Usage 12:00-24:00 balance", new BigDecimal("21")));
        assertThat(bill.subtotal(ChargeKind.USAGE)).isEqualByComparingTo("4.95");
    }

    @Test
    void aCappedBandCanResetMonthlyRatherThanDaily() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new TimeOfUse(List.of(
                Band.parseTiered("00:00", "24:00", DaySelector.ALL, ResetPeriod.MONTHLY, List.of(
                        new Tier(new BigDecimal("100"), new BigDecimal("10")),
                        new Tier(null, new BigDecimal("20")))))));

        var jan25 = LocalDate.of(2025, 1, 25);
        var feb5 = LocalDate.of(2025, 2, 5);
        var bill = ENGINE.cost(days(jan25, feb5, "0.5"), plan, new DateRange(jan25, feb5));

        // 24 kWh a day: 168 kWh in January and 120 in February, each month capped at 100.
        assertThat(bill.lines().get(1).quantity()).isEqualByComparingTo("200");
        assertThat(bill.lines().get(2).quantity()).isEqualByComparingTo("88");
        assertThat(bill.subtotal(ChargeKind.USAGE)).isEqualByComparingTo("37.60");
    }

    @Test
    void anUncappedBandKeepsItsPlainLabelAndSingleRate() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new TimeOfUse(List.of(
                Band.parse("00:00", "24:00", DaySelector.ALL, new BigDecimal("24.77")))));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        var usage = bill.lines().stream().filter(l -> l.kind() == ChargeKind.USAGE).toList();
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).label()).isEqualTo("Usage 00:00-24:00");
        assertThat(usage.get(0).quantity()).isEqualByComparingTo("48");
        assertThat(usage.get(0).cost()).isEqualByComparingTo("11.8896");
    }

    @Test
    void costsDemandOnThePeakIntervalWithinTheWindow() {
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            var kWh = minute == 1020 ? new BigDecimal("2") : new BigDecimal("0.1");
            readings.add(new IntervalReading(JAN1.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), kWh, Quality.ACTUAL));
        }
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new FlatRate(BigDecimal.ZERO),
                new Demand(960, 1260, DaySelector.ALL, ResetPeriod.MONTHLY, new BigDecimal("50")));
        var bill = ENGINE.cost(UsageData.consumptionOnly(UsageSeries.of(readings)), plan, on(JAN1));
        assertThat(bill.subtotal(ChargeKind.DEMAND)).isEqualByComparingTo("2.00");
    }

    @Test
    void demandIgnoresPeaksOutsideTheWindow() {
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            var kWh = minute == 120 ? new BigDecimal("5") : new BigDecimal("0.5");
            readings.add(new IntervalReading(JAN1.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), kWh, Quality.ACTUAL));
        }
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new FlatRate(BigDecimal.ZERO),
                new Demand(960, 1260, DaySelector.ALL, ResetPeriod.MONTHLY, new BigDecimal("50")));
        var bill = ENGINE.cost(UsageData.consumptionOnly(UsageSeries.of(readings)), plan, on(JAN1));
        assertThat(bill.subtotal(ChargeKind.DEMAND)).isEqualByComparingTo("0.50");
    }

    @Test
    void feedInCreditsExportAsANegativeLine() {
        var exportReadings = new ArrayList<IntervalReading>();
        for (int minute = 600; minute < 900; minute += 30) {
            exportReadings.add(new IntervalReading(JAN1.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), BigDecimal.ONE, Quality.ACTUAL));
        }
        var usage = new UsageData(day(JAN1, "1").consumption(), UsageSeries.of(exportReadings));
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new FlatRate(BigDecimal.ZERO),
                new SolarFeedIn(new BigDecimal("3.3")));
        var bill = ENGINE.cost(usage, plan, on(JAN1));
        assertThat(bill.subtotal(ChargeKind.FEED_IN)).isEqualByComparingTo("-0.33");
    }

    @Test
    void feedInIsZeroWithoutExport() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new FlatRate(BigDecimal.ZERO),
                new SolarFeedIn(new BigDecimal("3.3")));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        assertThat(bill.subtotal(ChargeKind.FEED_IN)).isEqualByComparingTo("0");
    }

    @Test
    void percentageDiscountAppliesToItsScopeOnly() {
        var plan = planOf(new DailySupply(new BigDecimal("100")),
                new FlatRate(new BigDecimal("25")),
                new Discount("Usage discount", DiscountBasis.PERCENTAGE, DiscountScope.USAGE,
                        new BigDecimal("10"), null));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        assertThat(bill.subtotal(ChargeKind.DISCOUNT)).isEqualByComparingTo("-1.20");
        assertThat(bill.totalRounded()).isEqualByComparingTo("11.80");
    }

    /**
     * A discount the household has to earn is priced, and named.
     *
     * <p>The arithmetic is the same either way. What differs is the claim the total makes,
     * and a comparison that cannot tell the two apart quietly ranks a best case against a
     * certainty.
     */
    @Test
    void aConditionalDiscountNamesWhatTheTotalAssumes() {
        var plan = planOf(new DailySupply(new BigDecimal("100")),
                new FlatRate(new BigDecimal("25")),
                new Discount("Pay on time", DiscountBasis.PERCENTAGE, DiscountScope.USAGE,
                        new BigDecimal("10"), "pay every bill by its due date"));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));

        assertThat(bill.assumesConditions()).isTrue();
        assertThat(bill.discountConditions())
                .containsExactly("Pay on time: pay every bill by its due date");
        assertThat(bill.discountConditionsText())
                .isEqualTo("Pay on time: pay every bill by its due date");
        // Costed exactly as an unconditional discount is: only the claim differs.
        assertThat(bill.subtotal(ChargeKind.DISCOUNT)).isEqualByComparingTo("-1.20");
    }

    @Test
    void anUnconditionalDiscountAssumesNothing() {
        var plan = planOf(new DailySupply(new BigDecimal("100")),
                new FlatRate(new BigDecimal("25")),
                new Discount("Welcome credit", DiscountBasis.FIXED, DiscountScope.TOTAL,
                        new BigDecimal("500"), null));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        assertThat(bill.assumesConditions()).isFalse();
        assertThat(bill.discountConditions()).isEmpty();
    }

    @Test
    void fixedDiscountIsExpressedInCents() {
        var plan = planOf(new DailySupply(new BigDecimal("100")),
                new FlatRate(new BigDecimal("25")),
                new Discount("Credit", DiscountBasis.FIXED, DiscountScope.TOTAL,
                        new BigDecimal("500"), null));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        assertThat(bill.subtotal(ChargeKind.DISCOUNT)).isEqualByComparingTo("-5.00");
    }

    @Test
    void costingIsRestrictedToTheRequestedRange() {
        var plan = planOf(new DailySupply(new BigDecimal("100")), new FlatRate(BigDecimal.ZERO));
        var bill = ENGINE.cost(days(JAN1, JAN1.plusDays(9), "1"), plan,
                new DateRange(JAN1, JAN1.plusDays(2)));
        assertThat(bill.billingDays()).isEqualTo(3);
    }

    @Test
    void reportsAverageCentsPerKWh() {
        var plan = planOf(new DailySupply(BigDecimal.ZERO), new FlatRate(new BigDecimal("25")));
        var bill = ENGINE.cost(day(JAN1, "1"), plan, on(JAN1));
        assertThat(bill.averageCentsPerKWh()).isEqualByComparingTo("25");
    }

    @Test
    void emptyUsageProducesZeroBill() {
        var plan = planOf(new DailySupply(new BigDecimal("123.20")), new FlatRate(BigDecimal.TEN));
        var bill = ENGINE.cost(UsageData.consumptionOnly(UsageSeries.empty()), plan, on(JAN1));
        assertThat(bill.billingDays()).isZero();
        assertThat(bill.totalRounded()).isEqualByComparingTo("0.00");
    }
}
