package io.github.bovinemagnet.electrome.core.tariff;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A separate, cheaper rate for energy on a controlled circuit.
 *
 * <p>Victorian households commonly have hot water on one: the retailer energises the circuit for
 * part of the day and prices what it draws well below the ordinary rate. Pricing that energy as
 * ordinary consumption misprices it by the whole gap between two tariffs.
 *
 * @param fromMinuteOfDay start of the window the circuit is energised, or null for any time
 * @param toMinuteOfDay end of that window, or null for any time
 */
public record ControlledLoad(
        BigDecimal centsPerKWh, Integer fromMinuteOfDay, Integer toMinuteOfDay)
        implements Charge {

    public ControlledLoad {
        Objects.requireNonNull(centsPerKWh, "centsPerKWh");
        if (centsPerKWh.signum() < 0) {
            throw new IllegalArgumentException(
                    "Controlled load rate must not be negative: " + centsPerKWh);
        }
        if ((fromMinuteOfDay == null) != (toMinuteOfDay == null)) {
            throw new IllegalArgumentException(
                    "A controlled load window needs both a start and an end, or neither");
        }
    }

    /** Energised all day, which is how a plan that states no window behaves. */
    public static ControlledLoad anyTime(BigDecimal centsPerKWh) {
        return new ControlledLoad(centsPerKWh, null, null);
    }

    public boolean windowed() {
        return fromMinuteOfDay != null;
    }

    /**
     * Whether this reading falls in the energised window.
     *
     * <p>A window running past midnight is normal for a controlled circuit and is handled by the
     * same wrapping rule {@link Band} uses.
     */
    public boolean covers(IntervalReading reading) {
        if (!windowed()) {
            return true;
        }
        return new Band(fromMinuteOfDay, toMinuteOfDay, DaySelector.ALL, centsPerKWh)
                .matchesTime(reading.minuteOfDay());
    }

    @Override
    public String label() {
        return windowed()
                ? "Controlled load " + Band.parse(
                                describe(fromMinuteOfDay), describe(toMinuteOfDay),
                                DaySelector.ALL, centsPerKWh)
                        .describe()
                : "Controlled load";
    }

    private static String describe(int minuteOfDay) {
        return String.format(
                java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
