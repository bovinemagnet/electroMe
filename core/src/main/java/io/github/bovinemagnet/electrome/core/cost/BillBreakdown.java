package io.github.bovinemagnet.electrome.core.cost;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The full result of costing a usage series against a plan.
 *
 * <p>Deliberately not a single number. Anything that reduces a plan comparison to one figure
 * without the lines behind it cannot be checked.
 */
public record BillBreakdown(
        Plan plan,
        DateRange range,
        int billingDays,
        List<ChargeLine> lines,
        BigDecimal total,
        List<IntervalReading> uncoveredIntervals) {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public BillBreakdown {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(total, "total");
        lines = List.copyOf(lines);
        uncoveredIntervals = List.copyOf(uncoveredIntervals);
    }

    /** The total in dollars, rounded for display. */
    public BigDecimal totalRounded() {
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal subtotal(ChargeKind kind) {
        var sum = BigDecimal.ZERO;
        for (var line : lines) {
            if (line.kind() == kind) {
                sum = sum.add(line.cost());
            }
        }
        return sum;
    }

    /**
     * What the household must do for this total to hold.
     *
     * <p>Empty for a plan whose rates are simply its rates. A retailer that publishes a
     * before- and an after-discount column has its after column priced here, and a total that
     * assumes the household pays every bill on time is not the same claim as a total that does
     * not. Every view showing such a total shows this beside it.
     */
    public List<String> discountConditions() {
        var conditions = new ArrayList<String>();
        for (var charge : plan.charges()) {
            if (charge instanceof Discount discount && discount.conditional()) {
                conditions.add(discount.name() + ": " + discount.condition());
            }
        }
        return List.copyOf(conditions);
    }

    public boolean assumesConditions() {
        return !discountConditions().isEmpty();
    }

    /** The conditions as one string, for a tooltip. */
    public String discountConditionsText() {
        return String.join("; ", discountConditions());
    }

    /** True when every interval in range was priced by some charge. */
    public boolean complete() {
        return uncoveredIntervals.isEmpty();
    }

    public BigDecimal totalKWh() {
        var sum = BigDecimal.ZERO;
        for (var line : lines) {
            if (line.unit() == Unit.KWH && line.kind() == ChargeKind.USAGE) {
                sum = sum.add(line.quantity());
            }
        }
        return sum;
    }

    /** Effective all-in rate: the whole bill divided by energy consumed. */
    public BigDecimal averageCentsPerKWh() {
        var kWh = totalKWh();
        if (kWh.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return total.multiply(ONE_HUNDRED).divide(kWh, MathContext.DECIMAL64);
    }
}
