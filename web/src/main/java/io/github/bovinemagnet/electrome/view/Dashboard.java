package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.app.Comparison;
import io.github.bovinemagnet.electrome.app.UsageAnalysis;
import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Everything one render of the dashboard needs, assembled once.
 *
 * <p>The template stays dumb: no arithmetic, no colour decisions, no sorting. Anything a
 * reader could question is computed here where it can be tested.
 */
public record Dashboard(
        DateRange range,
        Comparison comparison,
        Highlights highlights,
        List<PlanRow> rows,
        List<LegendEntry> legend,
        BillBars attribution,
        String loadCurveChart,
        String monthlyChart,
        String heatmapChart,
        UsageAnalysis analysis) {

    public static Dashboard of(Comparison comparison, UsageAnalysis analysis, DateRange range) {
        var rows = new ArrayList<PlanRow>();
        BigDecimal scale = comparison.maxTotal();
        for (var result : comparison.results()) {
            rows.add(new PlanRow(result, BillBars.of(result.bill(), scale)));
        }

        // One legend for the whole page, in first-seen order across every plan, so a colour
        // means the same thing in the table, the ranked bars and the shaded chart.
        var seen = new LinkedHashMap<String, String>();
        for (var row : rows) {
            for (var segment : row.bars().segments()) {
                seen.putIfAbsent(segment.label(), segment.colour());
            }
        }
        var legend = new ArrayList<LegendEntry>();
        seen.forEach((label, colour) -> legend.add(new LegendEntry(label, colour)));

        BillBreakdown best = comparison.best()
                .map(r -> r.bill())
                .orElse(null);

        return new Dashboard(
                range,
                comparison,
                best == null ? null : Highlights.of(best, analysis),
                rows,
                legend,
                best == null ? new BillBars(List.of()) : BillBars.ranked(best),
                Charts.payload(ChartOptions.loadCurve(
                        analysis, best == null ? null : best.plan())),
                Charts.payload(ChartOptions.monthly(analysis.monthlyKWh())),
                Charts.payload(ChartOptions.heatmap(analysis)),
                analysis);
    }

    public boolean empty() {
        return comparison.empty() || highlights == null;
    }
}
