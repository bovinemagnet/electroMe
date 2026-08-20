package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One plan's cost within a comparison.
 *
 * @param differenceFromBest what this plan costs above the cheapest, which is the figure a
 *     user acts on
 * @param differenceFromBaseline what this plan costs above the household's own tariff, or null
 *     when no baseline plan is configured; negative means it is cheaper than what they pay now
 * @param baseline true when this row <em>is</em> the household's own tariff
 */
public record PlanResult(
        BillBreakdown bill,
        BigDecimal differenceFromBest,
        boolean cheapest,
        BigDecimal differenceFromBaseline,
        boolean baseline) {

    public PlanResult {
        Objects.requireNonNull(bill, "bill");
        Objects.requireNonNull(differenceFromBest, "differenceFromBest");
    }

    /** A result from a comparison with no household tariff to measure against. */
    public PlanResult(BillBreakdown bill, BigDecimal differenceFromBest, boolean cheapest) {
        this(bill, differenceFromBest, cheapest, null, false);
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

    public boolean comparedToBaseline() {
        return differenceFromBaseline != null;
    }

    /** What switching to this plan would have saved: the figure the household acts on. */
    public BigDecimal savingAgainstBaseline() {
        return differenceFromBaseline == null ? BigDecimal.ZERO : differenceFromBaseline.negate();
    }

    public boolean cheaperThanBaseline() {
        return differenceFromBaseline != null && differenceFromBaseline.signum() < 0;
    }
}
