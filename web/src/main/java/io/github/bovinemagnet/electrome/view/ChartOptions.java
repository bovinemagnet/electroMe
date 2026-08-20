package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.app.LoadCurve;
import io.github.bovinemagnet.electrome.app.UsageAnalysis;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;

/**
 * Builds complete ECharts option objects.
 *
 * <p>Written in Java rather than JavaScript so chart structure is unit tested. Colours are
 * emitted as "@token" references and resolved against the stylesheet in the browser.
 */
public final class ChartOptions {

    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

    private ChartOptions() {}

    /** Half-hour labels, "00:00" through "23:30". */
    static List<String> halfHourLabels() {
        var labels = new ArrayList<String>(48);
        for (int slot = 0; slot < 48; slot++) {
            labels.add(Money.slotTime(slot));
        }
        return labels;
    }

    /**
     * Average load across the day with the tariff's windows shaded behind it.
     *
     * <p>The shading is the point. A load curve says when energy is used; the bands say
     * whether that timing is expensive. Split across two charts, the reader has to do the
     * overlay themselves, which is the work this application exists to do for them.
     */
    public static Map<String, Object> loadCurve(UsageAnalysis analysis, Plan plan) {
        var series = new ArrayList<Map<String, Object>>();
        var curves = analysis.curves();

        for (int i = 0; i < curves.size(); i++) {
            LoadCurve curve = curves.get(i);
            boolean overall = i == 0;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", curve.label());
            entry.put("type", "line");
            entry.put("smooth", 0.25);
            entry.put("showSymbol", false);
            entry.put("z", overall ? 5 : 3);
            entry.put("lineStyle", Map.of("width", overall ? 2.6 : 1.3));
            entry.put("itemStyle", Map.of("color", overall ? BandPalette.chartRef("accent") : seriesColour(i)));
            if (!overall) {
                // Only the overall curve is shown at first; the rest are one legend click away.
                entry.put("lineStyle", Map.of("width", 1.3, "opacity", 0.85));
            }
            if (overall) {
                entry.put("areaStyle", Map.of("opacity", 0.10));
                entry.put("markArea", bandShading(plan));
                entry.put("markPoint", peakMarker(curve));
            }
            entry.put("data", curve.averageKWByHalfHour());
            series.add(entry);
        }

        var selected = new LinkedHashMap<String, Object>();
        for (int i = 0; i < curves.size(); i++) {
            selected.put(curves.get(i).label(), i == 0);
        }

        var option = new LinkedHashMap<String, Object>();
        option.put("tooltip", Map.of("trigger", "axis", "valueFormatter", "@kW"));
        option.put("legend", Map.of("type", "scroll", "bottom", 0, "selected", selected));
        option.put("grid", Map.of("left", 52, "right", 20, "top", 46, "bottom", 54));
        option.put("xAxis", Map.of(
                "type", "category", "boundaryGap", false, "data", halfHourLabels(),
                "axisLabel", Map.of("interval", 5)));
        option.put("yAxis", Map.of("type", "value", "name", "Average kW", "nameGap", 34,
                "nameLocation", "middle", "nameTextStyle", Map.of("fontSize", 11)));
        option.put("series", series);
        return option;
    }

