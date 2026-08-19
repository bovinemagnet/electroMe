package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
        int fromMinuteOfDay, int toMinuteOfDay, int targetMinuteOfDay, BigDecimal proportion)
        implements Scenario {

    private static final int TARGET_WINDOW_MINUTES = 360;

    public LoadShift {
        Objects.requireNonNull(proportion, "proportion");
        if (proportion.signum() < 0 || proportion.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "Proportion must be between 0 and 1, got " + proportion);
        }
    }

    /** Shift out of the 16:00-21:00 evening peak into the 00:00-06:00 overnight window. */
    public static LoadShift outOfPeak(BigDecimal proportion) {
        return new LoadShift(960, 1260, 0, proportion);
    }

    @Override
    public String label() {
        return "Shift " + proportion.multiply(new BigDecimal("100")).stripTrailingZeros()
                .toPlainString() + "% of peak load overnight";
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

        int targetIntervals = countTargetIntervals(source.consumption());
        var shifted = new ArrayList<IntervalReading>(source.consumption().readings().size());

        for (var reading : source.consumption().readings()) {
            BigDecimal kWh = reading.kWh();
            if (inSource(reading.minuteOfDay())) {
                kWh = kWh.subtract(kWh.multiply(proportion));
            } else if (inTarget(reading.minuteOfDay())) {
                BigDecimal dayShift = shiftedPerDay.getOrDefault(reading.date(), BigDecimal.ZERO);
                if (dayShift.signum() > 0 && targetIntervals > 0) {
                    kWh = kWh.add(dayShift.divide(
                            BigDecimal.valueOf(targetIntervals), MathContext.DECIMAL64));
                }
            }
            shifted.add(new IntervalReading(
                    reading.start(), reading.length(), kWh, reading.quality()));
        }

        return new UsageData(UsageSeries.of(shifted), source.export());
    }

    private boolean inSource(int minuteOfDay) {
        return minuteOfDay >= fromMinuteOfDay && minuteOfDay < toMinuteOfDay;
    }

    private boolean inTarget(int minuteOfDay) {
        return minuteOfDay >= targetMinuteOfDay
                && minuteOfDay < targetMinuteOfDay + TARGET_WINDOW_MINUTES;
    }

    /** Intervals per day falling in the target window, taken from the data itself. */
    private int countTargetIntervals(UsageSeries series) {
        if (series.isEmpty()) {
            return 0;
        }
        LocalDate first = series.readings().get(0).date();
        int count = 0;
        for (var reading : series.readings()) {
            if (reading.date().equals(first) && inTarget(reading.minuteOfDay())) {
                count++;
            }
        }
        return count;
    }
}
