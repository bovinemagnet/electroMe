package io.github.bovinemagnet.electrome.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class IntervalReadingTest {

    private static IntervalReading reading(int hour, int minute, String kWh) {
        return new IntervalReading(
                LocalDateTime.of(2025, 8, 19, hour, minute),
                Duration.ofMinutes(30),
                new BigDecimal(kWh),
                Quality.ACTUAL);
    }

    @Test
    void derivesEndFromLength() {
        assertThat(reading(0, 0, "0.424").end()).isEqualTo(LocalDateTime.of(2025, 8, 19, 0, 30));
    }

    @Test
    void exposesDateAndMinuteOfDay() {
        var r = reading(16, 30, "1.0");
        assertThat(r.date()).isEqualTo(LocalDate.of(2025, 8, 19));
        assertThat(r.minuteOfDay()).isEqualTo(990);
    }

    @Test
    void midnightIsMinuteZero() {
        assertThat(reading(0, 0, "1.0").minuteOfDay()).isZero();
    }

    @Test
    void convertsHalfHourlyEnergyToAverageDemand() {
        // 0.5 kWh delivered over half an hour is an average draw of 1 kW.
        assertThat(reading(0, 0, "0.5").averageKW()).isEqualByComparingTo("1");
        assertThat(reading(0, 0, "1.234").averageKW()).isEqualByComparingTo("2.468");
    }

    @Test
    void convertsFiveMinuteEnergyToAverageDemand() {
        var r = new IntervalReading(
                LocalDateTime.of(2025, 8, 19, 0, 0),
                Duration.ofMinutes(5),
                new BigDecimal("0.1"),
                Quality.ACTUAL);
        assertThat(r.averageKW()).isEqualByComparingTo("1.2");
    }

    @Test
    void rejectsNonPositiveLength() {
        assertThatThrownBy(() -> new IntervalReading(
                        LocalDateTime.of(2025, 8, 19, 0, 0),
                        Duration.ZERO,
                        BigDecimal.ONE,
                        Quality.ACTUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsQualityFlags() {
        assertThat(Quality.fromFlag("A")).isEqualTo(Quality.ACTUAL);
        assertThat(Quality.fromFlag("E")).isEqualTo(Quality.ESTIMATED);
        assertThat(Quality.fromFlag("S")).isEqualTo(Quality.SUBSTITUTED);
        assertThat(Quality.fromFlag("a")).isEqualTo(Quality.ACTUAL);
    }

    @Test
    void rejectsUnknownQualityFlag() {
        assertThatThrownBy(() -> Quality.fromFlag("Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Z");
    }
}
