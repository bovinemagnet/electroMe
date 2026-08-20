package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A reduction applied after all other charges.
 *
 * @param value a percentage when the basis is PERCENTAGE, otherwise an amount in cents
 * @param condition what the household must do to earn it, such as "pay on time", or null when
 *     the discount is unconditional
 */
public record Discount(
        String name,
        DiscountBasis basis,
        DiscountScope scope,
        BigDecimal value,
        String condition)
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

    /**
     * Whether the total this discount produces depends on the household's behaviour.
     *
     * <p>Retailers publish before- and after-discount columns side by side, and the after
     * column is the one that wins a comparison. Pricing it without saying what it assumes
     * presents a best case as the case, so every view that shows such a total says so.
     */
    public boolean conditional() {
        return condition != null;
    }

    @Override
    public String label() {
        return name;
    }
}
