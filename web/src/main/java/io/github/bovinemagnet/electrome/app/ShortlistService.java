package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.Plan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The working set: the household's own plan files, plus what they picked out of the market.
 *
 * <p>One collection, read by every screen that needs a handful of plans rather than all of
 * them. A harvested plan that has been picked is indistinguishable from a hand-written one
 * here, which is the point: the appliance modelling and the comparison should not care where a
 * tariff came from.
 */
@ApplicationScoped
public class ShortlistService {

    @Inject Shortlist picks;
    @Inject PlanStore planStore;
    @Inject MarketPlanSource market;

    /**
     * One plan on the shortlist.
     *
     * @param local true when it came from a file rather than the register
     * @param picked true when the household ticked it rather than it being there by default
     * @param withdrawn true when the register no longer publishes it and the tariff below was
     *     recovered from the harvest cache
     */
    public record Entry(Plan plan, boolean local, boolean picked, boolean withdrawn) {

        public String planId() {
            return plan.id();
        }
    }

    /**
     * Local plans first, then the picks, in the order they were picked.
     *
     * <p>A picked identifier the register no longer lists is not dropped. A plan being
     * withdrawn is decision-relevant — it may be why the household is looking — so it stays,
     * marked, priced from what the retailer last published.
     */
    public List<Entry> entries() {
        var entries = new ArrayList<Entry>();
        var seen = new LinkedHashSet<String>();

        for (var plan : planStore.localPlans()) {
            entries.add(new Entry(plan, true, picks.contains(plan.id()), false));
            seen.add(plan.id());
        }

        var available = planStore.plans();
        for (var id : picks.picked()) {
            if (seen.contains(id)) {
                continue;
            }
            var live = available.stream().filter(p -> p.id().equals(id)).findFirst();
            if (live.isPresent()) {
                entries.add(new Entry(live.get(), false, true, false));
                seen.add(id);
                continue;
            }
            // Not on the register any more. The last thing it published still prices.
            market.fromCache(id).ifPresent(plan -> {
                entries.add(new Entry(plan, false, true, true));
                seen.add(id);
            });
        }
        return List.copyOf(entries);
    }

    /** Just the tariffs, for anything that only needs to cost them. */
    public List<Plan> plans() {
        return entries().stream().map(Entry::plan).toList();
    }

    /** Picked identifiers whose plan could not be found at all, so nothing can price them. */
    public Set<String> unresolved() {
        var resolved = entries().stream().map(Entry::planId).collect(java.util.stream.Collectors
                .toCollection(LinkedHashSet::new));
        var missing = new LinkedHashSet<String>();
        for (var id : picks.picked()) {
            if (!resolved.contains(id)) {
                missing.add(id);
            }
        }
        return java.util.Collections.unmodifiableSet(missing);
    }

    public Set<String> withdrawnIds() {
        var withdrawn = new LinkedHashSet<String>();
        for (var entry : entries()) {
            if (entry.withdrawn()) {
                withdrawn.add(entry.planId());
            }
        }
        return java.util.Collections.unmodifiableSet(withdrawn);
    }

    public Optional<Entry> entryFor(String planId) {
        return entries().stream().filter(e -> e.planId().equals(planId)).findFirst();
    }

    /** Everything the household has ticked, whether or not it still resolves to a tariff. */
    public Set<String> pickedIds() {
        return picks.picked();
    }

    public boolean picked(String planId) {
        return picks.contains(planId);
    }

    public int pickedCount() {
        return picks.size();
    }
}
