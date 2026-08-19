package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A reduction applied after all other charges.
 *
 * @param value a percentage when the basis is PERCENTAGE, otherwise an amount in cents
 * @param conditional true when the discount depends on customer behaviour, such as paying on
 *     time; conditional discounts are shown separately so a comparison can exclude them
 */
public record Discount(
        String name,
        DiscountBasis basis,
        DiscountScope scope,
        BigDecimal value,
        boolean conditional)
        implements Charge {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public Discount {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Discount value must not be negative: " + value);
        }
        if (basis == DiscountBasis.PERCENTAGE && value.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException("Percentage discount exceeds 100: " + value);
        }
    }

    @Override
    public String label() {
        return name;
    }
}
