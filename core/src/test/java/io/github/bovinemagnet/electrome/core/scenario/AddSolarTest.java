package io.github.bovinemagnet.electrome.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.solar.ArrayGeometry;
import io.github.bovinemagnet.electrome.core.solar.GenerationModel;
import io.github.bovinemagnet.electrome.core.solar.SitePosition;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AddSolarTest {

    private static final LocalDate SUMMER = LocalDate.of(2025, 12, 21);
    private static final AddSolar SIX_POINT_SIX = AddSolar.of(new BigDecimal("6.6"));

    private static UsageData flatDay(LocalDate date, String eachKWh) {
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            readings.add(new IntervalReading(date.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), new BigDecimal(eachKWh), Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    @Test
    void reducesDaytimeImports() {
        var before = flatDay(SUMMER, "1");
        assertThat(SIX_POINT_SIX.applyTo(before).consumption().totalKWh())
                .isLessThan(before.consumption().totalKWh());
    }

    @Test
    void leavesNightIntervalsUntouched() {
        var midnight = SIX_POINT_SIX.applyTo(flatDay(SUMMER, "1")).consumption().readings()
                .stream().filter(r -> r.minuteOfDay() == 0).findFirst().orElseThrow();
        assertThat(midnight.kWh()).isEqualByComparingTo("1");
    }

    @Test
    void createsExportWhenGenerationExceedsConsumption() {
        assertThat(SIX_POINT_SIX.applyTo(flatDay(SUMMER, "0.1")).export().totalKWh())
                .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void nettingIsPerIntervalNotDaily() {
        // Heavy consumption at night, almost none during the day. Daily netting would
        // wrongly cancel the midday surplus against the overnight load.
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            readings.add(new IntervalReading(SUMMER.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30),
                    new BigDecimal(minute < 360 ? "3" : "0.05"), Quality.ACTUAL));
        }
        var after = SIX_POINT_SIX.applyTo(UsageData.consumptionOnly(UsageSeries.of(readings)));

        var overnight = after.consumption().readings().stream()
                .filter(r -> r.minuteOfDay() < 360)
                .map(IntervalReading::kWh)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(overnight).isEqualByComparingTo("36");
        assertThat(after.export().totalKWh()).isGreaterThan(new BigDecimal("10"));
    }

    @Test
    void consumptionNeverGoesNegative() {
        assertThat(SIX_POINT_SIX.applyTo(flatDay(SUMMER, "0.01")).consumption().readings())
                .allSatisfy(r -> assertThat(r.kWh().signum()).isGreaterThanOrEqualTo(0));
    }

    @Test
    void energyBalanceHolds() {
        // consumption after + generation = consumption before + export after
        var before = flatDay(SUMMER, "0.5");
        var after = SIX_POINT_SIX.applyTo(before);
        var generation = new GenerationModel(
                        SitePosition.melbourne(), ArrayGeometry.typical(new BigDecimal("6.6")))
                .generate(before.consumption()).totalKWh();

        var left = after.consumption().totalKWh().add(generation);
        var right = before.consumption().totalKWh().add(after.export().totalKWh());
        assertThat(left.subtract(right).abs()).isLessThan(new BigDecimal("0.0001"));
    }

    @Test
    void addsToExistingExportRatherThanReplacingIt() {
        var before = flatDay(SUMMER, "0.1");
        var existing = UsageSeries.of(before.consumption().readings());
        var after = SIX_POINT_SIX.applyTo(new UsageData(before.consumption(), existing));
        assertThat(after.export().totalKWh()).isGreaterThan(existing.totalKWh());
    }

    @Test
    void aBiggerArrayExportsMore() {
        var small = AddSolar.of(new BigDecimal("3")).applyTo(flatDay(SUMMER, "0.1"));
        var large = AddSolar.of(new BigDecimal("10")).applyTo(flatDay(SUMMER, "0.1"));
        assertThat(large.export().totalKWh()).isGreaterThan(small.export().totalKWh());
    }

    @Test
    void labelNamesTheArraySize() {
        assertThat(SIX_POINT_SIX.label()).contains("6.6");
    }
}
