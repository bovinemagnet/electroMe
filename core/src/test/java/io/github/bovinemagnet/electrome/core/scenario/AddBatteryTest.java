package io.github.bovinemagnet.electrome.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AddBatteryTest {

    private static final LocalDate DAY = LocalDate.of(2025, 1, 1);

    private static final AddBattery ARBITRAGE = new AddBattery(
            BatterySpecification.typical(),
            List.of(Band.parse("00:00", "06:00", DaySelector.ALL, new BigDecimal("4.99"))),
            List.of(Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("49.54"))));

    private static UsageData flatDays(int days, String eachKWh) {
        var readings = new ArrayList<IntervalReading>();
        for (int day = 0; day < days; day++) {
            for (int minute = 0; minute < 1440; minute += 30) {
                readings.add(new IntervalReading(
                        DAY.plusDays(day).atStartOfDay().plusMinutes(minute),
                        Duration.ofMinutes(30), new BigDecimal(eachKWh), Quality.ACTUAL));
            }
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
    void reducesImportsDuringTheDischargeWindow() {
        var before = flatDays(1, "1");
        assertThat(kWhBetween(ARBITRAGE.applyTo(before), 960, 1260))
                .isLessThan(kWhBetween(before, 960, 1260));
    }

    @Test
    void increasesImportsDuringTheChargeWindow() {
        var before = flatDays(1, "1");
        assertThat(kWhBetween(ARBITRAGE.applyTo(before), 0, 360))
                .isGreaterThan(kWhBetween(before, 0, 360));
    }

    @Test
    void totalImportRisesBecauseOfRoundTripLosses() {
        // A battery moves energy, it does not create it, and storage costs energy. Total grid
        // import must rise even though the bill falls.
        var before = flatDays(2, "1");
        assertThat(ARBITRAGE.applyTo(before).consumption().totalKWh())
                .isGreaterThan(before.consumption().totalKWh());
    }

    @Test
    void neverExceedsThePowerLimit() {
        var after = ARBITRAGE.applyTo(flatDays(1, "0.1"));
        // 5 kW over half an hour is 2.5 kWh, plus the 0.1 kWh baseline consumption.
        assertThat(after.consumption().readings())
                .allSatisfy(r -> assertThat(r.kWh()).isLessThanOrEqualTo(new BigDecimal("2.61")));
    }

    @Test
    void neverExceedsCapacity() {
        // Consumption is tiny, so the battery would charge indefinitely if capacity were not
        // enforced: six charge hours at 5 kW could take 30 kWh against a 13.5 kWh capacity.
        var before = flatDays(1, "0.01");
        var stored = kWhBetween(ARBITRAGE.applyTo(before), 0, 360)
                .subtract(kWhBetween(before, 0, 360));
        assertThat(stored).isLessThanOrEqualTo(new BigDecimal("15.5"));
    }

    @Test
    void cannotDischargeMoreThanWasStored() {
        var before = flatDays(1, "5");
        var displaced = kWhBetween(before, 960, 1260)
                .subtract(kWhBetween(ARBITRAGE.applyTo(before), 960, 1260));
        assertThat(displaced).isLessThanOrEqualTo(new BigDecimal("13.5"));
    }

    @Test
    void doesNothingOutsideBothWindows() {
        var before = flatDays(1, "1");
        assertThat(kWhBetween(ARBITRAGE.applyTo(before), 360, 960))
                .isEqualByComparingTo(kWhBetween(before, 360, 960));
    }

    private static UsageData withMiddayExport() {
        var readings = new ArrayList<IntervalReading>();
        var exportReadings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            readings.add(new IntervalReading(DAY.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), new BigDecimal("0.1"), Quality.ACTUAL));
            if (minute >= 600 && minute < 840) {
                exportReadings.add(new IntervalReading(DAY.atStartOfDay().plusMinutes(minute),
                        Duration.ofMinutes(30), new BigDecimal("2"), Quality.ACTUAL));
            }
        }
        return new UsageData(UsageSeries.of(readings), UsageSeries.of(exportReadings));
    }

    @Test
    void surplusExportChargesTheBatteryBeforeBeingExported() {
        // No grid-charge window, so the battery's only source is the midday surplus.
        var solarOnly = new AddBattery(BatterySpecification.typical(), List.of(),
                List.of(Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("49.54"))));
        var before = withMiddayExport();
        assertThat(solarOnly.applyTo(before).export().totalKWh())
                .isLessThan(before.export().totalKWh());
    }

    @Test
    void aBatteryFilledOvernightCannotAbsorbTheMiddaySurplus() {
        // A documented limitation, not a defect. This controller charges to full in its cheap
        // window, so by the time solar peaks there is no headroom left and the surplus is
        // exported. Real units avoid this with a solar forecast; a rule-based one cannot.
        var before = withMiddayExport();
        assertThat(ARBITRAGE.applyTo(before).export().totalKWh())
                .isEqualByComparingTo(before.export().totalKWh());
    }

    @Test
    void rechargesEachDayRatherThanExhaustingItself() {
        // Consumption heavy enough that the battery cannot cover a whole peak window, so the
        // displaced energy is measurable. Two days must displace close to twice one day's.
        var oneDay = flatDays(1, "5");
        var twoDays = flatDays(2, "5");
        var displacedOne = kWhBetween(oneDay, 960, 1260)
                .subtract(kWhBetween(ARBITRAGE.applyTo(oneDay), 960, 1260));
        var displacedTwo = kWhBetween(twoDays, 960, 1260)
                .subtract(kWhBetween(ARBITRAGE.applyTo(twoDays), 960, 1260));
        assertThat(displacedOne).isGreaterThan(new BigDecimal("10"));
        assertThat(displacedTwo).isGreaterThan(displacedOne.multiply(new BigDecimal("1.8")));
    }

    @Test
    void arbitragingDerivesWindowsFromATariff() {
        var tariff = new TimeOfUse(List.of(
                Band.parse("00:00", "06:00", DaySelector.ALL, new BigDecimal("4.99")),
                Band.parse("06:00", "16:00", DaySelector.ALL, new BigDecimal("24.77")),
                Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("49.54")),
                Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("24.77"))));
        var battery = AddBattery.arbitraging(BatterySpecification.typical(), tariff);

        assertThat(battery.chargeWindows()).hasSize(1);
        assertThat(battery.chargeWindows().get(0).centsPerKWh()).isEqualByComparingTo("4.99");
        assertThat(battery.dischargeWindows()).hasSize(1);
        assertThat(battery.dischargeWindows().get(0).centsPerKWh()).isEqualByComparingTo("49.54");
    }

    @Test
    void onewayEfficiencyIsTheSquareRootOfTheRoundTrip() {
        var spec = BatterySpecification.typical();
        var oneway = spec.onewayEfficiency();
        assertThat(oneway.multiply(oneway).doubleValue()).isCloseTo(0.90, within(0.0001));
    }

    @Test
    void labelNamesTheCapacity() {
        assertThat(ARBITRAGE.label()).contains("13.5");
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
