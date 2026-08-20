package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Every plan costed over both halves of the year, and what switching between them would save.
 *
 * <p>The answer this exists for is not "which plan is cheapest" — the dashboard already says
 * that. It is whether being on the best plan for each season beats being on the best plan for
 * the year, by enough to be worth switching for.
 *
 * @param plans every plan, cheapest whole-year first
 * @param bestInFirst the cheapest plan over the first season alone
 * @param bestInSecond the cheapest plan over the second season alone
 * @param bestSingle the cheapest plan over the whole window, which is what switching is measured
 *     against
 */
public record SeasonalReport(
        DateRange range,
        SeasonSplit split,
        List<SeasonalPlanCost> plans,
        SeasonalPlanCost bestInFirst,
        SeasonalPlanCost bestInSecond,
        SeasonalPlanCost bestSingle) {

    /**
     * One plan over both seasons and over the year.
     *
     * @param wholeYear costed over the whole window in one go, not by adding the seasons up,
     *     because for some tariffs those are not the same number
     * @param caveat why this plan's seasons do not add up to its year, or null when they do
     */
    public record SeasonalPlanCost(
            Plan plan,
            BigDecimal first,
            BigDecimal second,
            BigDecimal wholeYear,
            String caveat) {

        public String planName() {
            return plan.name();
        }

        public String retailer() {
            return plan.retailer();
        }

        public String planId() {
            return plan.id();
        }

        public boolean decomposes() {
            return caveat == null;
        }
    }

    public SeasonalReport {
        plans = List.copyOf(plans);
    }

    public boolean empty() {
        return plans.isEmpty();
    }

    /** What a household pays by being on the right plan in each season. */
    public BigDecimal switchingTotal() {
        if (empty()) {
            return BigDecimal.ZERO;
        }
        return bestInFirst.first().add(bestInSecond.second());
    }

    /** What switching twice a year is worth, before any effort it costs to do it. */
    public BigDecimal savingFromSwitching() {
        if (empty()) {
            return BigDecimal.ZERO;
        }
        return bestSingle.wholeYear().subtract(switchingTotal());
    }

    /**
     * Whether the answer is actually "switch".
     *
     * <p>False when one plan wins both seasons, which is the common case and a more useful
     * finding than any figure: it means the annual ranking was not hiding anything.
     */
    public boolean worthSwitching() {
        return !empty()
                && !bestInFirst.planId().equals(bestInSecond.planId())
                && savingFromSwitching().signum() > 0;
    }

    /** True when one plan is cheapest in both halves of the year. */
    public boolean onePlanWinsBoth() {
        return !empty() && bestInFirst.planId().equals(bestInSecond.planId());
    }

    /** Every distinct reason a plan's seasons do not add up to its year. */
    public List<String> caveats() {
        var seen = new LinkedHashSet<String>();
        for (var plan : plans) {
            if (plan.caveat() != null) {
                seen.add(plan.caveat());
            }
        }
        return List.copyOf(new ArrayList<>(seen));
    }

    public boolean hasCaveats() {
        return !caveats().isEmpty();
    }
}
