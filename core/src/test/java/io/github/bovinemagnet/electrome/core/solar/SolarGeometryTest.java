package io.github.bovinemagnet.electrome.core.solar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SolarGeometryTest {

    private static final SitePosition MELBOURNE = SitePosition.melbourne();

    @Test
    void declinationIsZeroNearTheEquinoxes() {
        assertThat(SolarGeometry.declinationDegrees(LocalDate.of(2025, 3, 21)))
                .isCloseTo(0.0, within(1.0));
        assertThat(SolarGeometry.declinationDegrees(LocalDate.of(2025, 9, 23)))
                .isCloseTo(0.0, within(1.5));
    }

    @Test
    void declinationIsExtremeAtTheSolstices() {
        assertThat(SolarGeometry.declinationDegrees(LocalDate.of(2025, 12, 21)))
                .isCloseTo(-23.45, within(0.5));
        assertThat(SolarGeometry.declinationDegrees(LocalDate.of(2025, 6, 21)))
                .isCloseTo(23.45, within(0.5));
    }

    @Test
    void summerSolsticeNoonElevationMatchesTheGeometricPrediction() {
        // 90 - 37.81 + 23.45 = 75.64 degrees at solar noon.
        assertThat(peakElevation(LocalDate.of(2025, 12, 21))).isCloseTo(75.64, within(0.6));
    }

    @Test
    void winterSolsticeNoonElevationMatchesTheGeometricPrediction() {
        // 90 - 37.81 - 23.45 = 28.74 degrees at solar noon.
        assertThat(peakElevation(LocalDate.of(2025, 6, 21))).isCloseTo(28.74, within(0.6));
    }

    @Test
    void sunIsBelowTheHorizonAtMidnight() {
        assertThat(SolarGeometry.elevationDegrees(
                        LocalDateTime.of(2025, 12, 21, 0, 0), MELBOURNE))
                .isNegative();
    }

    @Test
    void solarNoonSitsAfterThirteenHundredDuringDaylightSaving() {
        // Melbourne runs on AEDT in December, so solar noon falls around 13:20 clock time.
        assertThat(clockHourOfPeakElevation(LocalDate.of(2025, 12, 21))).isBetween(13.0, 13.7);
    }

    @Test
    void solarNoonSitsNearMiddayInWinter() {
        // No daylight saving in June, so solar noon falls around 12:20.
        assertThat(clockHourOfPeakElevation(LocalDate.of(2025, 6, 21))).isBetween(12.0, 12.7);
    }

    @Test
    void irradianceIsZeroBelowTheHorizon() {
        assertThat(SolarGeometry.clearSkyDirectNormalKW(-5)).isZero();
        assertThat(SolarGeometry.clearSkyDirectNormalKW(0)).isZero();
    }

    @Test
    void irradianceApproachesTheSolarConstantOverhead() {
        // At the zenith air mass is 1, giving 1.353 * 0.7 = 0.947 kW/m2.
        assertThat(SolarGeometry.clearSkyDirectNormalKW(90)).isCloseTo(0.947, within(0.01));
    }

    @Test
    void irradianceFallsAsTheSunGetsLower() {
        assertThat(SolarGeometry.clearSkyDirectNormalKW(60))
                .isLessThan(SolarGeometry.clearSkyDirectNormalKW(90));
        assertThat(SolarGeometry.clearSkyDirectNormalKW(10))
                .isLessThan(SolarGeometry.clearSkyDirectNormalKW(60));
    }

    @Test
    void azimuthIsNorthAtSolarNoonInTheSouthernHemisphere() {
        // Measured clockwise from north, the midday sun sits near 0 or 360.
        double azimuth = SolarGeometry.azimuthDegrees(
                LocalDateTime.of(2025, 6, 21, 12, 20), MELBOURNE);
        assertThat(Math.min(azimuth, 360 - azimuth)).isLessThan(15.0);
    }

    @Test
    void rejectsAnImpossiblePosition() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SitePosition(-120, 145, 150, java.time.ZoneId.of("UTC")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double peakElevation(LocalDate date) {
        double peak = -90;
        for (int minute = 0; minute < 1440; minute++) {
            peak = Math.max(peak, SolarGeometry.elevationDegrees(
                    date.atStartOfDay().plusMinutes(minute), MELBOURNE));
        }
        return peak;
    }

    private static double clockHourOfPeakElevation(LocalDate date) {
        double peak = -90;
        int peakMinute = 0;
        for (int minute = 0; minute < 1440; minute++) {
            double elevation = SolarGeometry.elevationDegrees(
                    date.atStartOfDay().plusMinutes(minute), MELBOURNE);
            if (elevation > peak) {
                peak = elevation;
                peakMinute = minute;
            }
        }
        return peakMinute / 60.0;
    }
}
