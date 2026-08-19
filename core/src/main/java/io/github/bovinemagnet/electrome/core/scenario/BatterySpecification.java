package io.github.bovinemagnet.electrome.core.scenario;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * A household battery.
 *
 * @param roundTripEfficiency energy returned divided by energy stored, across a full cycle
 */
public record BatterySpecification(
        BigDecimal capacityKWh, BigDecimal powerKW, BigDecimal roundTripEfficiency) {

    public BatterySpecification {
        Objects.requireNonNull(capacityKWh, "capacityKWh");
        Objects.requireNonNull(powerKW, "powerKW");
        Objects.requireNonNull(roundTripEfficiency, "roundTripEfficiency");
        if (capacityKWh.signum() <= 0 || powerKW.signum() <= 0) {
            throw new IllegalArgumentException("Capacity and power must be positive");
        }
        if (roundTripEfficiency.signum() <= 0
                || roundTripEfficiency.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "Round-trip efficiency must be between 0 and 1: " + roundTripEfficiency);
        }
    }

    /** A common household unit: 13.5 kWh, 5 kW, 90% round trip. */
    public static BatterySpecification typical() {
        return new BatterySpecification(
                new BigDecimal("13.5"), new BigDecimal("5"), new BigDecimal("0.90"));
    }

    /**
     * One-way efficiency, the square root of the round trip.
     *
     * <p>Splitting the loss evenly across charge and discharge is the conventional treatment
     * and keeps the arithmetic symmetric.
     */
    public BigDecimal onewayEfficiency() {
        return new BigDecimal(
                Math.sqrt(roundTripEfficiency().doubleValue()), MathContext.DECIMAL64);
    }
}
