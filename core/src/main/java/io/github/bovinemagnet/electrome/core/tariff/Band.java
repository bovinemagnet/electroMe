package io.github.bovinemagnet.electrome.core.tariff;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * One time-of-use window, half-open over {@code [fromMinuteOfDay, toMinuteOfDay)}.
 *
 * <p>Times are minutes since local midnight rather than {@code LocalTime} so that end-of-day
 * can be represented as 1440. Tariffs are routinely published with an end of "24:00", which
 * {@code LocalTime} cannot hold and which would otherwise be indistinguishable from 00:00.
 */
public record Band(
        int fromMinuteOfDay, int toMinuteOfDay, DaySelector days, BigDecimal centsPerKWh) {

    public static final int MINUTES_PER_DAY = 1440;

    public Band {
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(centsPerKWh, "centsPerKWh");
        if (fromMinuteOfDay < 0 || fromMinuteOfDay >= MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Band start out of range: " + fromMinuteOfDay);
        }
        if (toMinuteOfDay <= 0 || toMinuteOfDay > MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Band end out of range: " + toMinuteOfDay);
        }
        if (centsPerKWh.signum() < 0) {
            throw new IllegalArgumentException("Band rate must not be negative: " + centsPerKWh);
        }
    }

    public static Band parse(String from, String to, DaySelector days, BigDecimal centsPerKWh) {
        return new Band(parseMinuteOfDay(from), parseMinuteOfDay(to), days, centsPerKWh);
    }

    /** Parses "HH:mm", accepting "24:00" as the end-of-day sentinel 1440. */
    public static int parseMinuteOfDay(String clockTime) {
        Objects.requireNonNull(clockTime, "clockTime");
        var parts = clockTime.trim().split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected HH:mm, got: " + clockTime);
        }
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected HH:mm, got: " + clockTime, e);
        }
        int total = hour * 60 + minute;
        if (hour < 0 || minute < 0 || minute > 59 || total > MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Time out of range: " + clockTime);
        }
        return total;
    }

    /** True when the window runs past midnight, for example 21:00 to 11:00. */
    public boolean wrapsMidnight() {
        return toMinuteOfDay <= fromMinuteOfDay;
    }

    public boolean matchesTime(int minuteOfDay) {
        if (wrapsMidnight()) {
            return minuteOfDay >= fromMinuteOfDay || minuteOfDay < toMinuteOfDay;
        }
        return minuteOfDay >= fromMinuteOfDay && minuteOfDay < toMinuteOfDay;
    }

    public boolean matches(IntervalReading reading, HolidayCalendar holidays) {
        return matchesTime(reading.minuteOfDay()) && days.matches(reading.date(), holidays);
    }

    /** The window as printed on a bill, for example "16:00-21:00". */
    public String describe() {
        return format(fromMinuteOfDay) + "-" + format(toMinuteOfDay);
    }

    private static String format(int minuteOfDay) {
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
