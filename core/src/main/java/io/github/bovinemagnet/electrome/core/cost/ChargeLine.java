package io.github.bovinemagnet.electrome.core.cost;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of a bill.
 *
 * <p>Carries the quantity and rate as well as the cost so that every figure shown to a user
 * can be traced back to the arithmetic that produced it.
 *
 * @param cost in dollars, negative for credits and discounts
 * @param rateCents the applicable rate in cents, or null where no single rate applies
 */
public record ChargeLine(
        String label,
        ChargeKind kind,
        BigDecimal quantity,
        Unit unit,
        BigDecimal rateCents,
        BigDecimal cost) {

    public ChargeLine {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(cost, "cost");
    }
}
