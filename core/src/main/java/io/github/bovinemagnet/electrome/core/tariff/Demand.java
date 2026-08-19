package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A charge on the highest average demand recorded inside a window.
 *
 * <p>Priced per kW per day, billed against the single highest interval within each reset
 * period. This is the charge kind for which outlier days dominate the result.
 */
public record Demand(
        int fromMinuteOfDay,
        int toMinuteOfDay,
        DaySelector days,
        ResetPeriod reset,
        BigDecimal centsPerKWPerDay)
        implements Charge {

    public Demand {
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(reset, "reset");
        Objects.requireNonNull(centsPerKWPerDay, "centsPerKWPerDay");
        if (centsPerKWPerDay.signum() < 0) {
            throw new IllegalArgumentException("Demand rate must not be negative");
        }
        if (fromMinuteOfDay < 0 || fromMinuteOfDay >= Band.MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Demand window start out of range");
        }
        if (toMinuteOfDay <= 0 || toMinuteOfDay > Band.MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Demand window end out of range");
        }
    }

    /** The demand window expressed as a band, so window matching is shared with time of use. */
    public Band window() {
        return new Band(fromMinuteOfDay, toMinuteOfDay, days, BigDecimal.ZERO);
    }

    @Override
    public String label() {
        return "Demand";
    }
}
