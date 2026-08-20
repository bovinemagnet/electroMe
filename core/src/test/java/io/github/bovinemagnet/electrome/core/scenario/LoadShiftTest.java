package io.github.bovinemagnet.electrome.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
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

    // -----------------------------------------------------------------
    // An arbitrary target window: a capped free window is four hours in the middle of the
    // day, not six overnight, and whether moving load into it pays is the whole question.
    // -----------------------------------------------------------------

    @Test
    void shiftsIntoAnyNamedWindow() {
        // 16:00-21:00 holds 10 kWh; all of it lands in 11:00-15:00, which held 8 kWh.
        var after = LoadShift.into(660, 900, BigDecimal.ONE).applyTo(flatDay("1"));
        assertThat(kWhBetween(after, 960, 1260)).isEqualByComparingTo("0");
        assertThat(kWhBetween(after, 660, 900)).isEqualByComparingTo("18.0");
    }

    @Test
    void conservesEnergyIntoAnArbitraryWindow() {
        var before = flatDay("1");
        var after = LoadShift.into(660, 900, BigDecimal.ONE).applyTo(before);
        assertThat(after.consumption().totalKWh())
                .isEqualByComparingTo(before.consumption().totalKWh());
    }

    @Test
    void conservesEnergyIntoAWindowThatWrapsMidnight() {
        // A car left on charge from 21:00 to 06:00 is the commonest overnight shift there is,
        // and 5 kWh across its 18 half hours has no exact decimal share: the day's last
        // interval must take the remainder or the scenario loses energy and calls it a saving.
        var before = flatDay("1");
        var after = LoadShift.into(1260, 360, new BigDecimal("0.5")).applyTo(before);
        assertThat(after.consumption().totalKWh())
                .isEqualByComparingTo(before.consumption().totalKWh());
        assertThat(kWhBetween(after, 960, 1260)).isEqualByComparingTo("5.0");
    }

    @Test
    void namesTheWindowItShiftsInto() {
        assertThat(LoadShift.into(660, 900, BigDecimal.ONE).label())
                .isEqualTo("Shift 100% of peak load into 11:00-15:00");
    }

    @Test
    void rejectsATargetWindowWithNoWidth() {
        assertThatThrownBy(() -> LoadShift.into(660, 660, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no width");
    }

    /**
     * The point of the exercise: load moved into a capped free window is priced at nothing,
     * until the cap runs out.
     */
    @Test
    void loadShiftedIntoAFreeWindowIsPricedAtTheFreeRate() {
        var plan = new Plan("globird", "4 Hour Free", "GloBird", DistributionZone.AUSNET,
                List.of(new DailySupply(BigDecimal.ZERO), new TimeOfUse(List.of(
                        Band.parseTiered("11:00", "15:00", DaySelector.ALL, ResetPeriod.DAILY,
                                List.of(new Tier(new BigDecimal("50"), BigDecimal.ZERO),
                                        new Tier(null, new BigDecimal("9.405")))),
                        Band.parseTiered("15:00", "11:00", DaySelector.ALL, ResetPeriod.DAILY,
                                List.of(new Tier(new BigDecimal("15"), new BigDecimal("31.559")),
                                        new Tier(null, new BigDecimal("33.963"))))))),
                true, null, null);

        var engine = new CostingEngine();
        var range = new DateRange(DAY, DAY);
        var before = engine.cost(flatDay("1"), plan, range);
        var after = engine.cost(
                LoadShift.into(660, 900, BigDecimal.ONE).applyTo(flatDay("1")), plan, range);

        // The whole day's 18 kWh of free-window energy sits under the 50 kWh cap, at nothing.
        var free = after.lines().stream()
                .filter(l -> l.label().equals("Usage 11:00-15:00 to 50 kWh"))
                .findFirst().orElseThrow();
        assertThat(free.quantity()).isEqualByComparingTo("18");
        assertThat(free.cost()).isEqualByComparingTo("0");
        assertThat(after.totalRounded()).isLessThan(before.totalRounded());
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
