package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.util.Objects;

/** A credit per kWh exported to the grid. Contributes a negative amount to the bill. */
public record SolarFeedIn(BigDecimal centsPerKWh) implements Charge {

    public SolarFeedIn {
        Objects.requireNonNull(centsPerKWh, "centsPerKWh");
        if (centsPerKWh.signum() < 0) {
            throw new IllegalArgumentException("Feed-in rate must not be negative: " + centsPerKWh);
        }
    }

    @Override
    public String label() {
        return "Solar feed-in credit";
    }
}
