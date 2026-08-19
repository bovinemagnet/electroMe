package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One plan's cost within a comparison.
 *
 * @param differenceFromBest what this plan costs above the cheapest, which is the figure a
 *     user acts on
 */
public record PlanResult(BillBreakdown bill, BigDecimal differenceFromBest, boolean cheapest) {

    public PlanResult {
        Objects.requireNonNull(bill, "bill");
        Objects.requireNonNull(differenceFromBest, "differenceFromBest");
    }

    public String planName() {
        return bill.plan().name();
    }

    public String retailer() {
        return bill.plan().retailer();
    }

    public BigDecimal total() {
        return bill.totalRounded();
    }
}
