package io.github.bovinemagnet.electrome.core.solar;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class GenerationModelTest {

    private static final GenerationModel MODEL = new GenerationModel(
            SitePosition.melbourne(), ArrayGeometry.typical(new BigDecimal("6.6")));

    private static BigDecimal dayTotal(GenerationModel model, LocalDate date) {
        var total = BigDecimal.ZERO;
        for (int minute = 0; minute < 1440; minute += 30) {
            total = total.add(model.generationKWh(
                    date.atStartOfDay().plusMinutes(minute), Duration.ofMinutes(30)));
        }
        return total;
    }

    @Test
    void generatesNothingAtNight() {
        assertThat(MODEL.generationKWh(
                        LocalDate.of(2025, 12, 21).atTime(2, 0), Duration.ofMinutes(30)))
                .isEqualByComparingTo("0");
        assertThat(MODEL.generationKWh(
                        LocalDate.of(2025, 6, 21).atTime(23, 0), Duration.ofMinutes(30)))
                .isEqualByComparingTo("0");
    }

    @Test
    void generatesMoreInSummerThanWinter() {
        assertThat(dayTotal(MODEL, LocalDate.of(2025, 12, 21)))
                .isGreaterThan(dayTotal(MODEL, LocalDate.of(2025, 6, 21)));
    }

    @Test
    void summerDailyOutputIsPlausibleForSixPointSixKilowatts() {
        // A clear midsummer day on a 6.6 kW array in Melbourne is roughly 35 to 45 kWh.
        assertThat(dayTotal(MODEL, LocalDate.of(2025, 12, 21)))
                .isBetween(new BigDecimal("30"), new BigDecimal("50"));
    }

    @Test
    void winterDailyOutputIsPlausible() {
        assertThat(dayTotal(MODEL, LocalDate.of(2025, 6, 21)))
                .isBetween(new BigDecimal("8"), new BigDecimal("26"));
    }

    @Test
    void neverExceedsTheArrayRating() {
        for (int minute = 0; minute < 1440; minute += 30) {
            // 6.6 kW for half an hour is at most 3.3 kWh.
            assertThat(MODEL.generationKWh(
                            LocalDate.of(2025, 12, 21).atStartOfDay().plusMinutes(minute),
                            Duration.ofMinutes(30)))
                    .isLessThanOrEqualTo(new BigDecimal("3.3"));
        }
    }

    @Test
    void peaksNearSolarNoonNotClockNoon() {
        int peakMinute = 0;
        var peak = BigDecimal.ZERO;
        for (int minute = 0; minute < 1440; minute += 30) {
            var output = MODEL.generationKWh(
                    LocalDate.of(2025, 12, 21).atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30));
            if (output.compareTo(peak) > 0) {
                peak = output;
                peakMinute = minute;
            }
        }
        // Daylight saving pushes the December peak to around 13:00-13:30.
        assertThat(peakMinute).isBetween(12 * 60 + 30, 14 * 60);
    }

    @Test
    void generatesLittleDuringTheEveningPeakWindow() {
        // This is the finding that drives the battery case: generation is almost over by the
        // time the 16:00-21:00 peak begins, especially in winter.
        var winterEvening = BigDecimal.ZERO;
        for (int minute = 16 * 60; minute < 21 * 60; minute += 30) {
            winterEvening = winterEvening.add(MODEL.generationKWh(
                    LocalDate.of(2025, 6, 21).atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30)));
        }
        assertThat(winterEvening).isLessThan(new BigDecimal("1.5"));
    }

    @Test
    void aNorthFacingArrayBeatsASouthFacingOne() {
        var north = new GenerationModel(SitePosition.melbourne(),
                new ArrayGeometry(new BigDecimal("6.6"), 22.0, 0.0, 0.8));
        var south = new GenerationModel(SitePosition.melbourne(),
                new ArrayGeometry(new BigDecimal("6.6"), 22.0, 180.0, 0.8));
        var date = LocalDate.of(2025, 6, 21);
        assertThat(dayTotal(north, date)).isGreaterThan(dayTotal(south, date));
    }

    @Test
    void generateProducesOneReadingPerIntervalOfTheShape() {
        var readings = new ArrayList<IntervalReading>();
        var date = LocalDate.of(2025, 12, 21);
        for (int minute = 0; minute < 1440; minute += 30) {
            readings.add(new IntervalReading(date.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), BigDecimal.ONE, Quality.ACTUAL));
        }
        var generated = MODEL.generate(UsageSeries.of(readings));
        assertThat(generated.readings()).hasSize(48);
        assertThat(generated.readings().get(0).start()).isEqualTo(readings.get(0).start());
        assertThat(generated.totalKWh()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void rejectsAnImpossibleArray() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ArrayGeometry(new BigDecimal("-1"), 22, 0, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ArrayGeometry(BigDecimal.TEN, 22, 0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
