package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.ingest.PlanLibrary;
import io.github.bovinemagnet.electrome.market.cdr.PlanExtras;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The market screen: every published plan, what it charges, what it would cost, and whether it
 * is already held.
 *
 * <p>Costed from what the register publishes now, never from a file already on disk. The two
 * diverge — that is the whole reason the status column exists — and a screen that showed a plan
 * as out of date and then priced the out-of-date copy beside it would be arguing with itself.
 *
 * @param matched how many plans met the criteria, before the limit
 * @param total how many were harvested at all
 * @param retailers every retailer publishing into this network, for the filter
 * @param scale the diverging scale the value column is drawn on, anchored on the household's
 *     own plan
 * @param heat where each published rate sits within its own column
 * @param marketLinkedHidden how many plans this page is leaving out because their rates follow
 *     the wholesale market rather than a published figure
 */
public record MarketPage(
        MarketQuery query,
        List<MarketEntry> entries,
        List<PlanMatrix.Component> rateColumns,
        List<String> retailers,
        ValueScale scale,
        RateHeat heat,
        int matched,
        int total,
        int heldCount,
        int staleCount,
        int marketLinkedHidden) {

    public MarketPage {
        entries = List.copyOf(entries);
        rateColumns = List.copyOf(rateColumns);
        retailers = List.copyOf(retailers);
    }

    /**
     * Builds the page from what has been harvested, what the plans directory holds, and what
     * each plan would have cost.
     *
     * <p>Pure: no file access, no network and no clock. The directory has already been read into
     * {@code previews} and the costing into {@code comparison}, which is what lets this be
     * tested against a handful of plans rather than against somebody's real plans directory.
     *
     * @param comparison every published plan costed over the window, or null when no usage is
     *     loaded, in which case the screen still lists what plans charge
     */
    public static MarketPage of(
            List<Plan> harvested,
            Map<String, PlanLibrary.Saved> previews,
            Map<String, List<String>> conditions,
            Map<String, PlanExtras> extras,
            Comparison comparison,
            MarketQuery query) {

        var retailers = new TreeSet<String>();
        for (var plan : harvested) {
            retailers.add(plan.retailer());
        }

        var costs = costsById(comparison);

        var matching = harvested.stream()
                .filter(query::matches)
                .map(plan -> entry(plan, previews, conditions, extras, costs))
                .filter(entry -> !query.hideHeld() || !entry.held())
                .toList();

        // Counted before they are dropped, so the page can offer to show what it is holding
        // back rather than quietly returning a shorter list.
        int marketLinkedHidden = query.includeMarketLinked()
                ? 0
                : (int) matching.stream().filter(MarketEntry::marketLinked).count();

        var entries = matching.stream()
                .filter(entry -> query.includeMarketLinked() || !entry.marketLinked())
                .sorted(order(query.sort()))
                .toList();

        var shown = entries.size() > query.limit()
                ? entries.subList(0, query.limit())
                : entries;

        var columns = PlanRates.columnsFor(shown.stream().map(MarketEntry::rates).toList());

        return new MarketPage(
                query,
                shown,
                columns,
                List.copyOf(retailers),
                scaleOver(shown, comparison),
                RateHeat.over(shown, columns),
                entries.size(),
                harvested.size(),
                heldCount(harvested, previews),
                staleCount(harvested, previews),
                marketLinkedHidden);
    }

    private static Map<String, PlanResult> costsById(Comparison comparison) {
        if (comparison == null) {
            return Map.of();
        }
        var byId = new HashMap<String, PlanResult>();
        for (var result : comparison.results()) {
            byId.put(result.bill().plan().id(), result);
        }
        return Map.copyOf(byId);
    }

    /**
     * The scale the value column is drawn on, built only from the plans on screen.
     *
     * <p>Anchored on the household's own plan, so the middle of the scale is what they pay now
     * rather than the cheapest row, which would move every time a filter changed.
     */
    private static ValueScale scaleOver(List<MarketEntry> shown, Comparison comparison) {
        if (comparison == null || comparison.baselinePlanId() == null) {
            return ValueScale.none();
        }
        var anchor = comparison.baseline()
                .map(PlanResult::planName)
                .orElse("your plan");
        return ValueScale.over(
                shown.stream().map(MarketEntry::differenceFromBaseline).toList(), anchor);
    }

    private static MarketEntry entry(
            Plan plan,
            Map<String, PlanLibrary.Saved> previews,
            Map<String, List<String>> conditions,
            Map<String, PlanExtras> extras,
            Map<String, PlanResult> costs) {
        var preview = previews.get(plan.id());
        var cost = costs.get(plan.id());
        return new MarketEntry(
                plan,
                PlanRates.of(plan),
                outcomeOf(plan, previews),
                preview == null ? "" : preview.file().getFileName().toString(),
                cost == null ? null : cost.total(),
                cost == null ? null : cost.differenceFromBaseline(),
                conditions.getOrDefault(plan.id(), List.of()),
                extras.getOrDefault(plan.id(), PlanExtras.none()));
    }

    private static PlanLibrary.Outcome outcomeOf(
            Plan plan, Map<String, PlanLibrary.Saved> previews) {
        var preview = previews.get(plan.id());
        return preview == null ? PlanLibrary.Outcome.NEW : preview.outcome();
    }

    /** Counted over everything harvested, not over the page: a count that narrows misleads. */
    private static int heldCount(
            List<Plan> harvested, Map<String, PlanLibrary.Saved> previews) {
        return (int) harvested.stream()
                .map(plan -> outcomeOf(plan, previews))
                .filter(outcome -> outcome == PlanLibrary.Outcome.UNCHANGED
                        || outcome == PlanLibrary.Outcome.REPLACED)
                .count();
    }

    private static int staleCount(
            List<Plan> harvested, Map<String, PlanLibrary.Saved> previews) {
        return (int) harvested.stream()
                .map(plan -> outcomeOf(plan, previews))
                .filter(outcome -> outcome == PlanLibrary.Outcome.REPLACED)
                .count();
    }

    /**
     * Cheapest first for a rate, and a plan that does not charge one at all sorts last.
     *
     * <p>The same rule the plan browser uses: a tariff with no evening peak has not got the
     * cheapest evening peak. Solar feed-in inverts, because there the biggest number wins, and
     * so does a total, which sorts an uncosted plan last rather than free.
     */
    private static Comparator<MarketEntry> order(PlanQuery.SortBy sort) {
        if (sort == PlanQuery.SortBy.NAME) {
            return Comparator.comparing(MarketEntry::retailer, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(MarketEntry::planName, String.CASE_INSENSITIVE_ORDER);
        }
        if (sort == PlanQuery.SortBy.TOTAL) {
            return Comparator.comparing(MarketEntry::total,
                            Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()))
                    .thenComparing(MarketEntry::planName, String.CASE_INSENSITIVE_ORDER);
        }
        var component = sort.component();
        Comparator<MarketEntry> byRate = Comparator.comparing(
                entry -> rateFor(entry, component),
                Comparator.nullsLast(
                        component == PlanMatrix.Component.FEED_IN
                                ? Comparator.<BigDecimal>reverseOrder()
                                : Comparator.<BigDecimal>naturalOrder()));
        return byRate.thenComparing(MarketEntry::planName, String.CASE_INSENSITIVE_ORDER);
    }

    private static BigDecimal rateFor(MarketEntry entry, PlanMatrix.Component component) {
        return component == null ? null : entry.rates().usageRate(component);
    }

    /** Whether anything on this page has been priced, which the value column depends on. */
    public boolean costed() {
        return entries.stream().anyMatch(MarketEntry::costed);
    }

    public boolean hasScale() {
        return scale.anchored();
    }

    /** The shade for one rate cell, so the template chooses none of its own. */
    public String heatClass(MarketEntry entry, PlanMatrix.Component column) {
        return heat.classFor(entry, column);
    }

    public String valueDirection(MarketEntry entry) {
        return scale.direction(entry.differenceFromBaseline());
    }

    public int valueStep(MarketEntry entry) {
        return scale.stepFor(entry.differenceFromBaseline());
    }

    public int valueBar(MarketEntry entry) {
        return scale.barPercent(entry.differenceFromBaseline());
    }

    public boolean anyMarketLinkedHidden() {
        return marketLinkedHidden > 0;
    }

    /** Whether anything on this page publishes an illustrative rate rather than a real one. */
    public boolean anyMarketLinkedShown() {
        return entries.stream().anyMatch(MarketEntry::marketLinked);
    }

    public boolean empty() {
        return entries.isEmpty();
    }

    public boolean truncated() {
        return matched > entries.size();
    }
}
