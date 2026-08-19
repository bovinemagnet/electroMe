package io.github.bovinemagnet.electrome.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoadShiftTest {

    private static final LocalDate DAY = LocalDate.of(2025, 1, 1);

    private static UsageData flatDay(String eachKWh) {
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            readings.add(new IntervalReading(DAY.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), new BigDecimal(eachKWh), Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static BigDecimal kWhBetween(UsageData data, int fromMinute, int toMinute) {
        var total = BigDecimal.ZERO;
        for (var reading : data.consumption().readings()) {
            if (reading.minuteOfDay() >= fromMinute && reading.minuteOfDay() < toMinute) {
                total = total.add(reading.kWh());
            }
        }
        return total;
    }

    @Test
    void conservesTotalEnergy() {
        var before = flatDay("1");
        var after = LoadShift.outOfPeak(new BigDecimal("0.3")).applyTo(before);
        assertThat(after.consumption().totalKWh())
                .isEqualByComparingTo(before.consumption().totalKWh());
    }

    @Test
    void removesTheStatedProportionFromTheSourceWindow() {
        // 16:00-21:00 is 10 intervals of 1 kWh = 10 kWh; shifting 30% leaves 7 kWh.
        var after = LoadShift.outOfPeak(new BigDecimal("0.3")).applyTo(flatDay("1"));
        assertThat(kWhBetween(after, 960, 1260)).isEqualByComparingTo("7.0");
    }

    @Test
    void addsTheShiftedEnergyToTheTargetWindow() {
        // Default target is 00:00-06:00, 12 intervals holding 12 kWh, plus 3 kWh shifted.
        var after = LoadShift.outOfPeak(new BigDecimal("0.3")).applyTo(flatDay("1"));
        assertThat(kWhBetween(after, 0, 360)).isEqualByComparingTo("15.0");
    }

    @Test
    void spreadsTheShiftedEnergyEvenlyAcrossTheTarget() {
        // 3 kWh over 12 intervals is 0.25 kWh each, on top of the existing 1 kWh.
        var after = LoadShift.outOfPeak(new BigDecimal("0.3")).applyTo(flatDay("1"));
        var overnight = after.consumption().readings().stream()
                .filter(r -> r.minuteOfDay() < 360).toList();
        assertThat(overnight).hasSize(12);
        assertThat(overnight).allSatisfy(r -> assertThat(r.kWh()).isEqualByComparingTo("1.25"));
    }

    @Test
    void leavesUntouchedWindowsAlone() {
        var before = flatDay("1");
        var after = LoadShift.outOfPeak(new BigDecimal("0.3")).applyTo(before);
        assertThat(kWhBetween(after, 360, 960))
                .isEqualByComparingTo(kWhBetween(before, 360, 960));
    }

    @Test
    void shiftingNothingChangesNothing() {
        var before = flatDay("1");
        var after = LoadShift.outOfPeak(BigDecimal.ZERO).applyTo(before);
        assertThat(after.consumption().readings())
                .containsExactlyElementsOf(before.consumption().readings());
    }

    @Test
    void shiftsIndependentlyPerDay() {
        var readings = new ArrayList<IntervalReading>();
        for (int day = 0; day < 2; day++) {
            for (int minute = 0; minute < 1440; minute += 30) {
                // Day two consumes twice as much as day one.
                readings.add(new IntervalReading(
                        DAY.plusDays(day).atStartOfDay().plusMinutes(minute),
                        Duration.ofMinutes(30), new BigDecimal(day == 0 ? "1" : "2"),
                        Quality.ACTUAL));
            }
        }
        var after = LoadShift.outOfPeak(new BigDecimal("0.5"))
                .applyTo(UsageData.consumptionOnly(UsageSeries.of(readings)));

        var dayTwoPeak = after.consumption().readings().stream()
                .filter(r -> r.date().equals(DAY.plusDays(1)))
                .filter(r -> r.minuteOfDay() >= 960 && r.minuteOfDay() < 1260)
                .map(IntervalReading::kWh)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Day two peak is 20 kWh; half shifted leaves 10.
        assertThat(dayTwoPeak).isEqualByComparingTo("10.0");
    }

    @Test
    void preservesExport() {
        var before = flatDay("1");
        var withExport = new UsageData(before.consumption(), before.consumption());
        var after = LoadShift.outOfPeak(new BigDecimal("0.3")).applyTo(withExport);
        assertThat(after.export().totalKWh())
                .isEqualByComparingTo(withExport.export().totalKWh());
    }

    @Test
    void rejectsAProportionOutsideZeroToOne() {
        assertThatThrownBy(() -> LoadShift.outOfPeak(new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoadShift.outOfPeak(new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void engineAppliesScenariosInOrder() {
        var before = flatDay("1");
        var twice = ScenarioEngine.apply(before, List.of(
                LoadShift.outOfPeak(new BigDecimal("0.5")),
                LoadShift.outOfPeak(new BigDecimal("0.5"))));
        // Peak was 10 kWh; halved twice leaves 2.5.
        assertThat(kWhBetween(twice, 960, 1260)).isEqualByComparingTo("2.5");
        assertThat(twice.consumption().totalKWh())
                .isEqualByComparingTo(before.consumption().totalKWh());
    }

    @Test
    void engineWithNoScenariosReturnsTheSourceUnchanged() {
        var before = flatDay("1");
        assertThat(ScenarioEngine.apply(before, List.of())).isSameAs(before);
    }
}
