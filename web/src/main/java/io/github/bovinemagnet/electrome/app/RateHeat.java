package io.github.bovinemagnet.electrome.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each published rate sits within its own column, in four steps.
 *
 * <p>Shaded per column rather than across the table, because the columns are not comparable
 * with each other: a daily supply charge of 128 cents and a peak rate of 47 cents are different
 * units, and one ramp over both would say the supply charge is the expensive part of every plan
 * on the page.
 *
 * <p>Neutral shading, no hue. The band colours already mean something specific everywhere else
 * in the application — red is the evening peak window, amber the middle of the day — and tinting
 * an "Evening peak" cell red for being expensive would give red two jobs in one table. Darkness
 * alone carries dearness here, which also leaves the diverging colour of the value column as the
 * only coloured thing in the row, and so the thing the eye goes to.
 *
 * <p>Ranked by position rather than by distance, so one outlier priced at four times the market
 * cannot flatten every other plan into the same shade. The ranks are spread across the four
 * shades endpoints included, so the cheapest rate in a column is always the lightest and the
 * dearest always the darkest.
 */
public record RateHeat(Map<String, Map<PlanMatrix.Component, Integer>> steps) {

    /** Four steps: enough to rank a column at a glance, few enough to stay distinguishable. */
    public static final int STEPS = 4;

    private static final RateHeat NONE = new RateHeat(Map.of());

    public RateHeat {
        steps = Map.copyOf(steps);
    }

    public static RateHeat none() {
        return NONE;
    }

    public static RateHeat over(List<MarketEntry> entries, List<PlanMatrix.Component> columns) {
        if (entries.isEmpty() || columns.isEmpty()) {
            return NONE;
        }
        var steps = new HashMap<String, Map<PlanMatrix.Component, Integer>>();
        for (var entry : entries) {
            steps.put(entry.id(), new HashMap<>());
        }

        for (var column : columns) {
            var priced = new ArrayList<MarketEntry>();
            for (var entry : entries) {
                if (entry.rates().has(column)) {
                    priced.add(entry);
                }
            }
            if (priced.size() < 2) {
                continue;
            }
            // Solar feed-in is a credit: there, the biggest number is the best one.
            boolean higherIsBetter = column == PlanMatrix.Component.FEED_IN;
            priced.sort((left, right) -> {
                int order = left.rates().rate(column).compareTo(right.rates().rate(column));
                return higherIsBetter ? -order : order;
            });

            for (int position = 0; position < priced.size(); position++) {
                steps.get(priced.get(position).id())
                        .put(column, stepAt(position, priced.size()));
            }
        }

        var frozen = new HashMap<String, Map<PlanMatrix.Component, Integer>>();
        steps.forEach((id, byColumn) -> frozen.put(id, Map.copyOf(byColumn)));
        return new RateHeat(frozen);
    }

    /** 1 for the cheapest quarter of the column, 4 for the dearest. */
    public int stepFor(MarketEntry entry, PlanMatrix.Component column) {
        var byColumn = steps.get(entry.id());
        if (byColumn == null) {
            return 0;
        }
        return byColumn.getOrDefault(column, 0);
    }

    /** The class the cell carries, so the template picks no shades of its own. */
    public String classFor(MarketEntry entry, PlanMatrix.Component column) {
        int step = stepFor(entry, column);
        return step == 0 ? "" : "heat-" + step;
    }

    /**
     * Rank spread across the four shades, endpoints included.
     *
     * <p>Bucketing by quartile instead would leave the dearest of two plans a shade short of the
     * darkest, and a scale whose extremes are never reached does not read as a scale.
     */
    private static int stepAt(int position, int total) {
        if (total <= 1) {
            return 1;
        }
        return position * (STEPS - 1) / (total - 1) + 1;
    }
}
