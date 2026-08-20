package io.github.bovinemagnet.electrome.core.appliance;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A block of energy that must be delivered inside a window.
 *
 * <p>An electric vehicle, a pool pump, a hot water service. The household says how much and by
 * when; the scheduler says at what time.
 *
 * @param energyPerRunKWh the energy one run delivers
 * @param powerKW the appliance's draw, which fixes how many half hours a run occupies
 * @param availableFromMinute when it is plugged in or the circuit is available
 * @param deadlineMinute when it must be finished, wrapping midnight where that is earlier
 * @param runsPerWeek how many days a week it runs
 * @param months the months it runs at all; a pool heater contributes nothing in June
 * @param interruptible true where the run may be split across non-adjacent half hours
 * @param controlledCircuit true where the appliance sits on the controlled circuit
 */
public record SchedulableLoad(
        String label,
        BigDecimal energyPerRunKWh,
        BigDecimal powerKW,
        int availableFromMinute,
        int deadlineMinute,
        int runsPerWeek,
        Set<Month> months,
        boolean interruptible,
        boolean controlledCircuit) implements ApplianceLoad {

    private static final BigDecimal TWO = new BigDecimal("2");

    public SchedulableLoad {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(energyPerRunKWh, "energyPerRunKWh");
        Objects.requireNonNull(powerKW, "powerKW");
        if (energyPerRunKWh.signum() <= 0) {
            throw new IllegalArgumentException("Energy per run must be positive");
        }
        if (powerKW.signum() <= 0) {
            throw new IllegalArgumentException("Power must be positive");
        }
        if (runsPerWeek < 0 || runsPerWeek > 7) {
            throw new IllegalArgumentException("Runs per week must be 0 to 7, got " + runsPerWeek);
        }
        months = months == null || months.isEmpty()
                ? EnumSet.allOf(Month.class)
                : EnumSet.copyOf(months);
    }

    /** Energy delivered in one full half hour at this appliance's draw. */
    public BigDecimal kWhPerSlot() {
        return powerKW.divide(TWO, MathContext.DECIMAL64);
    }

    /** How many half hours a run occupies, rounding up: a part slot still occupies one. */
    public int slotsNeeded() {
        return energyPerRunKWh.divide(kWhPerSlot(), 0, RoundingMode.CEILING).intValueExact();
    }

    public boolean runsIn(Month month) {
        return months.contains(month);
    }

    /** Runs per week as a proportion of days, which is how the approximation is applied. */
    public BigDecimal dutyCycle() {
        return BigDecimal.valueOf(runsPerWeek).divide(new BigDecimal("7"), MathContext.DECIMAL64);
    }

    @Override
    public UsageSeries addedTo(UsageSeries shape, LoadSchedule schedule) {
        if (schedule == null || schedule.empty() || runsPerWeek == 0) {
            return shape;
        }

        // Which days the appliance runs on. Spreading the week's runs evenly is an
        // approximation — driving is not evenly spaced — and it is stated as one.
        var days = new TreeSet<>(shape.billingDays());
        var added = new ArrayList<IntervalReading>(shape.readings());

        int dayIndex = 0;
        for (LocalDate day : days) {
            if (!runsIn(day.getMonth())) {
                continue;
            }
            if (!runsToday(dayIndex++)) {
                continue;
            }
            for (var entry : schedule.energyBySlot().entrySet()) {
                added.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT)
                                .plusMinutes(entry.getKey() * 30L),
                        Duration.ofMinutes(30),
                        entry.getValue(),
                        Quality.ESTIMATED));
            }
        }
        return UsageSeries.of(added);
    }

    /**
     * Spreads the week's runs evenly across its days.
     *
     * <p>Five runs land on five of every seven days rather than on five consecutive ones. It is
     * an approximation — nobody drives to a schedule — and the screen says so.
     */
    private boolean runsToday(int dayIndex) {
        if (runsPerWeek >= 7) {
            return true;
        }
        int positionInWeek = dayIndex % 7;
        return (positionInWeek * runsPerWeek) / 7 < ((positionInWeek + 1) * runsPerWeek) / 7;
    }
}
