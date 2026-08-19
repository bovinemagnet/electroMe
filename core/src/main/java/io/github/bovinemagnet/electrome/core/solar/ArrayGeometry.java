package io.github.bovinemagnet.electrome.core.solar;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The photovoltaic array.
 *
 * @param azimuthDegrees clockwise from true north, matching {@link SolarGeometry}. In the
 *     Southern Hemisphere the optimum is 0, which is north-facing.
 * @param performanceRatio covers inverter loss, soiling, temperature derating and wiring
 */
public record ArrayGeometry(
        BigDecimal sizeKW, double tiltDegrees, double azimuthDegrees, double performanceRatio) {

    public ArrayGeometry {
        Objects.requireNonNull(sizeKW, "sizeKW");
        if (sizeKW.signum() <= 0) {
            throw new IllegalArgumentException("Array size must be positive: " + sizeKW);
        }
        if (tiltDegrees < 0 || tiltDegrees > 90) {
            throw new IllegalArgumentException("Tilt out of range: " + tiltDegrees);
        }
        if (performanceRatio <= 0 || performanceRatio > 1) {
            throw new IllegalArgumentException(
                    "Performance ratio must be between 0 and 1: " + performanceRatio);
        }
    }

    /** A north-facing array on a typical Victorian roof pitch. */
    public static ArrayGeometry typical(BigDecimal sizeKW) {
        return new ArrayGeometry(sizeKW, 22.0, 0.0, 0.80);
    }
}
