package io.github.bovinemagnet.electrome.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
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
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import io.github.bovinemagnet.electrome.market.cdr.PlanExtras;
import io.github.bovinemagnet.electrome.market.cdr.PlanFee;
import io.github.bovinemagnet.electrome.market.cdr.PlanIncentive;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One plan, fully expanded.
 *
 * <p>This is the view a reader opens when the comparison row was not enough, so it has to show
 * the tariff itself — and it has to be honest about what the costed figure does and does not
 * include.
 */
class PlanDetailTest {

    private static final DateRange RANGE =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        var start = LocalDateTime.of(2025, 1, 1, 0, 0);
        for (int i = 0; i < 96; i++) {
            readings.add(new IntervalReading(start.plusMinutes(30L * i), Duration.ofMinutes(30),
                    new BigDecimal("0.25"), Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(Charge... charges) {
        var all = new ArrayList<Charge>();
        all.add(new DailySupply(new BigDecimal("123.20")));
        all.addAll(List.of(charges));
        return new Plan("p1", "Test Plan", "AGL", DistributionZone.AUSNET, all, true, null, null);
    }

    /** Deliberately out of clock order, to prove the strip sorts rather than trusts input. */
    private static TimeOfUse threeBands() {
        return new TimeOfUse(List.of(
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("49.54")),
                new Band(0, 6 * 60, DaySelector.ALL, new BigDecimal("4.99")),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal("24.77")),
                new Band(6 * 60, 16 * 60, DaySelector.ALL, new BigDecimal("24.77"))));
    }

    private static PlanDetail detail(Plan plan, PlanExtras extras, List<String> conditions) {
        return PlanDetail.of(
                new CostingEngine().cost(usage(), plan, RANGE), RANGE, conditions, extras, false);
    }

    // -----------------------------------------------------------------
    // The 24-hour strip.
    // -----------------------------------------------------------------

    @Test
    void bandsAreDrawnInWindowOrderRatherThanTheOrderTheyWerePublished() {
        var strips = detail(plan(threeBands()), PlanExtras.none(), List.of()).strips();

        assertThat(strips).singleElement().satisfies(strip ->
                assertThat(strip.segments()).extracting(PlanDetail.Segment::window)
                        .containsExactly("00:00-06:00", "06:00-16:00", "16:00-21:00",
                                "21:00-24:00"));
    }

    @Test
    void theStripSpansExactlyTheWholeDay() {
        var strip = detail(plan(threeBands()), PlanExtras.none(), List.of()).strips().get(0);

        // A strip that does not add to 100% draws a tariff with a gap it does not have.
        var total = strip.segments().stream()
                .map(s -> new BigDecimal(s.widthPercent()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("100.00");
    }

    @Test
    void theDearestBandIsColouredAsThePeakAndTheCheapestAsOffPeak() {
        var strip = detail(plan(threeBands()), PlanExtras.none(), List.of()).strips().get(0);

        // The same hue has to mean the same thing here as on the dashboard, or the detail view
        // teaches a reader a second, contradictory colour language.
        assertThat(strip.segments())
                .filteredOn(s -> "16:00-21:00".equals(s.window()))
                .singleElement()
                .satisfies(s -> assertThat(s.colour())
                        .isEqualTo(BandPalette.cssVar(BandPalette.PEAK)));

        assertThat(strip.segments())
                .filteredOn(s -> "00:00-06:00".equals(s.window()))
                .singleElement()
                .satisfies(s -> assertThat(s.colour())
                        .isEqualTo(BandPalette.cssVar(BandPalette.OFFPEAK)));
    }

    @Test
    void aFlatPlanIsDrawnAsOneBandCoveringTheDay() {
        var strip = detail(plan(new FlatRate(new BigDecimal("31.98"))), PlanExtras.none(),
                List.of()).strips().get(0);

        assertThat(strip.segments()).singleElement().satisfies(s -> {
            assertThat(s.window()).isEqualTo("00:00-24:00");
            assertThat(s.widthPercent()).isEqualTo("100.00");
            assertThat(s.centsPerKWh()).isEqualByComparingTo("31.98");
        });
    }

    @Test
    void weekdayAndWeekendWindowsGetAStripEach() {
        // One strip mixing both would draw overlapping windows on a single 24-hour axis and
        // read as a tariff that charges two rates at once.
        var plan = plan(new TimeOfUse(List.of(
                new Band(0, 24 * 60, DaySelector.WEEKDAYS, new BigDecimal("30.00")),
                new Band(0, 24 * 60, DaySelector.WEEKENDS, new BigDecimal("20.00")))));

        assertThat(detail(plan, PlanExtras.none(), List.of()).strips())
                .extracting(PlanDetail.Strip::label)
                .containsExactly("Weekdays", "Weekends");
    }

    @Test
    void aBandWrappingMidnightIsDrawnAsTwoSegments() {
        // 21:00 to 06:00 is one price but two places on a 24-hour axis.
        var plan = plan(new TimeOfUse(List.of(
                new Band(21 * 60, 6 * 60, DaySelector.ALL, new BigDecimal("18.00")),
                new Band(6 * 60, 21 * 60, DaySelector.ALL, new BigDecimal("40.00")))));

        assertThat(detail(plan, PlanExtras.none(), List.of()).strips().get(0).segments())
                .extracting(PlanDetail.Segment::window)
                .containsExactly("00:00-06:00", "06:00-21:00", "21:00-24:00");
    }

    // -----------------------------------------------------------------
    // The rest of the tariff.
    // -----------------------------------------------------------------

    @Test
    void showsTheDailySupplyChargeAndTheBillItProduces() {
        var detail = detail(plan(threeBands()), PlanExtras.none(), List.of());

        assertThat(detail.supplyCentsPerDay()).isEqualByComparingTo("123.20");
        assertThat(detail.bill().totalRounded()).isGreaterThan(BigDecimal.ZERO);
        assertThat(detail.lines()).isNotEmpty();
    }

    @Test
    void namesTheOtherChargeComponentsAPlanCarries() {
        var detail = detail(
                plan(threeBands(), new SolarFeedIn(new BigDecimal("3.30"))),
                PlanExtras.none(), List.of());

        assertThat(detail.components()).contains("Solar feed-in");
    }

    @Test
    void showsEligibilityInFullRatherThanAsAHoverHint() {
        var conditions = List.of("Must have a net-metered solar PV system installed");
        var detail = detail(plan(threeBands()), PlanExtras.none(), conditions);

        assertThat(detail.conditions()).isEqualTo(conditions);
        assertThat(detail.conditional()).isTrue();
    }

    /**
     * A conditional discount is a different claim from an eligibility requirement.
     *
     * <p>Eligibility says who may sign up. A pay-on-time discount says what the household must
     * keep doing for the number on the screen to stay true. Conflating them would either hide
     * the plan for the wrong reason or show its best case as its only case.
     */
    @Test
    void saysWhenTheTotalDependsOnEarningADiscount() {
        var detail = detail(
                plan(threeBands(),
                        new Discount("Pay on time", DiscountBasis.PERCENTAGE,
                                DiscountScope.USAGE, new BigDecimal("12"),
                                "pay every bill by its due date")),
                PlanExtras.none(), List.of());

        assertThat(detail.assumesConditions()).isTrue();
        assertThat(detail.discountConditions())
                .containsExactly("Pay on time: pay every bill by its due date");
        // Not an eligibility requirement: anyone may sign up to this plan.
        assertThat(detail.conditional()).isFalse();
    }

    @Test
    void aPlanWithNoConditionalDiscountAssumesNothing() {
        var detail = detail(plan(threeBands()), PlanExtras.none(), List.of());
        assertThat(detail.assumesConditions()).isFalse();
        assertThat(detail.discountConditions()).isEmpty();
    }

    // -----------------------------------------------------------------
    // Fees and incentives: shown, and labelled as not costed.
    // -----------------------------------------------------------------

    @Test
    void showsFeesAndIncentives() {
        var extras = new PlanExtras(
                List.of(new PlanFee("MEMBERSHIP", "FIXED", new BigDecimal("10.00"), null,
                        "Monthly membership fee")),
                List.of(new PlanIncentive("$50 credit", "ACCOUNT_CREDIT", "Sign-up credit",
                        "Applied on your first bill")));
        var detail = detail(plan(threeBands()), extras, List.of());

        assertThat(detail.fees()).singleElement()
                .satisfies(fee -> assertThat(fee.describe()).isEqualTo("$10.00"));
        assertThat(detail.incentives()).singleElement()
                .satisfies(i -> assertThat(i.displayName()).isEqualTo("$50 credit"));
        assertThat(detail.hasExtras()).isTrue();
    }

    @Test
    void feesAreLabelledAsExcludedFromTheCostedFigures() {
        // Showing a rate while silently omitting a $10 monthly membership fee misleads exactly
        // where the reader has come looking for detail.
        var detail = detail(plan(threeBands()), PlanExtras.none(), List.of());

        assertThat(detail.excludedNote())
                .containsIgnoringCase("not included")
                .containsIgnoringCase("figures");
    }

    @Test
    void aPlanWithNoPublishedExtrasSaysSoRatherThanShowingAnEmptyPanel() {
        var detail = detail(plan(threeBands()), PlanExtras.none(), List.of());

        assertThat(detail.fees()).isEmpty();
        assertThat(detail.incentives()).isEmpty();
        assertThat(detail.hasExtras()).isFalse();
    }

    @Test
    void nullExtrasAreTreatedAsNonePublished() {
        // A locally defined plan has no CDR response behind it at all.
        var detail = detail(plan(threeBands()), null, null);

        assertThat(detail.hasExtras()).isFalse();
        assertThat(detail.conditions()).isEmpty();
    }

    @Test
    void marksAPlanThatCameFromAFileRatherThanTheHarvest() {
        // A local file wins on identifier collision, and behaves differently as a result.
        var detail = PlanDetail.of(
                new CostingEngine().cost(usage(), plan(threeBands()), RANGE), RANGE,
                List.of(), PlanExtras.none(), true);

        assertThat(detail.local()).isTrue();
    }

    /** A credit that changes across the day is a different offer, so it is named differently. */
    @Test
    void namesAFeedInThatChangesAcrossTheDay() {
        var varying = new SolarFeedIn(List.of(
                Band.parse("00:00", "16:00", DaySelector.ALL, new BigDecimal("1.65")),
                Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("11.00")),
                Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("1.65"))));

        var detail = detail(plan(threeBands(), varying), PlanExtras.none(), List.of());

        assertThat(detail.components()).contains("Solar feed-in, by time of day");
    }
}
