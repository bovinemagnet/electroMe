package io.github.bovinemagnet.electrome.core.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A single metered interval.
 *
 * <p>The interval is identified by its local wall-clock start and its length. It deliberately
 * does not store an end timestamp: retailer exports write the end one second short of the
 * boundary (for example 12:29:59 for a half-hour interval), and deriving a duration from that
 * yields 1799 seconds rather than 1800, understating every derived demand figure.
 */
public record IntervalReading(
        LocalDateTime start, Duration length, BigDecimal kWh, Quality quality) {

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    public IntervalReading {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(length, "length");
        Objects.requireNonNull(kWh, "kWh");
        Objects.requireNonNull(quality, "quality");
        if (length.isZero() || length.isNegative()) {
            throw new IllegalArgumentException("Interval length must be positive, got " + length);
        }
    }

    public LocalDateTime end() {
        return start.plus(length);
    }

    public LocalDate date() {
        return start.toLocalDate();
    }

    /** Minutes elapsed since local midnight, 0 to 1439. */
    public int minuteOfDay() {
        return start.getHour() * 60 + start.getMinute();
    }

    /** Average power draw across the interval, in kW. */
    public BigDecimal averageKW() {
        return kWh.multiply(SECONDS_PER_HOUR)
                .divide(BigDecimal.valueOf(length.toSeconds()), MathContext.DECIMAL64);
    }
}
