package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.util.Objects;

/** A single usage rate applied to all consumption. */
public record FlatRate(BigDecimal centsPerKWh) implements Charge {

    public FlatRate {
        Objects.requireNonNull(centsPerKWh, "centsPerKWh");
        if (centsPerKWh.signum() < 0) {
            throw new IllegalArgumentException("Flat rate must not be negative: " + centsPerKWh);
        }
    }

    @Override
    public String label() {
        return "Usage";
    }
}
