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
        List<PlanRow> shortlist,
        List<LegendEntry> legend,
        BillBars attribution,
        String loadCurveChart,
        String monthlyChart,
        String heatmapChart,
        UsageAnalysis analysis) {

    /**
     * How many rows the shortlist may hold.
     *
     * <p>A household usually keeps two or three plan files, but nothing stops it keeping two
     * hundred, and a shortlist that grows with them is the table this panel replaced.
     */
    public static final int SHORTLIST_LIMIT = 6;

    public static Dashboard of(Comparison comparison, UsageAnalysis analysis, DateRange range) {
        return of(comparison, analysis, range, java.util.Map.of(), java.util.Set.of());
    }

    public static Dashboard of(Comparison comparison, UsageAnalysis analysis, DateRange range,
            java.util.Map<String, List<String>> conditions) {
        return of(comparison, analysis, range, conditions, java.util.Set.of());
    }

    /**
     * @param localPlanIds plans defined as files rather than harvested: the household's own
     *     tariff and the regulated benchmark, which are what the rest is compared against
     */
    public static Dashboard of(Comparison comparison, UsageAnalysis analysis, DateRange range,
            java.util.Map<String, List<String>> conditions, java.util.Set<String> localPlanIds) {
        var rows = new ArrayList<PlanRow>();
        BigDecimal scale = comparison.maxTotal();
        for (var result : comparison.results()) {
            rows.add(new PlanRow(result, BillBars.of(result.bill(), scale),
                    conditions.get(result.bill().plan().id())));
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
                shortlist(rows, conditions, localPlanIds),
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

    /** How many plans the shortlist left for the browser to show. */
    public int hiddenFromShortlist() {
        return rows.size() - shortlist.size();
    }

    public boolean editedDown() {
        return hiddenFromShortlist() > 0;
    }

    /** The shortlist drawn straight from a comparison, for callers that have no analysis. */
    public static List<PlanRow> shortlistOf(Comparison comparison,
            java.util.Map<String, List<String>> conditions,
            java.util.Set<String> localPlanIds) {
        return shortlist(rowsOf(comparison, conditions), conditions, localPlanIds);
    }

    /**
     * The few plans the dashboard answers with.
     *
     * <p>The dashboard's job is the answer, not the catalogue: your own tariff, the best you
     * could actually switch to, the best that exists at all, and the regulated benchmark. The
     * full table is not deleted — it moves to the screen built for browsing.
     *
     * <p>Kept in cost order, so the shortlist reads the same way as the table it came from.
     */
    private static List<PlanRow> shortlist(List<PlanRow> rows,
            java.util.Map<String, List<String>> conditions,
            java.util.Set<String> localPlanIds) {

        var chosen = new java.util.LinkedHashSet<String>();

        // The cheapest overall, even when it needs equipment: omitting it would hide the best
        // number there is. Its requirement is named beside it instead.
        if (!rows.isEmpty()) {
            chosen.add(planId(rows.get(0)));
        }
        // The cheapest a household can actually sign up to, which is the figure it can act on.
        for (var row : rows) {
            if (!row.conditional()) {
                chosen.add(planId(row));
                break;
            }
        }
        for (var row : rows) {
            if (localPlanIds.contains(planId(row))) {
                chosen.add(planId(row));
            }
        }

        // Rows arrive cheapest first, so truncating drops the dear end — which is the end a
        // reader is least likely to act on, and the end the browser is there to show.
        var shortlist = new ArrayList<PlanRow>();
        for (var row : rows) {
            if (chosen.contains(planId(row))) {
                shortlist.add(row);
                if (shortlist.size() == SHORTLIST_LIMIT) {
                    break;
                }
            }
        }
        return List.copyOf(shortlist);
    }

    private static String planId(PlanRow row) {
        return row.result().bill().plan().id();
    }

    private static List<PlanRow> rowsOf(
            Comparison comparison, java.util.Map<String, List<String>> conditions) {
        var rows = new ArrayList<PlanRow>();
        BigDecimal scale = comparison.maxTotal();
        for (var result : comparison.results()) {
            rows.add(new PlanRow(result, BillBars.of(result.bill(), scale),
                    conditions.get(result.bill().plan().id())));
        }
        return rows;
    }
}