    /**
     * Every selected plan's rate through the day, with the household's load curve behind it.
     *
     * <p>The same overlay the dashboard gives for one plan, extended to several. This is where a
     * reader sees that one plan's cheap window sits exactly where their consumption is highest,
     * which is the whole reason two households on the same tariff pay different effective rates.
     *
     * <p>Rates on the left axis, load on the right: they share an x-axis and nothing else, and
     * plotting them on one scale would make the comparison meaningless.
     */
    public static Map<String, Object> rateShapes(
            List<Plan> plans, UsageData usage, DateRange range) {

        var series = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < plans.size(); i++) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", plans.get(i).name());
            entry.put("type", "line");
            entry.put("step", "end");
            entry.put("showSymbol", false);
            entry.put("yAxisIndex", 0);
            entry.put("lineStyle", Map.of("width", 2.2));
            entry.put("itemStyle", Map.of("color", seriesColour(i)));
            entry.put("data", ratesByHalfHour(plans.get(i)));
            series.add(entry);
        }

        var load = new LinkedHashMap<String, Object>();
        load.put("name", "Your average draw");
        load.put("type", "line");
        load.put("smooth", 0.25);
        load.put("showSymbol", false);
        load.put("yAxisIndex", 1);
        load.put("z", 1);
        load.put("lineStyle", Map.of("width", 1.4, "opacity", 0.55));
        load.put("areaStyle", Map.of("opacity", 0.10));
        load.put("itemStyle", Map.of("color", BandPalette.chartRef("accent")));
        load.put("data", averageDraw(usage, range));
        series.add(load);

        var option = new LinkedHashMap<String, Object>();
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("legend", Map.of("type", "scroll", "bottom", 0));
        option.put("grid", Map.of("left", 56, "right", 56, "top", 30, "bottom", 54));
        option.put("xAxis", Map.of(
                "type", "category", "boundaryGap", false, "data", halfHourLabels(),
                "axisLabel", Map.of("interval", 5)));
        option.put("yAxis", List.of(
                Map.of("type", "value", "name", "c/kWh", "nameGap", 38,
                        "nameLocation", "middle", "nameTextStyle", Map.of("fontSize", 11)),
                Map.of("type", "value", "name", "kW", "nameGap", 38, "position", "right",
                        "nameLocation", "middle", "splitLine", Map.of("show", false),
                        "nameTextStyle", Map.of("fontSize", 11))));
        option.put("series", series);
        return option;
    }

    /**
     * A plan's rate in each half hour.
     *
     * <p>A plan with no time-of-use bands charges one rate all day, which is exactly what a
     * flat line across the axis says. A slot no band covers is left null so the line breaks
     * rather than dropping to zero and implying free electricity.
     */
    private static List<BigDecimal> ratesByHalfHour(Plan plan) {
        var bands = BandPalette.timeOfUseBands(plan);
        var rates = new ArrayList<BigDecimal>(48);

        if (bands.isEmpty()) {
            BigDecimal flat = null;
            for (var charge : plan.charges()) {
                if (charge instanceof io.github.bovinemagnet.electrome.core.tariff.FlatRate rate) {
                    flat = rate.centsPerKWh();
                } else if (charge
                        instanceof io.github.bovinemagnet.electrome.core.tariff.Tiered tiered) {
                    // The first block is what most households spend most of their time in.
                    flat = tiered.tiers().get(0).centsPerKWh();
                }
            }
            for (int slot = 0; slot < 48; slot++) {
                rates.add(flat);
            }
            return rates;
        }

        for (int slot = 0; slot < 48; slot++) {
            int minute = slot * 30;
            BigDecimal rate = null;
            for (var band : bands) {
                if (band.matchesTime(minute)) {
                    rate = band.centsPerKWh();
                    break;
                }
            }
            rates.add(rate);
        }
        return rates;
    }

    /** Mean power draw in each half hour across the window, in kW. */
    private static List<BigDecimal> averageDraw(UsageData usage, DateRange range) {
        var totals = new BigDecimal[48];
        var counts = new int[48];
        java.util.Arrays.fill(totals, BigDecimal.ZERO);

        for (var reading : usage.consumption().slice(range).readings()) {
            int slot = Math.min(47, reading.minuteOfDay() / 30);
            totals[slot] = totals[slot].add(reading.averageKW());
            counts[slot]++;
        }

        var curve = new ArrayList<BigDecimal>(48);
        for (int slot = 0; slot < 48; slot++) {
            curve.add(counts[slot] == 0
                    ? BigDecimal.ZERO
                    : totals[slot].divide(BigDecimal.valueOf(counts[slot]),
                            java.math.MathContext.DECIMAL64)
                            .setScale(3, java.math.RoundingMode.HALF_UP));
        }
        return curve;
    }

    private static String seriesColour(int index) {
        var tokens = List.of("shoulder", "midday", "offpeak", "peak", "supply");
        return BandPalette.chartRef(tokens.get(index % tokens.size()));
    }

    /** Shaded regions for each tariff band, labelled with its rate. */
    private static Map<String, Object> bandShading(Plan plan) {
        var areas = new ArrayList<List<Map<String, Object>>>();
        for (var band : BandPalette.timeOfUseBands(plan)) {
            // A midnight-wrapping band is shaded from its start to the end of the day; the
            // remainder sits against the chart's left edge and reads as contiguous.
            int fromSlot = band.fromMinuteOfDay() / 30;
            int toSlot = band.wrapsMidnight() ? 47 : Math.min(47, band.toMinuteOfDay() / 30 - 1);
            if (toSlot < fromSlot) {
                continue;
            }
            var token = bandToken(plan, band);
            var start = new LinkedHashMap<String, Object>();
            start.put("xAxis", Money.slotTime(fromSlot));
            start.put("name", Money.cents(band.centsPerKWh()));
            start.put("itemStyle", Map.of("color", BandPalette.chartRef(token), "opacity",
                    BandPalette.PEAK.equals(token) ? 0.15 : 0.07));
            var end = new LinkedHashMap<String, Object>();
            end.put("xAxis", Money.slotTime(toSlot));
            areas.add(List.of(start, end));
        }
        return Map.of("silent", true,
                "label", Map.of("show", true, "position", "insideTop", "fontSize", 10,
                        "distance", 4, "color", BandPalette.chartRef("muted")),
                "data", areas);
    }

    /** Reuses the bill-line colouring rules so a band is the same hue everywhere. */
    private static String bandToken(Plan plan, Band band) {
        var line = new io.github.bovinemagnet.electrome.core.cost.ChargeLine(
                "Usage " + band.describe(),
                io.github.bovinemagnet.electrome.core.cost.ChargeKind.USAGE,
                BigDecimal.ZERO,
                io.github.bovinemagnet.electrome.core.cost.Unit.KWH,
                band.centsPerKWh(),
                BigDecimal.ZERO);
        return BandPalette.tokenFor(plan, line);
    }

    private static Map<String, Object> peakMarker(LoadCurve curve) {
        return Map.of(
                "symbol", "circle", "symbolSize", 9,
                "itemStyle", Map.of("color", BandPalette.chartRef("peak")),
                "label", Map.of("show", true, "position", "top", "fontSize", 11,
                        "fontWeight", "bold", "color", BandPalette.chartRef("peak"), "formatter", "{c} kW"),
                "data", List.of(Map.of(
                        "coord", List.of(Money.slotTime(curve.peakSlot()),
                                curve.peakKW().setScale(2, java.math.RoundingMode.HALF_UP)),
                        "value", curve.peakKW().setScale(2, java.math.RoundingMode.HALF_UP))));
    }

    /**
     * Consumption as day against half hour, at full resolution.
     *
     * <p>Not downsampled: two years is 35,040 cells, which renders fine, and the visible
     * texture of daily routine and seasonal shift is exactly what averaging would destroy.
     */
    public static Map<String, Object> heatmap(UsageAnalysis analysis) {
        var data = new ArrayList<List<Object>>();
        var values = new ArrayList<BigDecimal>();
        var rows = analysis.heatmap();
        for (int day = 0; day < rows.size(); day++) {
            var row = rows.get(day);
            for (int slot = 0; slot < row.size(); slot++) {
                data.add(List.of(day, slot, row.get(slot)));
                values.add(row.get(slot));
            }
        }
        // Scale to the 99th percentile, not the maximum. A handful of extreme half hours
        // would otherwise compress every ordinary day into the palest two shades and hide
        // exactly the daily and seasonal texture this chart exists to show.
        BigDecimal max = percentile(values, 0.99);
        var dates = new ArrayList<String>();
        for (var date : analysis.heatmapDates()) {
            dates.add(date.toString());
        }

        var option = new LinkedHashMap<String, Object>();
        option.put("tooltip", Map.of("position", "top"));
        option.put("grid", Map.of("left", 56, "right", 24, "top", 12, "bottom", 62));
        option.put("xAxis", Map.of("type", "category", "data", dates,
                "splitArea", Map.of("show", false),
                "axisLabel", Map.of("interval", Math.max(1, dates.size() / 10))));
        option.put("yAxis", Map.of("type", "category", "data", halfHourLabels(),
                "splitArea", Map.of("show", false),
                "axisLabel", Map.of("interval", 5)));
        option.put("visualMap", Map.of(
                "min", 0, "max", max, "calculable", true, "orient", "horizontal",
                "left", "center", "bottom", 6, "itemHeight", 90,
                "textStyle", Map.of("fontSize", 10),
                "inRange", Map.of("color",
                        List.of("#f7fbff", "#c9dff0", "#9ecae1", "#6baed6",
                                "#4292c6", "#2171b5", "#08519c", "#08306b"))));
        option.put("series", List.of(Map.of(
                "type", "heatmap", "data", data, "progressive", 6000,
                "emphasis", Map.of("itemStyle", Map.of("borderColor", BandPalette.chartRef("fg"), "borderWidth", 1)))));
        return option;
    }

    /** The value below which the given fraction of readings fall. */
    static BigDecimal percentile(List<BigDecimal> values, double fraction) {
        if (values.isEmpty()) {
            return BigDecimal.ONE;
        }
        var sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        int index = (int) Math.min(sorted.size() - 1L, Math.round(fraction * (sorted.size() - 1)));
        var value = sorted.get(index);
        return value.signum() == 0 ? BigDecimal.ONE : value;
    }

    /** Annual saving per scenario, largest first. */
    public static Map<String, Object> scenarioSavings(
            List<io.github.bovinemagnet.electrome.app.ScenarioOutcome> outcomes) {
        var sorted = new ArrayList<>(outcomes);
        sorted.sort(java.util.Comparator.comparing(
                io.github.bovinemagnet.electrome.app.ScenarioOutcome::saving));

        var labels = new ArrayList<String>();
        var values = new ArrayList<BigDecimal>();
        for (var outcome : sorted) {
            labels.add(outcome.label());
            values.add(outcome.saving());
        }

        var option = new LinkedHashMap<String, Object>();
        option.put("tooltip", Map.of("trigger", "axis",
                "axisPointer", Map.of("type", "shadow"), "valueFormatter", "@dollars"));
        option.put("grid", Map.of("left", 240, "right", 60, "top", 12, "bottom", 36));
        option.put("xAxis", Map.of("type", "value", "name", "Saving a year",
                "nameLocation", "middle", "nameGap", 26,
                "nameTextStyle", Map.of("fontSize", 11)));
        option.put("yAxis", Map.of("type", "category", "data", labels,
                "axisLabel", Map.of("width", 228, "overflow", "break", "fontSize", 11)));
        option.put("series", List.of(Map.of(
                "type", "bar", "data", values, "barMaxWidth", 26,
                "label", Map.of("show", true, "position", "right",
                        "formatter", "@dollarLabel", "fontSize", 11),
                "itemStyle", Map.of("color", BandPalette.chartRef("offpeak"), "borderRadius", List.of(0, 3, 3, 0)))));
        return option;
    }

    /** Monthly consumption, for year-on-year comparison. */
    public static Map<String, Object> monthly(SortedMap<YearMonth, BigDecimal> monthlyKWh) {
        var labels = new ArrayList<String>();
        var values = new ArrayList<BigDecimal>();
        for (var entry : monthlyKWh.entrySet()) {
            labels.add(entry.getKey().atDay(1).format(MONTH));
            values.add(entry.getValue().setScale(1, java.math.RoundingMode.HALF_UP));
        }

        var option = new LinkedHashMap<String, Object>();
        option.put("tooltip", Map.of("trigger", "axis", "valueFormatter", "@kWh"));
        option.put("grid", Map.of("left", 56, "right", 20, "top", 16, "bottom", 46));
        option.put("xAxis", Map.of("type", "category", "data", labels,
                "axisLabel", Map.of("rotate", labels.size() > 14 ? 45 : 0, "fontSize", 10)));
        option.put("yAxis", Map.of("type", "value", "name", "kWh", "nameTextStyle",
                Map.of("fontSize", 11)));
        option.put("series", List.of(Map.of(
                "type", "bar", "data", values, "barMaxWidth", 34,
                "itemStyle", Map.of("color", BandPalette.chartRef("accent"), "borderRadius", List.of(3, 3, 0, 0)))));
        return option;
    }
}
