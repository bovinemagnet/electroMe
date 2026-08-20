package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;

/** Costs every known plan over a window and ranks them. */
@ApplicationScoped
public class ComparisonService {

    private final CostingEngine engine = new CostingEngine();

    @Inject UsageStore usageStore;
    @Inject PlanStore planStore;

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
        var bills = new ArrayList<BillBreakdown>();
        for (var plan : planStore.plans()) {
            bills.add(engine.cost(usage, plan, range));
        }
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
        return new Comparison(range, results, baselineId);
    }
}
