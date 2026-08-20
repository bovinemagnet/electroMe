package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Where the ranking flips, and on what.
 *
 * <p>The number this produces is a claim about a contract a household is about to sign, so the
 * tests here are about whether it is <em>true</em>, not whether it is produced.
 */
class CrossoverAnalysisTest {

    /** A year, so an annualised figure needs no scaling and can be read directly. */
    private static final DateRange YEAR =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

    /**
     * A year of consumption with a realistic evening peak.
     *
     * @param dailyKWh spread across the day, with a third of it in the 16:00-21:00 window
     */
    private static UsageData usage(String dailyKWh) {
        var perDay = new BigDecimal(dailyKWh);
        var peakShare = perDay.multiply(new BigDecimal("0.333"));
        var restShare = perDay.subtract(peakShare);
        var peakSlot = peakShare.divide(new BigDecimal("10"), java.math.MathContext.DECIMAL64);
        var restSlot = restShare.divide(new BigDecimal("38"), java.math.MathContext.DECIMAL64);

        var readings = new ArrayList<IntervalReading>();
        var day = LocalDate.of(2025, 1, 1);
        while (!day.isAfter(LocalDate.of(2025, 12, 31))) {
            for (int slot = 0; slot < 48; slot++) {
                int minute = slot * 30;
                boolean peak = minute >= 16 * 60 && minute < 21 * 60;
                readings.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT).plusMinutes(minute),
                        Duration.ofMinutes(30),
                        peak ? peakSlot : restSlot,
                        Quality.ACTUAL));
            }
            day = day.plusDays(1);
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(String id, String supplyCents, Charge... usage) {
        var charges = new ArrayList<Charge>();
        charges.add(new DailySupply(new BigDecimal(supplyCents)));
        charges.addAll(List.of(usage));
        return new Plan(id, "Plan " + id, "Retailer", DistributionZone.AUSNET, charges,
                true, null, null);
    }

    private static TimeOfUse tou(String offPeak, String peak) {
        return new TimeOfUse(List.of(
                new Band(0, 16 * 60, DaySelector.ALL, new BigDecimal(offPeak)),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal(peak)),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal(offPeak))));
    }

    // -----------------------------------------------------------------
    // The closed form, in isolation.
    // -----------------------------------------------------------------

    @Test
    void theClosedFormSolvesAHandComputedCase() {
        // A has the higher supply charge but the cheaper usage, so it wins above the crossover.
        // supply 500 vs 300, usage 1000 vs 1200: k* = (300-500)/(1000-1200) = 1.0
        var k = CrossoverAnalysis.linearCrossover(
                new BigDecimal("500"), new BigDecimal("1000"),
                new BigDecimal("300"), new BigDecimal("1200"));

        assertThat(k).isPresent();
        assertThat(k.get()).isEqualByComparingTo("1.0");
    }

    @Test
    void theClosedFormReportsNoCrossoverWhenOnePlanWinsOnBothTerms() {
        // The worked example from the design, using the household's real figures. The current
        // tariff is cheaper on supply AND on usage, so it wins at every positive consumption.
        //
        // k* = (468.08 - 449.68) / (2403.12 - 2425.57) = -0.82
        //
        // Reporting -0.82 of anything, or silently naming a winner, would both be wrong: the
        // honest finding is that there is no crossover at all.
        var k = CrossoverAnalysis.linearCrossover(
                new BigDecimal("449.68"), new BigDecimal("2403.12"),
                new BigDecimal("468.08"), new BigDecimal("2425.57"));

        assertThat(k).isEmpty();
    }

    @Test
    void theClosedFormReportsNoCrossoverWhenTheUsageTermsAreEqual() {
        // Parallel lines: the same usage rate, different supply charges. One wins everywhere.
        var k = CrossoverAnalysis.linearCrossover(
                new BigDecimal("500"), new BigDecimal("1000"),
                new BigDecimal("300"), new BigDecimal("1000"));

        assertThat(k).isEmpty();
    }

    @Test
    void theClosedFormReportsNoCrossoverAtExactlyZeroConsumption() {
        // k* = 0 means they are equal only when nothing is used, which is not a crossover a
        // household can be on either side of.
        var k = CrossoverAnalysis.linearCrossover(
                new BigDecimal("500"), new BigDecimal("1000"),
                new BigDecimal("500"), new BigDecimal("1200"));

        assertThat(k).isEmpty();
    }

    // -----------------------------------------------------------------
    // End to end, against real costings.
    // -----------------------------------------------------------------

    @Test
    void findsTheCrossoverBetweenAHighSupplyAndAHighUsagePlan() {
        var usage = usage("20");
        var lowSupplyDearUsage = plan("dear-usage", "50.00", new FlatRate(new BigDecimal("35.00")));
        var highSupplyCheapUsage =
                plan("cheap-usage", "180.00", new FlatRate(new BigDecimal("25.00")));

        var crossover = CrossoverAnalysis.between(
                usage, lowSupplyDearUsage, highSupplyCheapUsage, YEAR);

        assertThat(crossover.exists()).isTrue();
        assertThat(crossover.method()).isEqualTo(CrossoverAnalysis.Method.LINEAR);
        // Below the crossover the low supply charge wins; above it the cheap usage rate does.
        assertThat(crossover.winnerBelow()).isEqualTo("dear-usage");
        assertThat(crossover.winnerAbove()).isEqualTo("cheap-usage");
    }

    @Test
    void reportsTheCrossoverAsAnAnnualConsumptionTheReaderCanPlaceThemselvesAgainst() {
        var usage = usage("20");
        var crossover = CrossoverAnalysis.between(
                usage,
                plan("dear-usage", "50.00", new FlatRate(new BigDecimal("35.00"))),
                plan("cheap-usage", "180.00", new FlatRate(new BigDecimal("25.00"))),
                YEAR);

        // A scale factor means nothing to a reader; their own consumption gives it a reference.
        assertThat(crossover.householdAnnualKWh()).isCloseTo(
                new BigDecimal("7300"), org.assertj.core.data.Offset.offset(new BigDecimal("5")));
        assertThat(crossover.crossoverAnnualKWh()).isNotNull();
        assertThat(crossover.crossoverAnnualKWh()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void saysAPlanWinsAtEveryLevelOfUseRatherThanInventingANumber() {
        var usage = usage("20");
        var better = plan("better", "100.00", new FlatRate(new BigDecimal("25.00")));
        var worse = plan("worse", "120.00", new FlatRate(new BigDecimal("30.00")));

        var crossover = CrossoverAnalysis.between(usage, better, worse, YEAR);

        assertThat(crossover.exists()).isFalse();
        assertThat(crossover.crossoverAnnualKWh()).isNull();
        assertThat(crossover.alwaysWins()).isEqualTo("better");
        assertThat(crossover.winnerBelow()).isEqualTo("better");
        assertThat(crossover.winnerAbove()).isEqualTo("better");
    }

    // -----------------------------------------------------------------
    // When the closed form does not hold.
    // -----------------------------------------------------------------

    @Test
    void abandonsTheClosedFormForABlockTariff() {
        // Block thresholds reset per period and do not scale, so cost is piecewise-linear in the
        // scale factor. Solving a straight line through it would be quietly wrong.
        var blocks = plan("blocks", "100.00", new Tiered(ResetPeriod.QUARTERLY, List.of(
                new Tier(new BigDecimal("1000"), new BigDecimal("20.00")),
                new Tier(null, new BigDecimal("40.00")))));

        var crossover = CrossoverAnalysis.between(usage("20"),
                plan("flat", "120.00", new FlatRate(new BigDecimal("28.00"))), blocks, YEAR);

        assertThat(crossover.method()).isEqualTo(CrossoverAnalysis.Method.BISECTION);
    }

    @Test
    void abandonsTheClosedFormForADemandCharge() {
        var demand = plan("demand", "100.00",
                new FlatRate(new BigDecimal("20.00")),
                new Demand(16 * 60, 21 * 60, DaySelector.ALL, ResetPeriod.MONTHLY,
                        new BigDecimal("30.00")));

        var crossover = CrossoverAnalysis.between(usage("20"),
                plan("flat", "120.00", new FlatRate(new BigDecimal("28.00"))), demand, YEAR);

        assertThat(crossover.method()).isEqualTo(CrossoverAnalysis.Method.BISECTION);
    }

    @Test
    void theTwoMethodsAgreeWhereBothApply() {
        // The strongest available check on the bisection: run it against a pair the closed form
        // solves exactly, and require the same answer.
        var usage = usage("20");
        var a = plan("a", "50.00", new FlatRate(new BigDecimal("35.00")));
        var b = plan("b", "180.00", new FlatRate(new BigDecimal("25.00")));

        var linear = CrossoverAnalysis.between(usage, a, b, YEAR);
        var bisected = CrossoverAnalysis.byBisection(usage, a, b, YEAR);

        assertThat(linear.method()).isEqualTo(CrossoverAnalysis.Method.LINEAR);
        assertThat(bisected.method()).isEqualTo(CrossoverAnalysis.Method.BISECTION);
        assertThat(bisected.crossoverAnnualKWh()).isCloseTo(
                linear.crossoverAnnualKWh(),
                org.assertj.core.data.Offset.offset(
                        linear.crossoverAnnualKWh().multiply(new BigDecimal("0.01"))));
    }

    @Test
    void bisectionAlsoReportsNoCrossoverWhenOnePlanWinsEverywhere() {
        var crossover = CrossoverAnalysis.byBisection(usage("20"),
                plan("better", "100.00", new FlatRate(new BigDecimal("25.00"))),
                plan("worse", "120.00", new FlatRate(new BigDecimal("30.00"))),
                YEAR);

        assertThat(crossover.exists()).isFalse();
        assertThat(crossover.alwaysWins()).isEqualTo("better");
    }

    // -----------------------------------------------------------------
    // The invariant that makes the number worth printing.
    // -----------------------------------------------------------------

    @Test
    void aReportedCrossoverAlwaysHasADifferentWinnerOnEitherSideOfIt() {
        // Property-style, over random plan pairs: either there is no crossover, or the winner
        // genuinely differs across it. Anything else means the number is decoration.
        var random = new Random(20260820L);
        var usage = usage("18");
        int examined = 0;
        int withCrossover = 0;

        for (int i = 0; i < 40; i++) {
            var a = plan("a" + i, supply(random), usageCharge(random, i));
            var b = plan("b" + i, supply(random), usageCharge(random, i + 1));

            var crossover = CrossoverAnalysis.between(usage, a, b, YEAR);
            examined++;
            if (!crossover.exists()) {
                assertThat(crossover.winnerBelow()).isEqualTo(crossover.winnerAbove());
                continue;
            }
            withCrossover++;

            assertThat(crossover.winnerBelow())
                    .as("pair %d: winner below the crossover", i)
                    .isNotEqualTo(crossover.winnerAbove());

            // And the claim is checked against the engine rather than against the algebra that
            // produced it: cost both plans either side and confirm the winner really changes.
            assertThat(CrossoverAnalysis.cheaperAt(usage, a, b, YEAR,
                            crossover.scaleFactor().multiply(new BigDecimal("0.8"))))
                    .isEqualTo(crossover.winnerBelow());
            assertThat(CrossoverAnalysis.cheaperAt(usage, a, b, YEAR,
                            crossover.scaleFactor().multiply(new BigDecimal("1.25"))))
                    .isEqualTo(crossover.winnerAbove());
        }

        assertThat(examined).isEqualTo(40);
        // A run in which nothing ever crossed would pass every assertion above it.
        assertThat(withCrossover).as("pairs that actually crossed").isGreaterThan(3);
    }

    private static String supply(Random random) {
        return (60 + random.nextInt(120)) + ".00";
    }

    private static Charge usageCharge(Random random, int seed) {
        if (seed % 3 == 0) {
            return new FlatRate(new BigDecimal((20 + random.nextInt(20)) + ".00"));
        }
        int offPeak = 12 + random.nextInt(15);
        return tou(offPeak + ".00", (offPeak + 5 + random.nextInt(35)) + ".00");
    }

    @Test
    void namesUniformScalingAsTheApproximationItIs() {
        var crossover = CrossoverAnalysis.between(usage("20"),
                plan("a", "50.00", new FlatRate(new BigDecimal("35.00"))),
                plan("b", "180.00", new FlatRate(new BigDecimal("25.00"))), YEAR);

        // Real households do not scale consumption evenly across every half hour, and the
        // screen has to say so rather than present the figure as exact.
        assertThat(crossover.caveat()).containsIgnoringCase("evenly");
    }
}
