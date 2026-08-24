package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Costs every known plan over a window and ranks them. */
@ApplicationScoped
public class ComparisonService {

    private final CostingEngine engine = new CostingEngine();

    @Inject UsageStore usageStore;
    @Inject PlanStore planStore;
    @Inject ShortlistService shortlist;

    /**
     * The plan the household is actually on.
     *
     * <p>Ranking tells a reader which plan is cheapest; only a baseline tells them what
     * switching is worth. Configured rather than inferred, because no rule over a directory of
     * plan files can know which one the household signed.
     */
    @ConfigProperty(name = "electrome.baseline.plan")
    Optional<String> baselinePlanId;

    /**
     * Costs one plan, for a view that shows a single tariff in full.
     *
     * <p>The same engine and the same window as the comparison, so a detail panel can never
     * disagree with the row a reader opened it from.
     */
    public io.github.bovinemagnet.electrome.core.cost.BillBreakdown cost(
            io.github.bovinemagnet.electrome.core.tariff.Plan plan, DateRange range) {
        return engine.cost(usageStore.usage(), plan, range);
    }

    public Comparison compare(DateRange range) {
        return compare(range, usageStore.usage());
    }

    /**
     * Costs against a supplied usage series rather than the stored one.
     *
     * <p>This is the seam what-if modelling uses: a shifted or solar-netted series is costed by
     * exactly this code, so a scenario and the baseline can never diverge in their arithmetic.
     */
    public Comparison compare(DateRange range, UsageData usage) {
        return compare(range, usage, costable());
    }

    /**
     * Costs an explicit set of plans rather than everything known.
     *
     * <p>The market screen needs this. {@link #costable()} lets a local plan file win on
     * identifier collision, which is right everywhere else — a hand-written plan is a
     * deliberate statement. But it means a plan you saved months ago would be priced from your
     * file rather than from what the retailer publishes today, and a screen whose whole job is
     * to show you that the two have diverged cannot quietly price the stale one.
     */
    public Comparison compare(DateRange range, UsageData usage, List<Plan> plans) {
        var key = new Costed(usage, plans, range, baselinePlanId.orElse(null));
        var cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // Every plan is independent of every other, and a live harvest is a few hundred of
        // them against seventeen thousand intervals each. The costing engine holds no mutable
        // state, so this is the one place in the application where a parallel stream earns its
        // keep. Order is restored by the sort below rather than relied on from the stream.
        var bills = new ArrayList<>(plans.parallelStream()
                .map(plan -> engine.cost(usage, plan, range))
                .toList());
        bills.sort(Comparator.comparing(BillBreakdown::totalRounded));

        // Absent when the configured plan file is missing, which must not break the page: the
        // rest of the comparison is still true, it simply has nothing to call "your plan".
        BigDecimal baselineTotal = null;
        String baselineId = baselinePlanId.filter(id -> !id.isBlank()).orElse(null);
        for (var bill : bills) {
            if (bill.plan().id().equals(baselineId)) {
                baselineTotal = bill.totalRounded();
            }
        }

        var results = new ArrayList<PlanResult>();
        for (int i = 0; i < bills.size(); i++) {
            var bill = bills.get(i);
            BigDecimal difference = bill.totalRounded().subtract(bills.get(0).totalRounded());
            results.add(new PlanResult(
                    bill,
                    difference,
                    i == 0,
                    baselineTotal == null ? null : bill.totalRounded().subtract(baselineTotal),
                    bill.plan().id().equals(baselineId)));
        }
        var comparison = new Comparison(range, results, baselineId);

        // A handful of entries, discarded wholesale when it fills. Scenario modelling costs
        // against derived series that will never be asked for again, so an unbounded cache
        // would grow with every what-if a reader tries.
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, comparison);
        return comparison;
    }

    /**
     * Costs published plans exactly as published, against the household's own usage.
     *
     * <p>The configured baseline is added if it is not already among them, because without it
     * there is nothing to say "cheaper than what you pay now" against.
     */
    public Comparison compareAsPublished(DateRange range, List<Plan> published) {
        return compare(range, usageStore.usage(), withBaseline(published));
    }

    private List<Plan> withBaseline(List<Plan> published) {
        var baselineId = baselinePlanId.filter(id -> !id.isBlank()).orElse(null);
        if (baselineId == null
                || published.stream().anyMatch(plan -> plan.id().equals(baselineId))) {
            return published;
        }
        var all = new ArrayList<>(published);
        planStore.localPlans().stream()
                .filter(plan -> plan.id().equals(baselineId))
                .findFirst()
                .ifPresent(all::add);
        return List.copyOf(all);
    }

    /**
     * Everything worth costing: the known plans, plus anything on the shortlist they miss.
     *
     * <p>A picked plan the register has stopped publishing is not in the plan list any more,
     * but it is still on the household's shortlist and still has to appear beside the others
     * with a price against it. Dropping it from the costing would make it vanish from every
     * screen except the one that says it was withdrawn.
     */
    private List<Plan> costable() {
        var known = planStore.plans();
        var ids = new java.util.HashSet<String>();
        for (var plan : known) {
            ids.add(plan.id());
        }
        var all = new ArrayList<>(known);
        for (var entry : shortlist.entries()) {
            if (ids.add(entry.planId())) {
                all.add(entry.plan());
            }
        }
        return List.copyOf(all);
    }

    /**
     * What a costing depended on.
     *
     * <p>The usage series is held by identity rather than by value: {@code UsageSeries} does
     * not define equality, so two series with the same numbers are different keys. That is the
     * safe direction to be wrong in — a scenario series that conserves energy exactly would
     * otherwise collide with the baseline it was derived from and be served its answer.
     */
    private record Costed(UsageData usage, List<Plan> plans, DateRange range, String baseline) {}

    /** Re-rendering a page must be free; remembering every what-if must not be. */
    private static final int MAX_CACHED = 8;

    private final Map<Costed, Comparison> cache = new ConcurrentHashMap<>();
}
