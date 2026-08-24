package io.github.bovinemagnet.electrome.app;

import java.util.List;
import java.util.Locale;

/**
 * What the market screen has been narrowed to.
 *
 * <p>Criteria travel in the query string, as they do on the plan browser, so a URL completely
 * describes what is on screen and the back button keeps working.
 *
 * @param hideHeld drop the plans already saved, which is what a reader looking for something new
 *     to keep is asking for
 * @param includeMarketLinked let in the plans that sell exposure to the wholesale market rather
 *     than a rate. Off by default: they publish an illustrative unit price, so they can be costed
 *     and ranked like everything else while the total means nothing, and a fiction that sorts
 *     near the top of a ranking is worse than an absence
 */
public record MarketQuery(
        String search,
        String retailer,
        PlanQuery.PlanShape shape,
        PlanQuery.SortBy sort,
        boolean hideHeld,
        boolean includeMarketLinked,
        int limit) {

    /** The orders this screen can offer. */
    public static final List<PlanQuery.SortBy> SORTS = List.of(
            PlanQuery.SortBy.TOTAL,
            PlanQuery.SortBy.SUPPLY_CHARGE,
            PlanQuery.SortBy.PEAK_RATE,
            PlanQuery.SortBy.OFFPEAK_RATE,
            PlanQuery.SortBy.MIDDAY_RATE,
            PlanQuery.SortBy.SHOULDER_RATE,
            PlanQuery.SortBy.FLAT_RATE,
            PlanQuery.SortBy.FEED_IN_RATE,
            PlanQuery.SortBy.NAME);

    public static final List<Integer> LIMITS = List.of(25, 50, 100, Integer.MAX_VALUE);

    private static final int DEFAULT_LIMIT = 50;

    /** What it would cost you: the question the screen is being asked. */
    private static final PlanQuery.SortBy DEFAULT_SORT = PlanQuery.SortBy.TOTAL;

    public MarketQuery {
        search = search == null ? "" : search.trim();
        retailer = retailer == null ? "" : retailer.trim();
        shape = shape == null ? PlanQuery.PlanShape.ANY : shape;
        // An order this screen cannot honour falls back rather than lying about the ranking.
        // A costed order is honourable here only because the screen costs; with no usage
        // loaded, every total is null and the comparator sorts them last, which is honest.
        sort = sort == null || SORTS.contains(sort) ? sort : DEFAULT_SORT;
        sort = sort == null ? DEFAULT_SORT : sort;
        limit = limit <= 0 ? DEFAULT_LIMIT : limit;
    }

    public static MarketQuery of(String search, String retailer, String shape, String sort,
            String hideHeld, String limit) {
        return of(search, retailer, shape, sort, hideHeld, null, limit);
    }

    public static MarketQuery of(String search, String retailer, String shape, String sort,
            String hideHeld, String includeMarketLinked, String limit) {
        return new MarketQuery(
                search,
                retailer,
                enumOrDefault(PlanQuery.PlanShape.class, shape, PlanQuery.PlanShape.ANY),
                enumOrDefault(PlanQuery.SortBy.class, sort, DEFAULT_SORT),
                ticked(hideHeld),
                ticked(includeMarketLinked),
                limitOf(limit));
    }

    /** A checkbox arrives as its value when ticked and not at all when it is not. */
    private static boolean ticked(String value) {
        return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    public boolean showsAll() {
        return limit == Integer.MAX_VALUE;
    }

    public boolean narrowed() {
        return !search.isEmpty()
                || !retailer.isEmpty()
                || shape != PlanQuery.PlanShape.ANY
                || hideHeld
                || includeMarketLinked;
    }

    public boolean matches(io.github.bovinemagnet.electrome.core.tariff.Plan plan) {
        if (!retailer.isEmpty() && !retailer.equalsIgnoreCase(plan.retailer())) {
            return false;
        }
        if (shape != PlanQuery.PlanShape.ANY && PlanQuery.PlanShape.of(plan) != shape) {
            return false;
        }
        if (search.isEmpty()) {
            return true;
        }
        var needle = search.toLowerCase(Locale.ROOT);
        return plan.name().toLowerCase(Locale.ROOT).contains(needle)
                || plan.retailer().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static int limitOf(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_LIMIT;
        }
        if ("all".equalsIgnoreCase(requested.trim())) {
            return Integer.MAX_VALUE;
        }
        try {
            int value = Integer.parseInt(requested.trim());
            return LIMITS.contains(value) ? value : DEFAULT_LIMIT;
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private static <E extends Enum<E>> E enumOrDefault(Class<E> type, String name, E fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
