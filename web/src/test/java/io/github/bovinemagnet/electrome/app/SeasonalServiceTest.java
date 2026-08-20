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
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.DiscountScope;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Is switching twice a year worth it?
 *
 * <p>A ranking of annual totals cannot answer that. A household whose pool moves several
 * thousand kilowatt hours into the middle of the day from November to March has two different
 * consumption shapes, and the plan that wins on one can lose badly on the other.
 */
class SeasonalServiceTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final LocalDate END = LocalDate.of(2025, 12, 31);
    private static final DateRange YEAR = new DateRange(START, END);

    private final SeasonalService service = new SeasonalService();

    /**
     * A household with a pool: heavy midday use from November to March, heavy evening use for
     * the rest of the year. Exactly the shape that makes the question worth asking.
     */
    private static UsageData poolHousehold() {
        var readings = new ArrayList<IntervalReading>();
        for (var day = START; !day.isAfter(END); day = day.plusDays(1)) {
            boolean poolSeason = SeasonSplit.DEFAULT.contains(day.getMonth());
            for (int minute = 0; minute < 1440; minute += 30) {
                boolean midday = minute >= 11 * 60 && minute < 15 * 60;
                boolean evening = minute >= 16 * 60 && minute < 21 * 60;
                BigDecimal kWh;
                if (poolSeason && midday) {
                    kWh = new BigDecimal("2.0");
                } else if (!poolSeason && evening) {
                    kWh = new BigDecimal("1.5");
                } else {
                    kWh = new BigDecimal("0.2");
                }
                readings.add(new IntervalReading(day.atStartOfDay().plusMinutes(minute),
                        Duration.ofMinutes(30), kWh, Quality.ACTUAL));
            }
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(String id, String name, Charge... charges) {
        var all = new ArrayList<Charge>();
        all.add(new DailySupply(new BigDecimal("100.00")));
        all.addAll(List.of(charges));
        return new Plan(id, name, "Retailer", DistributionZone.AUSNET, all, true, null, null);
    }

    /** Cheap in the middle of the day, dear in the evening: wins over the pool season. */
    private static Plan middayFriendly() {
        return plan("midday", "Midday saver", new TimeOfUse(List.of(
                new Band(11 * 60, 15 * 60, DaySelector.ALL, new BigDecimal("5.00")),
                new Band(15 * 60, 11 * 60, DaySelector.ALL, new BigDecimal("45.00")))));
    }

    /** The mirror image: cheap in the evening, dear at midday. */
    private static Plan eveningFriendly() {
        return plan("evening", "Evening saver", new TimeOfUse(List.of(
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("5.00")),
                new Band(21 * 60, 16 * 60, DaySelector.ALL, new BigDecimal("45.00")))));
    }

    private static Plan flat() {
        return plan("flat", "Flat", new FlatRate(new BigDecimal("28.00")));
    }

    private static SeasonalReport.SeasonalPlanCost costFor(SeasonalReport report, String id) {
        return report.plans().stream()
                .filter(p -> p.planId().equals(id))
                .findFirst().orElseThrow();
    }

    // -----------------------------------------------------------------

    /**
     * The property everything else rests on: a season is the same arithmetic as the year it
     * came from, restricted to fewer days.
     */
    @Test
    void aPlansTwoSeasonsAddUpToItsYear() {
        var report = service.report(YEAR, SeasonSplit.DEFAULT, poolHousehold(),
                List.of(middayFriendly(), eveningFriendly(), flat()));

        assertThat(report.plans()).hasSize(3);
        assertThat(report.plans()).allSatisfy(cost -> {
            assertThat(cost.decomposes()).isTrue();
            // Within a cent: all three figures are rounded to cents independently.
            assertThat(cost.first().add(cost.second()).subtract(cost.wholeYear()).abs())
                    .isLessThanOrEqualTo(new BigDecimal("0.01"));
        });
        assertThat(report.caveats()).isEmpty();
    }

    @Test
    void namesTheCheapestPlanInEachSeasonSeparately() {
        var report = service.report(YEAR, SeasonSplit.DEFAULT, poolHousehold(),
                List.of(middayFriendly(), eveningFriendly(), flat()));

        assertThat(report.bestInFirst().planId()).isEqualTo("midday");
        assertThat(report.bestInSecond().planId()).isEqualTo("evening");
        assertThat(report.worthSwitching()).isTrue();
        assertThat(report.onePlanWinsBoth()).isFalse();
    }

    @Test
    void switchingBeatsTheBestSinglePlanByTheStatedAmount() {
        var report = service.report(YEAR, SeasonSplit.DEFAULT, poolHousehold(),
                List.of(middayFriendly(), eveningFriendly(), flat()));

        var expected = costFor(report, "midday").first().add(costFor(report, "evening").second());
        assertThat(report.switchingTotal()).isEqualByComparingTo(expected);
        assertThat(report.savingFromSwitching())
                .isEqualByComparingTo(report.bestSingle().wholeYear().subtract(expected));
        assertThat(report.savingFromSwitching()).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * One plan winning both halves is the common case, and a more useful finding than any
     * number: it means the annual ranking was not hiding a seasonal answer.
     */
    @Test
    void saysSoWhenOnePlanWinsBothSeasons() {
        var report = service.report(YEAR, SeasonSplit.DEFAULT, poolHousehold(),
                List.of(middayFriendly(), plan("dearer", "Dearer flat",
                        new FlatRate(new BigDecimal("60.00")))));

        assertThat(report.onePlanWinsBoth()).isTrue();
        assertThat(report.worthSwitching()).isFalse();
        assertThat(report.savingFromSwitching()).isEqualByComparingTo("0");
    }

    @Test
    void ranksPlansByTheirWholeYearCost() {
        var report = service.report(YEAR, SeasonSplit.DEFAULT, poolHousehold(),
                List.of(middayFriendly(), eveningFriendly(), flat()));

        assertThat(report.plans())
                .extracting(SeasonalReport.SeasonalPlanCost::wholeYear).isSorted();
        assertThat(report.bestSingle().planId())
                .isEqualTo(report.plans().get(0).planId());
    }

    // -----------------------------------------------------------------
    // Tariffs that do not decompose. The report says so rather than quietly reporting a figure
    // that cannot be reconciled against the year beside it.
    // -----------------------------------------------------------------

    @Test
    void saysWhenAFixedDiscountIsCountedInBothSeasons() {
        // A fixed amount is applied once per costing, so splitting the year applies it twice.
        var withCredit = plan("credit", "Annual credit",
                new FlatRate(new BigDecimal("28.00")),
                new Discount("Sign-up credit", DiscountBasis.FIXED, DiscountScope.TOTAL,
                        new BigDecimal("5000"), null));

        var report = service.report(YEAR, SeasonSplit.DEFAULT, poolHousehold(),
                List.of(withCredit));
        var cost = costFor(report, "credit");

        assertThat(cost.decomposes()).isFalse();
        // $50 of credit, granted twice instead of once.
        assertThat(cost.first().add(cost.second()).subtract(cost.wholeYear()))
                .isEqualByComparingTo("-50.00");
        assertThat(cost.caveat()).contains("50.00").contains("less than");
        assertThat(report.caveats()).hasSize(1);
        assertThat(report.hasCaveats()).isTrue();
    }

    @Test
    void saysWhenABlockRateResetsInBothSeasons() {
        var quarterlyBlocks = plan("blocks", "Quarterly blocks",
                new Tiered(ResetPeriod.QUARTERLY, List.of(
                        new Tier(new BigDecimal("500"), new BigDecimal("20.00")),
                        new Tier(null, new BigDecimal("40.00")))));

        var report = service.report(YEAR, SeasonSplit.DEFAULT, poolHousehold(),
                List.of(quarterlyBlocks));
        var cost = costFor(report, "blocks");

        // A quarter straddling the split gets its cheap block in both halves, so the seasons
        // come to less than the year.
        assertThat(cost.decomposes()).isFalse();
        assertThat(cost.caveat()).contains("lower bound");
    }

    @Test
    void reportsNothingWhenThereAreNoPlans() {
        var report = service.report(YEAR, SeasonSplit.DEFAULT, poolHousehold(), List.of());
        assertThat(report.empty()).isTrue();
        assertThat(report.worthSwitching()).isFalse();
        assertThat(report.savingFromSwitching()).isEqualByComparingTo("0");
        assertThat(report.switchingTotal()).isEqualByComparingTo("0");
    }
}
