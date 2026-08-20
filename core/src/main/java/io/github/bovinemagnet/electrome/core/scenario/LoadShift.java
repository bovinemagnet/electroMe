package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Moves a proportion of consumption out of one window and into another, day by day.
 *
 * <p>The shifted energy is spread evenly across the target window rather than in proportion to
 * existing load. A household shifting a dishwasher or an electric vehicle adds a block of
 * demand; it does not scale its whole overnight profile.
 *
 * <p>Energy is conserved exactly. A scenario that quietly lost consumption would present as a
 * saving, and nothing in the output would reveal it.
 */
public record LoadShift(
        int fromMinuteOfDay,
        int toMinuteOfDay,
        int targetFromMinuteOfDay,
        int targetToMinuteOfDay,
        BigDecimal proportion)
        implements Scenario {

    public LoadShift {
        Objects.requireNonNull(proportion, "proportion");
        if (proportion.signum() < 0 || proportion.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "Proportion must be between 0 and 1, got " + proportion);
        }
        if (targetFromMinuteOfDay == targetToMinuteOfDay) {
            throw new IllegalArgumentException(
                    "The target window has no width, so there is nowhere to shift load to");
        }
    }

    /** Shift out of the 16:00-21:00 evening peak into the 00:00-06:00 overnight window. */
    public static LoadShift outOfPeak(BigDecimal proportion) {
        return into(0, 360, proportion);
    }

    /**
     * Shift out of the 16:00-21:00 evening peak into any window.
     *
     * <p>The target window is a parameter because the windows worth shifting into are not all
     * six hours long and not all overnight: a capped free window is typically four hours in
     * the middle of the day, and whether moving the pool pump and the car into it pays is the
     * decision this tool exists to inform.
     */
    public static LoadShift into(
            int targetFromMinuteOfDay, int targetToMinuteOfDay, BigDecimal proportion) {
        return new LoadShift(960, 1260, targetFromMinuteOfDay, targetToMinuteOfDay, proportion);
    }

    @Override
    public String label() {
        return "Shift " + proportion.multiply(new BigDecimal("100")).stripTrailingZeros()
                .toPlainString() + "% of peak load into "
                + clock(targetFromMinuteOfDay) + "-" + clock(targetToMinuteOfDay);
    }

    private static String clock(int minuteOfDay) {
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    @Override
    public UsageData applyTo(UsageData source) {
        if (proportion.signum() == 0) {
            return source;
        }

        // How much each day gives up. Computed per day so a heavy day shifts more than a
        // light one, which is how a household actually behaves.
        var shiftedPerDay = new HashMap<LocalDate, BigDecimal>();
        for (var reading : source.consumption().readings()) {
            if (inSource(reading.minuteOfDay())) {
                shiftedPerDay.merge(
                        reading.date(), reading.kWh().multiply(proportion), BigDecimal::add);
            }
        }

        // Per day, because a first or last day may be partial and spreading a whole day's
        // shift across a part day's intervals would move energy between days.
        var targetIntervals = new HashMap<LocalDate, Integer>();
        for (var reading : source.consumption().readings()) {
            if (inTarget(reading.minuteOfDay())) {
                targetIntervals.merge(reading.date(), 1, Integer::sum);
            }
        }

        var remainingIntervals = new HashMap<>(targetIntervals);
        var placed = new HashMap<LocalDate, BigDecimal>();
        var shifted = new ArrayList<IntervalReading>(source.consumption().readings().size());

        for (var reading : source.consumption().readings()) {
            BigDecimal kWh = reading.kWh();
            if (inSource(reading.minuteOfDay())) {
                kWh = kWh.subtract(kWh.multiply(proportion));
            } else if (inTarget(reading.minuteOfDay())) {
                kWh = kWh.add(share(reading.date(), shiftedPerDay, targetIntervals,
                        remainingIntervals, placed));
            }
            shifted.add(new IntervalReading(
                    reading.start(), reading.length(), kWh, reading.quality()));
        }

        return new UsageData(UsageSeries.of(shifted), source.export());
    }

    /**
     * This interval's slice of the day's shifted energy.
     *
     * <p>An even split rarely divides exactly — five kilowatt hours across eighteen half hours
     * does not — so the day's last target interval takes whatever the division left behind.
     * Without that, every scenario would quietly lose a fraction of a kilowatt hour and present
     * the loss as a saving.
     */
    private static BigDecimal share(
            LocalDate date,
            Map<LocalDate, BigDecimal> shiftedPerDay,
            Map<LocalDate, Integer> targetIntervals,
            Map<LocalDate, Integer> remainingIntervals,
            Map<LocalDate, BigDecimal> placed) {

        BigDecimal dayShift = shiftedPerDay.getOrDefault(date, BigDecimal.ZERO);
        int intervals = targetIntervals.getOrDefault(date, 0);
        int left = remainingIntervals.merge(date, -1, Integer::sum);
        if (dayShift.signum() <= 0 || intervals == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal alreadyPlaced = placed.getOrDefault(date, BigDecimal.ZERO);
        BigDecimal amount = left == 0
                ? dayShift.subtract(alreadyPlaced)
                : dayShift.divide(BigDecimal.valueOf(intervals), MathContext.DECIMAL64);
        placed.put(date, alreadyPlaced.add(amount));
        return amount;
    }

    private boolean inSource(int minuteOfDay) {
        return inWindow(minuteOfDay, fromMinuteOfDay, toMinuteOfDay);
    }

    private boolean inTarget(int minuteOfDay) {
        return inWindow(minuteOfDay, targetFromMinuteOfDay, targetToMinuteOfDay);
    }

    /**
     * Half-open over {@code [from, to)}, wrapping past midnight when the end precedes the start.
     *
     * <p>A car left on charge from 21:00 to 06:00 is an ordinary target window, so a window
     * that cannot wrap would rule out the commonest overnight shift there is.
     */
    private static boolean inWindow(int minuteOfDay, int from, int to) {
        if (to <= from) {
            return minuteOfDay >= from || minuteOfDay < to;
        }
        return minuteOfDay >= from && minuteOfDay < to;
    }

}
