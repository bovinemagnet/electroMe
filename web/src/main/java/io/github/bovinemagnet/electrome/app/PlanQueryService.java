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

    /** Uses the harvested eligibility conditions. */
    public PlanPage apply(Comparison comparison, PlanQuery query) {
        return apply(comparison, query, market.conditions());
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

        var everything = comparison.results();

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
                .toList());

        matched.sort(order(query));

        int hiddenByRequirements = switch (query.requirements()) {
            case OPEN_ONLY -> withRequirements;
            case REQUIREMENTS_ONLY -> eligible.size() - withRequirements;
            case ANY -> 0;
        };

        var shown = query.showsAll() || matched.size() <= query.limit()
                ? List.copyOf(matched)
                : List.copyOf(matched.subList(0, query.limit()));

        return new PlanPage(query, shown, matched.size(), everything.size(),
                hiddenByRequirements, withRequirements, List.copyOf(retailers));
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

    private static Comparator<PlanResult> order(PlanQuery query) {
        return switch (query.sort()) {
            case TOTAL -> Comparator.comparing(PlanResult::total);
            case NAME -> Comparator.comparing(PlanResult::planName, String.CASE_INSENSITIVE_ORDER);
            case AVERAGE_RATE -> Comparator.comparing(r -> r.bill().averageCentsPerKWh());
            case SUPPLY_CHARGE -> Comparator.comparing(
                    (PlanResult r) -> supplyCents(r.bill().plan()))
                    .thenComparing(PlanResult::total);
            case PEAK_RATE -> Comparator.comparing(
                    (PlanResult r) -> peakCents(r.bill().plan()))
                    .thenComparing(PlanResult::total);
        };
    }

    /** A plan with no stated supply charge sorts last rather than first. */
    private static BigDecimal supplyCents(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof DailySupply supply) {
                return supply.centsPerDay();
            }
        }
        return MAX;
    }

    /**
     * The dearest usage rate the plan can charge.
     *
     * <p>A flat plan has one rate, and that rate is its peak. Treating it as having no peak
     * would float every flat plan to the top of a peak-rate sort, which is the opposite of
     * what the reader asked to see.
     */
    private static BigDecimal peakCents(Plan plan) {
        BigDecimal dearest = null;
        for (var charge : plan.charges()) {
            dearest = switch (charge) {
                case FlatRate flat -> max(dearest, flat.centsPerKWh());
                case TimeOfUse tou -> max(dearest, tou.bands().stream()
                        .map(Band::centsPerKWh).max(BigDecimal::compareTo).orElse(null));
                case Tiered tiered -> max(dearest, tiered.tiers().stream()
                        .map(Tier::centsPerKWh).max(BigDecimal::compareTo).orElse(null));
                default -> dearest;
            };
        }
        return dearest == null ? MAX : dearest;
    }

    private static BigDecimal max(BigDecimal current, BigDecimal candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }

    /** Sorts anything that cannot state the figure to the end of the list. */
    private static final BigDecimal MAX = new BigDecimal(Long.MAX_VALUE);
}
