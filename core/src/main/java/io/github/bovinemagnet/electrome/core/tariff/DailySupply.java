package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.util.Objects;

/** A fixed charge per day of supply, irrespective of consumption. */
public record DailySupply(BigDecimal centsPerDay) implements Charge {

    public DailySupply {
        Objects.requireNonNull(centsPerDay, "centsPerDay");
        if (centsPerDay.signum() < 0) {
            throw new IllegalArgumentException("Daily supply must not be negative: " + centsPerDay);
        }
    }

    @Override
    public String label() {
        return "Daily supply charge";
    }
}
