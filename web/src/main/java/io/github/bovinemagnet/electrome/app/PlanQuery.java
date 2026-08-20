package io.github.bovinemagnet.electrome.app;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the reader has asked to see. Criteria only, no behaviour beyond describing itself.
 *
 * <p>Every value arrives as text in the query string rather than in a session, so that a screen
 * survives a reload, is linkable, and leaves the server stateless.
 *
 * <p>Nothing here rejects input. An unknown sort order or a hand-edited limit falls back to its
 * default: the reader asked to see plans, and a bad criterion is no reason to show them none.
 *
 * @param retailers empty means every retailer
 * @param limit how many rows to show; {@link Integer#MAX_VALUE} for all of them
 * @param household what the reader says they own, which decides which plans they could
 *     actually sign up to and therefore which one the verdict may name
 * @param excludeConditionalDiscounts drops plans whose total depends on the household earning
 *     a discount, for a reader who would rather compare certainties
 * @param minimumSaving hides plans saving less than this against the household's own tariff;
 *     null when no threshold was asked for
 */
public record PlanQuery(
        String search,
        RequirementFilter requirements,
        Set<String> retailers,
        PlanShape shape,
        SortBy sort,
        int limit,
        Set<Requirement> household,
        boolean excludeConditionalDiscounts,
        java.math.BigDecimal minimumSaving) {

    /**
     * Whether to show plans a household must own or join something to be offered.
     *
     * <p>{@link #OPEN_ONLY} is the default, and that is an editorial position rather than a
     * technical one: of the plans a live harvest returns, roughly a fifth need equipment or a
     * membership, and the cheapest few all do. Showing them by default puts a number at the top
     * of the page that the reader may not be able to act on. They stay one click away, and the
     * count of what is hidden is always visible.
     */
    public enum RequirementFilter {
        ANY,
        OPEN_ONLY,
        REQUIREMENTS_ONLY
    }

    /** The structure of a tariff, which is what a reader is choosing between. */
    public enum PlanShape {
        ANY,
        FLAT,
        TIME_OF_USE,
        BLOCK,
        DEMAND;

        /**
         * The most distinguishing charge on a plan.
         *
         * <p>A plan may carry several kinds at once. The order below runs from the least common
         * to the most, so a time-of-use plan that also has a demand charge is described as a
         * demand plan: that is the part a reader would not have expected.
         */
        public static PlanShape of(io.github.bovinemagnet.electrome.core.tariff.Plan plan) {
            if (plan == null) {
                return ANY;
            }
            PlanShape shape = ANY;
            for (var charge : plan.charges()) {
                if (charge instanceof io.github.bovinemagnet.electrome.core.tariff.Demand) {
                    return DEMAND;
                }
                if (charge instanceof io.github.bovinemagnet.electrome.core.tariff.Tiered) {
                    shape = BLOCK;
                } else if (charge instanceof io.github.bovinemagnet.electrome.core.tariff.TimeOfUse
                        && shape != BLOCK) {
                    shape = TIME_OF_USE;
                } else if (charge instanceof io.github.bovinemagnet.electrome.core.tariff.FlatRate
                        && shape == ANY) {
                    shape = FLAT;
                }
            }
            return shape;
        }
    }

    /**
     * What the table is ordered by.
     *
     * <p>The rate orders sort on the plan's published rate for that part of the day. A plan
     * that does not charge one at all sorts last rather than first: a tariff with no evening
     * peak has not got the cheapest evening peak.
     */
    public enum SortBy {
        TOTAL,
        SUPPLY_CHARGE,
        OFFPEAK_RATE,
        MIDDAY_RATE,
        SHOULDER_RATE,
        PEAK_RATE,
        FLAT_RATE,
        FEED_IN_RATE,
        AVERAGE_RATE,
        NAME;

        /** The rate this order reads, or null for an order that is not about a rate. */
        public PlanMatrix.Component component() {
            return switch (this) {
                case SUPPLY_CHARGE -> PlanMatrix.Component.DAILY_SUPPLY;
                case OFFPEAK_RATE -> PlanMatrix.Component.OFFPEAK;
                case MIDDAY_RATE -> PlanMatrix.Component.MIDDAY;
                case SHOULDER_RATE -> PlanMatrix.Component.SHOULDER;
                case PEAK_RATE -> PlanMatrix.Component.PEAK;
                case FLAT_RATE -> PlanMatrix.Component.FLAT;
                case FEED_IN_RATE -> PlanMatrix.Component.FEED_IN;
                case TOTAL, AVERAGE_RATE, NAME -> null;
            };
        }
    }

    /** The limits the control offers. A value outside them is clamped to the nearest. */
    public static final List<Integer> LIMITS = List.of(10, 25, 100, Integer.MAX_VALUE);

    private static final int DEFAULT_LIMIT = 25;

    public PlanQuery {
        search = search == null ? "" : search.trim();
        requirements = requirements == null ? RequirementFilter.OPEN_ONLY : requirements;
        shape = shape == null ? PlanShape.ANY : shape;
        sort = sort == null ? SortBy.TOTAL : sort;
        retailers = retailers == null ? Set.of() : Set.copyOf(retailers);
        household = household == null ? Set.of() : Set.copyOf(household);
        minimumSaving = minimumSaving != null && minimumSaving.signum() > 0
                ? minimumSaving
                : null;
    }

    /** Criteria from before the household could say what it owns. */
    public PlanQuery(String search, RequirementFilter requirements, Set<String> retailers,
            PlanShape shape, SortBy sort, int limit) {
        this(search, requirements, retailers, shape, sort, limit, Set.of(), false, null);
    }

    public static PlanQuery defaults() {
        return new PlanQuery("", RequirementFilter.OPEN_ONLY, Set.of(), PlanShape.ANY,
                SortBy.TOTAL, DEFAULT_LIMIT);
    }

    /** True when this household could sign up to a plan asking for these things. */
    public boolean canMeet(Set<Requirement> required) {
        for (var requirement : required) {
            if (!requirement.ownable() || !household.contains(requirement)) {
                return false;
            }
        }
        return true;
    }

    /** Builds from raw query-string text, falling back to a default for anything unreadable. */
    public static PlanQuery of(String search, String requirements, List<String> retailers,
            String shape, String sort, String limit) {
        return of(search, requirements, retailers, shape, sort, limit, List.of(), null, null);
    }

    /** Builds from raw query-string text, including what the household says it owns. */
    public static PlanQuery of(String search, String requirements, List<String> retailers,
            String shape, String sort, String limit, List<String> have,
            String excludeConditional, String minimumSaving) {
        return new PlanQuery(
                search,
                parse(RequirementFilter.class, requirements, RequirementFilter.OPEN_ONLY),
                cleaned(retailers),
                parse(PlanShape.class, shape, PlanShape.ANY),
                parse(SortBy.class, sort, SortBy.TOTAL),
                limit(limit),
                capabilities(have),
                Boolean.parseBoolean(excludeConditional),
                amount(minimumSaving));
    }

    public PlanQuery withSearch(String replacement) {
        return new PlanQuery(replacement, requirements, retailers, shape, sort, limit,
                household, excludeConditionalDiscounts, minimumSaving);
    }

    /** Anything the reader ticked that this application recognises; the rest is ignored. */
    private static Set<Requirement> capabilities(List<String> have) {
        if (have == null) {
            return Set.of();
        }
        var owned = new java.util.LinkedHashSet<Requirement>();
        for (var value : have) {
            if (value == null) {
                continue;
            }
            for (var part : value.split(",")) {
                var requirement = Requirement.parse(part);
                if (requirement != null && requirement.ownable()) {
                    owned.add(requirement);
                }
            }
        }
        return Set.copyOf(owned);
    }

    /** A threshold that cannot be read is no threshold, not an error page. */
    private static java.math.BigDecimal amount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Qute has no syntax for an enum constant, so the control asks the query for them.
    public Requirement solar() {
        return Requirement.SOLAR;
    }

    public Requirement battery() {
        return Requirement.BATTERY;
    }

    public Requirement electricVehicle() {
        return Requirement.ELECTRIC_VEHICLE;
    }

    public Requirement membership() {
        return Requirement.MEMBERSHIP;
    }

    public Requirement concession() {
        return Requirement.CONCESSION;
    }

    public boolean hasHousehold() {
        return !household.isEmpty();
    }

    public boolean hasMinimumSaving() {
        return minimumSaving != null;
    }

    public boolean showsAll() {
        return limit == Integer.MAX_VALUE;
    }

    /**
     * True when the reader has changed which plans are shown.
     *
     * <p>The default requirement filter is not counted, even though it does hide plans. It is
     * the view a reader arrives at without having chosen anything, so treating it as a filter
     * would offer to "clear" a choice nobody made. What that default hides is reported
     * separately, and always.
     */
    public boolean restricted() {
        return !search.isEmpty()
                || requirements != RequirementFilter.OPEN_ONLY
                || !retailers.isEmpty()
                || shape != PlanShape.ANY
                || excludeConditionalDiscounts
                || minimumSaving != null;
    }

    /**
     * The criteria in force, in words.
     *
     * <p>An empty result must name what excluded everything and offer to clear it. A blank table
     * is never acceptable.
     */
    public List<String> activeCriteria() {
        var active = new ArrayList<String>();
        if (!search.isEmpty()) {
            active.add("search “" + search + "”");
        }
        switch (requirements) {
            case OPEN_ONLY -> active.add("only plans anyone can sign up to");
            case REQUIREMENTS_ONLY -> active.add("only plans with requirements");
            case ANY -> { }
        }
        for (var retailer : new java.util.TreeSet<>(retailers)) {
            active.add("retailer " + retailer);
        }
        switch (shape) {
            case FLAT -> active.add("flat rate plans");
            case TIME_OF_USE -> active.add("time-of-use plans");
            case BLOCK -> active.add("block tariff plans");
            case DEMAND -> active.add("demand charge plans");
            case ANY -> { }
        }
        if (excludeConditionalDiscounts) {
            active.add("no plans relying on a conditional discount");
        }
        if (minimumSaving != null) {
            active.add("saving at least $" + minimumSaving.stripTrailingZeros().toPlainString()
                    + " against your plan");
        }
        return List.copyOf(active);
    }

    /**
     * These criteria as query-string parameters, without a leading separator.
     *
     * <p>Only what differs from the default is emitted. Defaults left in a URL make a shared
     * link look like a set of deliberate choices.
     */
    public String queryString() {
        var parts = new ArrayList<String>();
        if (!search.isEmpty()) {
            parts.add("search=" + encode(search));
        }
        if (requirements != RequirementFilter.OPEN_ONLY) {
            parts.add("requirements=" + requirements.name());
        }
        for (var retailer : new java.util.TreeSet<>(retailers)) {
            parts.add("retailers=" + encode(retailer));
        }
        if (shape != PlanShape.ANY) {
            parts.add("shape=" + shape.name());
        }
        if (sort != SortBy.TOTAL) {
            parts.add("sort=" + sort.name());
        }
        if (limit != DEFAULT_LIMIT) {
            parts.add("limit=" + (showsAll() ? "all" : Integer.toString(limit)));
        }
        for (var owned : new java.util.TreeSet<>(household)) {
            parts.add("have=" + owned.name());
        }
        if (excludeConditionalDiscounts) {
            parts.add("unconditional=true");
        }
        if (minimumSaving != null) {
            parts.add("minSaving=" + minimumSaving.stripTrailingZeros().toPlainString());
        }
        return String.join("&", parts);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String text, E fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Set<String> cleaned(List<String> retailers) {
        if (retailers == null) {
            return Set.of();
        }
        var kept = new LinkedHashSet<String>();
        for (var retailer : retailers) {
            if (retailer != null && !retailer.isBlank()) {
                kept.add(retailer.trim());
            }
        }
        return kept;
    }

    /** Clamps to an offered limit, so a hand-edited value cannot ship an unbounded page. */
    private static int limit(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT_LIMIT;
        }
        if ("all".equalsIgnoreCase(text.trim())) {
            return Integer.MAX_VALUE;
        }
        int requested;
        try {
            requested = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        int nearest = LIMITS.get(0);
        for (var offered : LIMITS) {
            if (offered <= requested) {
                nearest = offered;
            }
        }
        return nearest;
    }
}
