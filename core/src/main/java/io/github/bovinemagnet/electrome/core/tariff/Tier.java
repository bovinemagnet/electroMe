package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One block of a tiered usage charge.
 *
 * @param thresholdKWh the upper bound of this block within the reset period, or null for the
 *     final unbounded block
 */
public record Tier(BigDecimal thresholdKWh, BigDecimal centsPerKWh) {

    public Tier {
        Objects.requireNonNull(centsPerKWh, "centsPerKWh");
        if (centsPerKWh.signum() < 0) {
            throw new IllegalArgumentException("Tier rate must not be negative: " + centsPerKWh);
        }
        if (thresholdKWh != null && thresholdKWh.signum() <= 0) {
            throw new IllegalArgumentException("Tier threshold must be positive: " + thresholdKWh);
        }
    }

    public boolean unbounded() {
        return thresholdKWh == null;
    }
}
