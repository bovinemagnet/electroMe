package io.github.bovinemagnet.electrome.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.app.Comparison;
import io.github.bovinemagnet.electrome.app.LoadCurve;
import io.github.bovinemagnet.electrome.app.PlanResult;
import io.github.bovinemagnet.electrome.app.ScenarioOutcome;
import io.github.bovinemagnet.electrome.app.UsageAnalysis;
import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.ChargeKind;
import io.github.bovinemagnet.electrome.core.cost.ChargeLine;
import io.github.bovinemagnet.electrome.core.cost.Unit;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Structure of the chart option objects, which the render tests only prove are present. */
class InsightChartsTest {

    private static final DateRange RANGE =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    private static Band band(String from, String to, String cents) {
        return Band.parse(from, to, DaySelector.ALL, new BigDecimal(cents));
    }

    private static Plan touPlan() {
        return new Plan("current", "Current tariff", "AGL", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal("123.20")),
                        new TimeOfUse(List.of(
                                band("00:00", "06:00", "4.99"),
                                band("06:00", "11:00", "24.77"),
                                band("11:00", "16:00", "24.77"),
                                band("16:00", "21:00", "49.54"),
                                band("21:00", "24:00", "24.77")))),
                true, null, null);
    }

    private static UsageAnalysis analysis() {
        var curve = new ArrayList<BigDecimal>(Collections.nCopies(48, new BigDecimal("1")));
        curve.set(39, new BigDecimal("1.44"));            // 19:30, the real peak slot
        var monthly = new TreeMap<YearMonth, BigDecimal>();
        monthly.put(YearMonth.of(2025, 12), new BigDecimal("1213.4"));
        monthly.put(YearMonth.of(2026, 1), new BigDecimal("1117.5"));
        var daily = new TreeMap<LocalDate, BigDecimal>();
        daily.put(LocalDate.of(2025, 1, 1), new BigDecimal("24"));
        daily.put(LocalDate.of(2025, 1, 2), new BigDecimal("30"));
        var row = Collections.nCopies(48, new BigDecimal("0.5"));
        return new UsageAnalysis(RANGE, new BigDecimal("54"),
                List.of(new LoadCurve("Overall", curve), new LoadCurve("Summer", curve)),
                monthly, List.of(row, row),
                List.of(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2)), daily);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> series(Map<String, Object> option) {
        return (List<Map<String, Object>>) option.get("series");
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(Object axisData) {
        return (List<String>) axisData;
    }

    // ---------- load curve ----------

    @Test
    void loadCurveHasOneSeriesPerCurveOverFortyEightCategories() {
        var option = ChartOptions.loadCurve(analysis(), touPlan());
        assertThat(series(option)).hasSize(2);
        assertThat(series(option)).extracting(s -> s.get("name")).containsExactly("Overall", "Summer");

        @SuppressWarnings("unchecked")
        var xAxis = (Map<String, Object>) option.get("xAxis");
        var categories = (List<?>) xAxis.get("data");
        assertThat(categories).hasSize(48);
        assertThat(categories.get(0)).isEqualTo("00:00");
        assertThat(categories.get(32)).isEqualTo("16:00");
        assertThat(categories.get(47)).isEqualTo("23:30");
    }

    @Test
    void onlyTheOverallCurveIsSelectedInitially() {
        // The seasonal and weekday curves would make the chart unreadable if all were on.
        var option = ChartOptions.loadCurve(analysis(), touPlan());
        @SuppressWarnings("unchecked")
        var legend = (Map<String, Object>) option.get("legend");
        @SuppressWarnings("unchecked")
        var selected = (Map<String, Object>) legend.get("selected");
        assertThat(selected).containsEntry("Overall", true).containsEntry("Summer", false);
    }

    @Test
    void tariffBandsAreShadedBehindTheOverallCurveOnly() {
        var option = ChartOptions.loadCurve(analysis(), touPlan());
        var overall = series(option).get(0);
        var summer = series(option).get(1);

        @SuppressWarnings("unchecked")
        var markArea = (Map<String, Object>) overall.get("markArea");
        assertThat(markArea).isNotNull();
        assertThat((List<?>) markArea.get("data")).hasSize(5);
        assertThat(summer).doesNotContainKey("markArea");
    }

    @Test
    void theDearestBandIsShadedMoreStronglyThanTheRest() {
        var option = ChartOptions.loadCurve(analysis(), touPlan());
        @SuppressWarnings("unchecked")
        var markArea = (Map<String, Object>) series(option).get(0).get("markArea");
        @SuppressWarnings("unchecked")
        var areas = (List<List<Map<String, Object>>>) markArea.get("data");

        var opacities = new ArrayList<Object>();
        for (var area : areas) {
            @SuppressWarnings("unchecked")
            var style = (Map<String, Object>) area.get(0).get("itemStyle");
            if (BandPalette.chartRef(BandPalette.PEAK).equals(style.get("color"))) {
                opacities.add(style.get("opacity"));
            }
        }
        assertThat(opacities).containsExactly(0.15);
    }

    @Test
    void thePeakIsMarkedAtItsActualSlot() {
        var option = ChartOptions.loadCurve(analysis(), touPlan());
        @SuppressWarnings("unchecked")
        var markPoint = (Map<String, Object>) series(option).get(0).get("markPoint");
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) markPoint.get("data");
        assertThat((List<?>) data.get(0).get("coord")).first().isEqualTo("19:30");
    }

    @Test
    void aPlanWithNoBandsProducesAnUnshadedButValidChart() {
        var flat = new Plan("f", "Flat", "R", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal("100"))), true, null, null);
        var option = ChartOptions.loadCurve(analysis(), flat);
        @SuppressWarnings("unchecked")
        var markArea = (Map<String, Object>) series(option).get(0).get("markArea");
        assertThat((List<?>) markArea.get("data")).isEmpty();
    }

    @Test
    void aNullPlanDoesNotBreakTheChart() {
        assertThat(series(ChartOptions.loadCurve(analysis(), null))).isNotEmpty();
    }

    // ---------- heatmap ----------

    @Test
    void heatmapEmitsOneEntryPerDayPerSlotWithAScale() {
        var option = ChartOptions.heatmap(analysis());
        assertThat((List<?>) series(option).get(0).get("data")).hasSize(96);
        assertThat(option).containsKey("visualMap");
    }

    // ---------- monthly ----------

    @Test
    void monthlyChartLabelsMonthsReadably() {
        var option = ChartOptions.monthly(analysis().monthlyKWh());
        @SuppressWarnings("unchecked")
        var xAxis = (Map<String, Object>) option.get("xAxis");
        assertThat(labels(xAxis.get("data"))).containsExactly("Dec 25", "Jan 26");
        assertThat((List<?>) series(option).get(0).get("data")).hasSize(2);
    }

    // ---------- scenario savings ----------

    @Test
    void scenarioChartOrdersSmallestSavingFirstSoTheBiggestBarSitsOnTop() {
        var outcomes = List.of(
                outcome("Shift load", "319.86"),
                outcome("Add solar", "1049.67"),
                outcome("Add battery", "933.05"));
        var option = ChartOptions.scenarioSavings(outcomes);
        @SuppressWarnings("unchecked")
        var yAxis = (Map<String, Object>) option.get("yAxis");
        // A horizontal bar chart draws the first category at the bottom.
        assertThat(labels(yAxis.get("data")))
                .containsExactly("Shift load", "Add battery", "Add solar");
    }

    private static ScenarioOutcome outcome(String label, String saving) {
        return new ScenarioOutcome(label, new Comparison(RANGE, List.of()),
                new BigDecimal("2852.80"), new BigDecimal("2000"), new BigDecimal(saving));
    }

    // ---------- bars and payload ----------

    private static BillBreakdown bill() {
        var lines = List.of(
                new ChargeLine("Daily supply charge", ChargeKind.SUPPLY, new BigDecimal("365"),
                        Unit.DAY, new BigDecimal("123.20"), new BigDecimal("449.68")),
                new ChargeLine("Usage 16:00-21:00", ChargeKind.USAGE, new BigDecimal("2393"),
                        Unit.KWH, new BigDecimal("49.54"), new BigDecimal("1185.57")),
                new ChargeLine("Usage 00:00-06:00", ChargeKind.USAGE, new BigDecimal("1454"),
                        Unit.KWH, new BigDecimal("4.99"), new BigDecimal("72.56")),
                new ChargeLine("Solar feed-in credit", ChargeKind.FEED_IN, BigDecimal.ZERO,
                        Unit.KWH, new BigDecimal("3.3"), BigDecimal.ZERO));
        return new BillBreakdown(touPlan(), RANGE, 365, lines,
                new BigDecimal("1707.81"), List.of());
    }

    @Test
    void rankedBarsAreOrderedDearestFirstAndScaledToTheLeader() {
        var ranked = BillBars.ranked(bill());
        assertThat(ranked.segments()).extracting(BillBars.Segment::label)
                .containsExactly("Usage 16:00-21:00", "Daily supply charge", "Usage 00:00-06:00");
        assertThat(ranked.segments().get(0).widthPercent()).isEqualTo("100.00");
        // Share is of the whole bill, not of the leader.
        assertThat(new BigDecimal(ranked.segments().get(0).sharePercent()))
                .isCloseTo(new BigDecimal("69.42"), org.assertj.core.data.Offset.offset(
                        new BigDecimal("0.05")));
    }

    @Test
    void zeroValuedLinesAreOmittedFromTheBars() {
        assertThat(BillBars.ranked(bill()).segments())
                .noneMatch(s -> s.label().equals("Solar feed-in credit"));
    }

    @Test
    void barsScaleAgainstTheSuppliedTotalSoRowsShareOneScale() {
        var bars = BillBars.of(bill(), new BigDecimal("3415.62"));   // twice the bill
        assertThat(bars.segments().get(1).widthPercent()).isEqualTo("34.71");
    }

    @Test
    void emptyScaleYieldsNoSegmentsRatherThanDividingByZero() {
        assertThat(BillBars.of(bill(), BigDecimal.ZERO).segments()).isEmpty();
    }

    @Test
    void payloadSerialisesToJson() {
        String json = Charts.payload(ChartOptions.monthly(analysis().monthlyKWh()));
        assertThat(json).startsWith("{").contains("\"series\"").contains("Dec 25");
    }

    // ---------- highlights and formatting ----------

    @Test
    void highlightsKnowWhenThereIsNoPeakWindow() {
        var flat = new Plan("f", "Flat", "R", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal("100"))), true, null, null);
        var flatBill = new BillBreakdown(flat, RANGE, 2, List.of(), BigDecimal.TEN, List.of());
        assertThat(Highlights.of(flatBill, analysis()).hasPeakWindow()).isFalse();
        assertThat(Highlights.of(bill(), analysis()).hasPeakWindow()).isTrue();
    }

    @Test
    void highlightsRenderTheWindowInEverydayClockTerms() {
        assertThat(Highlights.of(bill(), analysis()).peakWindow()).isEqualTo("4pm–9pm");
    }

    @Test
    void countGroupsThousands() {
        assertThat(Money.count(35036)).isEqualTo("35,036");
        assertThat(Money.count(null)).isEmpty();
    }
}
