package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Applies a {@link PlanQuery} to an already-costed comparison.
 *
 * <p>Filtering happens here rather than in the browser, over results the costing engine has
 * already produced. At the size of a live harvest this is microseconds, and it keeps the page
 * free of application state, which is how every other control on the site works.
 *
 * <p>Nothing here changes what was costed. Criteria narrow what is <em>shown</em>, so a count
 * like "37 hidden" is always measured against the full set and is always truthful.
 */
@ApplicationScoped
public class PlanQueryService {

    @Inject MarketPlanSource market;
    @Inject ShortlistService shortlist;

    /** Uses the harvested eligibility conditions, fees, and the last harvest's own gaps. */
    public PlanPage apply(Comparison comparison, PlanQuery query) {
        return apply(comparison, query, market.conditions(),
                market.lastReport().map(r -> r.skipReasons()).orElse(List.of()),
                market.lastReport().map(r -> r.skipped().size()).orElse(0),
                market.extras().keySet(),
                shortlist.pickedIds(),
                shortlist.withdrawnIds());
    }

    /**
     * Applies criteria against supplied conditions.
     *
     * <p>The seam the tests use, and the reason this class needs no container to exercise: the
     * conditions are an input rather than a lookup.
     *
     * @param conditions eligibility requirements by plan id, for plans that carry any
     */
    public PlanPage apply(
            Comparison comparison, PlanQuery query, Map<String, List<String>> conditions) {
        return apply(comparison, query, conditions, List.of(), 0, java.util.Set.of());
    }

    public PlanPage apply(
            Comparison comparison, PlanQuery query, Map<String, List<String>> conditions,
            List<String> unpriceableReasons, int unpriceable) {
        return apply(comparison, query, conditions, unpriceableReasons, unpriceable,
                java.util.Set.of());
    }

    public PlanPage apply(
            Comparison comparison, PlanQuery query, Map<String, List<String>> conditions,
            List<String> unpriceableReasons, int unpriceable,
            java.util.Set<String> planIdsWithUncostedFees) {
        return apply(comparison, query, conditions, unpriceableReasons, unpriceable,
                planIdsWithUncostedFees, java.util.Set.of(), java.util.Set.of());
    }

    /**
     * @param unpriceableReasons why the harvester could not price some plans, counted
     * @param unpriceable how many plans those reasons account for, which is the size of the
     *     blind spot behind any verdict drawn from what remains
     */
    public PlanPage apply(
            Comparison comparison, PlanQuery query, Map<String, List<String>> conditions,
            List<String> unpriceableReasons, int unpriceable,
            java.util.Set<String> planIdsWithUncostedFees,
            java.util.Set<String> shortlistedIds,
            java.util.Set<String> withdrawnIds) {

        var everything = comparison.results();

        // What each plan asks of a household, read once and carried, so a row and the verdict
        // can never disagree about whether a plan is available to this reader.
        var notes = new java.util.LinkedHashMap<String, PlanNotes>();
        var rates = new java.util.LinkedHashMap<String, PlanRates>();
        var required = new java.util.LinkedHashMap<String, java.util.Set<Requirement>>();
        for (var result : everything) {
            String id = result.bill().plan().id();
            rates.put(id, PlanRates.of(result.bill().plan()));
            var eligibility = conditions.getOrDefault(id, List.of());
            var requirements = Requirement.of(eligibility);
            required.put(id, requirements);
            notes.put(id, new PlanNotes(
                    requirements,
                    eligibility,
                    planIdsWithUncostedFees.contains(id),
                    result.bill().complete(),
                    shortlistedIds.contains(id),
                    withdrawnIds.contains(id)));
        }

        // Every retailer present, not only those surviving the filter: a control that removes
        // its own options as you use it cannot be used to widen a search.
        var retailers = new TreeSet<String>();
        for (var result : everything) {
            retailers.add(result.retailer());
        }

        // The other criteria first, so the requirement counts describe plans this view would
        // otherwise have shown rather than the whole harvest.
        var eligible = everything.stream()
                .filter(matchesSearch(query))
                .filter(matchesRetailer(query))
                .filter(matchesShape(query))
                .toList();

        int withRequirements = (int) eligible.stream()
                .filter(result -> hasRequirements(result, conditions))
                .count();

        var matched = new ArrayList<>(eligible.stream()
                .filter(matchesRequirements(query, conditions))
                .filter(withinDiscountPreference(query))
                .filter(savesAtLeast(query))
                .toList());

        matched.sort(order(query, rates));

        int hiddenByRequirements = switch (query.requirements()) {
            case OPEN_ONLY -> withRequirements;
            case REQUIREMENTS_ONLY -> eligible.size() - withRequirements;
            case ANY -> 0;
        };

        var shown = query.showsAll() || matched.size() <= query.limit()
                ? List.copyOf(matched)
                : List.copyOf(matched.subList(0, query.limit()));

        return new PlanPage(query, shown, matched.size(), everything.size(),
                hiddenByRequirements, withRequirements, List.copyOf(retailers),
                verdict(everything, query, required, unpriceableReasons, unpriceable),
                notes,
                rates,
                PlanRates.columnsFor(List.copyOf(rates.values())));
    }

