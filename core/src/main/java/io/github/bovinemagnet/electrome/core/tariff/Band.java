package io.github.bovinemagnet.electrome.core.tariff;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One time-of-use window, half-open over {@code [fromMinuteOfDay, toMinuteOfDay)}.
 *
 * <p>Times are minutes since local midnight rather than {@code LocalTime} so that end-of-day
 * can be represented as 1440. Tariffs are routinely published with an end of "24:00", which
 * {@code LocalTime} cannot hold and which would otherwise be indistinguishable from 00:00.
 *
 * <p>A band prices its energy in blocks that accumulate over a reset period, because real
 * Victorian plans cap their cheap windows: GloBird's "4 Hour Free" gives the first 50 kWh of
 * 11:00-15:00 consumption each day at nothing and charges for the rest. A band priced at a
 * single rate is the degenerate case of one unbounded block, held that way so the costing
 * engine has one accumulation path rather than two that can disagree.
 *
 * @param reset how often the blocks start again, or null for a band priced at a single rate
 * @param tiers the blocks, ascending, the last unbounded
 */
public record Band(
        int fromMinuteOfDay,
        int toMinuteOfDay,
        DaySelector days,
        ResetPeriod reset,
        List<Tier> tiers) {

    public static final int MINUTES_PER_DAY = 1440;

    public Band {
        Objects.requireNonNull(days, "days");
        tiers = Tier.validatedBlocks(tiers);
        if (fromMinuteOfDay < 0 || fromMinuteOfDay >= MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Band start out of range: " + fromMinuteOfDay);
        }
        if (toMinuteOfDay <= 0 || toMinuteOfDay > MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Band end out of range: " + toMinuteOfDay);
        }
        // A cap that never resets is not a cap, and a reset with nothing to reset is noise.
        // Requiring the two to agree stops a plan file expressing a cap that never bites.
        if (tiers.size() > 1 && reset == null) {
            throw new IllegalArgumentException(
                    "A band with more than one block needs a reset period");
        }
        if (tiers.size() == 1 && reset != null) {
            throw new IllegalArgumentException(
                    "A band priced at a single rate must not declare a reset period");
        }
    }

    /** A band priced at one rate, whatever the household consumes in it. */
    public Band(int fromMinuteOfDay, int toMinuteOfDay, DaySelector days, BigDecimal cents) {
        this(fromMinuteOfDay, toMinuteOfDay, days, null, Tier.single(cents));
    }

    public static Band parse(String from, String to, DaySelector days, BigDecimal centsPerKWh) {
        return new Band(parseMinuteOfDay(from), parseMinuteOfDay(to), days, centsPerKWh);
    }

    /** A capped band: blocks that accumulate within the window and reset periodically. */
    public static Band parseTiered(
            String from, String to, DaySelector days, ResetPeriod reset, List<Tier> tiers) {
        return new Band(parseMinuteOfDay(from), parseMinuteOfDay(to), days, reset, tiers);
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

    /**
     * The rate the first block charges.
     *
     * <p>For an uncapped band this is the band's rate outright. For a capped one it is the
     * rate up to the cap, which is the right answer for a legend or a colour but only an
     * approximation of the marginal cost — see {@link #capped()}, which callers that care use
     * to say so.
     */
    public BigDecimal centsPerKWh() {
        return tiers.get(0).centsPerKWh();
    }

    /** True when the band's rate changes once a threshold of consumption is passed. */
    public boolean capped() {
        return tiers.size() > 1;
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
