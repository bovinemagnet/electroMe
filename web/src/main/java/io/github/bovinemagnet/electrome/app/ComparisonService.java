package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;

/** Costs every known plan over a window and ranks them. */
@ApplicationScoped
public class ComparisonService {

    private final CostingEngine engine = new CostingEngine();

    @Inject UsageStore usageStore;
    @Inject PlanStore planStore;

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

        var results = new ArrayList<PlanResult>();
        for (int i = 0; i < bills.size(); i++) {
            var bill = bills.get(i);
            BigDecimal difference = bill.totalRounded().subtract(bills.get(0).totalRounded());
            results.add(new PlanResult(bill, difference, i == 0));
        }
        return new Comparison(range, results);
    }
}