    /**
     * The cheapest plan this household could actually sign up to.
     *
     * <p>Drawn from everything costed rather than from the filtered rows. Which plan is
     * cheapest is a fact about the market, not about what the reader happens to have typed
     * into the search box, and a verdict that moved as you browsed would be worthless.
     *
     * <p>A plan asking for equipment the household does not have is passed over, but the
     * sentence still admits that something cheaper exists and could not be taken. Silently
     * naming the second-cheapest plan as "the cheapest" is the failure this guards against.
     */
    private static Verdict verdict(
            List<PlanResult> everything,
            PlanQuery query,
            Map<String, java.util.Set<Requirement>> required,
            List<String> unpriceableReasons,
            int unpriceable) {

        PlanResult best = null;
        PlanResult cheapestOfAll = null;
        for (var result : everything) {
            if (cheapestOfAll == null || result.total().compareTo(cheapestOfAll.total()) < 0) {
                cheapestOfAll = result;
            }
            if (!query.canMeet(required.getOrDefault(
                    result.bill().plan().id(), java.util.Set.of()))) {
                continue;
            }
            if (best == null || result.total().compareTo(best.total()) < 0) {
                best = result;
            }
        }
        if (best == null) {
            return new Verdict(null, java.util.Set.of(), true, unpriceable, unpriceableReasons);
        }
        boolean nothingCheaperWasBarred =
                cheapestOfAll == null || cheapestOfAll.total().compareTo(best.total()) >= 0;
        return new Verdict(
                best,
                required.getOrDefault(best.bill().plan().id(), java.util.Set.of()),
                nothingCheaperWasBarred,
                unpriceable,
                unpriceableReasons);
    }

    /** Drops plans whose total holds only if the household earns a discount. */
    private static Predicate<PlanResult> withinDiscountPreference(PlanQuery query) {
        if (!query.excludeConditionalDiscounts()) {
            return result -> true;
        }
        return result -> !result.bill().assumesConditions();
    }

    /**
     * Drops plans saving less than the reader asked for.
     *
     * <p>A plan with nothing to measure against is kept: no baseline means no saving figure,
     * and hiding every row because the household has not named its current plan would be a
     * blank table for a reason the reader could not see.
     */
    private static Predicate<PlanResult> savesAtLeast(PlanQuery query) {
        if (!query.hasMinimumSaving()) {
            return result -> true;
        }
        return result -> !result.comparedToBaseline()
                || result.savingAgainstBaseline().compareTo(query.minimumSaving()) >= 0;
    }

    private static Predicate<PlanResult> matchesSearch(PlanQuery query) {
        if (query.search().isEmpty()) {
            return result -> true;
        }
        String needle = query.search().toLowerCase(Locale.ROOT);
        return result -> result.planName().toLowerCase(Locale.ROOT).contains(needle)
                || result.retailer().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Predicate<PlanResult> matchesRetailer(PlanQuery query) {
        if (query.retailers().isEmpty()) {
            return result -> true;
        }
        return result -> query.retailers().contains(result.retailer());
    }

    private static Predicate<PlanResult> matchesShape(PlanQuery query) {
        if (query.shape() == PlanQuery.PlanShape.ANY) {
            return result -> true;
        }
        return result -> PlanQuery.PlanShape.of(result.bill().plan()) == query.shape();
    }

    private static Predicate<PlanResult> matchesRequirements(
            PlanQuery query, Map<String, List<String>> conditions) {
        return switch (query.requirements()) {
            case ANY -> result -> true;
            case OPEN_ONLY -> result -> !hasRequirements(result, conditions);
            case REQUIREMENTS_ONLY -> result -> hasRequirements(result, conditions);
        };
    }

    /** An empty condition list means the same as none at all: anyone can sign up. */
    private static boolean hasRequirements(
            PlanResult result, Map<String, List<String>> conditions) {
        var planConditions = conditions.get(result.bill().plan().id());
        return planConditions != null && !planConditions.isEmpty();
    }

    private static Comparator<PlanResult> order(
            PlanQuery query, Map<String, PlanRates> rates) {
        var component = query.sort().component();
        if (component != null) {
            // Ties broken by total, so an order over a rate every plan shares still reads as a
            // ranking rather than as whatever order the plans happened to arrive in.
            return Comparator
                    .<PlanResult, BigDecimal>comparing(result -> rateOf(result, rates, component))
                    .thenComparing(PlanResult::total);
        }
        return switch (query.sort()) {
            case TOTAL -> Comparator.comparing(PlanResult::total);
            case NAME -> Comparator.comparing(PlanResult::planName, String.CASE_INSENSITIVE_ORDER);
            case AVERAGE_RATE -> Comparator.comparing(r -> r.bill().averageCentsPerKWh());
            default -> Comparator.comparing(PlanResult::total);
        };
    }

    /**
     * One plan's published rate for a component.
     *
     * <p>A plan that does not charge the component at all sorts last. A tariff with no evening
     * peak has not got the cheapest evening peak, and floating it to the top of that order
     * would answer a different question from the one asked.
     */
    private static BigDecimal rateOf(
            PlanResult result, Map<String, PlanRates> rates, PlanMatrix.Component component) {
        var planRates = rates.get(result.bill().plan().id());
        if (planRates == null) {
            return MAX;
        }
        var rate = planRates.usageRate(component);
        return rate == null ? MAX : rate;
    }

    /** Sorts anything that cannot state the figure to the end of the list. */
    private static final BigDecimal MAX = new BigDecimal(Long.MAX_VALUE);
}
