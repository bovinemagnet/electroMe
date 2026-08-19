package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.HolidayCalendar;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * A rule-based battery controller: charge cheap, discharge dear.
 *
 * <p>Deliberately not an optimiser. A perfect-foresight optimiser would report a saving no
 * real installation achieves, which is worse than useless for a purchase decision. This is a
 * simple deterministic controller of the kind a real unit actually runs.
 *
 * <p>A battery does not conserve energy. Round-trip losses mean total grid import rises even
 * as the bill falls, and the model says so rather than hiding it.
 *
 * <p>Known limitation, pinned by a test: with both a grid-charge window and solar, the battery
 * fills overnight and has no headroom left when the midday surplus arrives, so that surplus is
 * exported rather than stored. Real units avoid this by reserving capacity against a solar
 * forecast. A rule-based controller has no forecast, and inventing one here would report a
 * saving no real installation achieves.
 */
public record AddBattery(
        BatterySpecification specification,
        List<Band> chargeWindows,
        List<Band> dischargeWindows)
        implements Scenario {

    public AddBattery {
        Objects.requireNonNull(specification, "specification");
        chargeWindows = List.copyOf(chargeWindows);
        dischargeWindows = List.copyOf(dischargeWindows);
    }

    /** Charge in the tariff's cheapest band, discharge in its dearest. */
    public static AddBattery arbitraging(BatterySpecification specification, TimeOfUse tariff) {
        var cheapest = tariff.bands().stream()
                .min(Comparator.comparing(Band::centsPerKWh))
                .orElseThrow(() -> new IllegalArgumentException("Tariff has no bands"));
        var dearest = tariff.bands().stream()
                .max(Comparator.comparing(Band::centsPerKWh))
                .orElseThrow(() -> new IllegalArgumentException("Tariff has no bands"));
        return new AddBattery(specification, List.of(cheapest), List.of(dearest));
    }

    @Override
    public String label() {
        return "Add a " + specification.capacityKWh().stripTrailingZeros().toPlainString()
                + " kWh battery";
    }

    @Override
    public UsageData applyTo(UsageData source) {
        var holidays = HolidayCalendar.none();
        var exportByStart = new HashMap<LocalDateTime, BigDecimal>();
        for (var reading : source.export().readings()) {
            exportByStart.merge(reading.start(), reading.kWh(), BigDecimal::add);
        }

        BigDecimal stateOfCharge = BigDecimal.ZERO;
        BigDecimal oneway = specification.onewayEfficiency();

        var newConsumption = new ArrayList<IntervalReading>();
        var newExport = new ArrayList<IntervalReading>();

        for (var reading : source.consumption().readings()) {
            BigDecimal hours = BigDecimal.valueOf(reading.length().toSeconds())
                    .divide(BigDecimal.valueOf(3600), MathContext.DECIMAL64);
            BigDecimal maxThroughput = specification.powerKW().multiply(hours);

            BigDecimal consumption = reading.kWh();
            BigDecimal export = exportByStart.getOrDefault(reading.start(), BigDecimal.ZERO);

            // Surplus export charges the battery before leaving the premises.
            if (export.signum() > 0) {
                BigDecimal headroom = specification.capacityKWh().subtract(stateOfCharge);
                BigDecimal absorbed = export.min(maxThroughput)
                        .min(headroom.divide(oneway, MathContext.DECIMAL64));
                if (absorbed.signum() > 0) {
                    stateOfCharge = stateOfCharge.add(absorbed.multiply(oneway));
                    export = export.subtract(absorbed);
                }
            }

            if (matches(dischargeWindows, reading, holidays)) {
                BigDecimal available = stateOfCharge.multiply(oneway).min(maxThroughput);
                BigDecimal supplied = available.min(consumption);
                if (supplied.signum() > 0) {
                    consumption = consumption.subtract(supplied);
                    stateOfCharge =
                            stateOfCharge.subtract(supplied.divide(oneway, MathContext.DECIMAL64));
                }
            } else if (matches(chargeWindows, reading, holidays)) {
                BigDecimal headroom = specification.capacityKWh().subtract(stateOfCharge);
                BigDecimal drawn =
                        maxThroughput.min(headroom.divide(oneway, MathContext.DECIMAL64));
                if (drawn.signum() > 0) {
                    consumption = consumption.add(drawn);
                    stateOfCharge = stateOfCharge.add(drawn.multiply(oneway));
                }
            }

            newConsumption.add(new IntervalReading(reading.start(), reading.length(),
                    consumption.max(BigDecimal.ZERO), reading.quality()));
            if (export.signum() > 0) {
                newExport.add(new IntervalReading(
                        reading.start(), reading.length(), export, Quality.ACTUAL));
            }
        }

        return new UsageData(UsageSeries.of(newConsumption), UsageSeries.of(newExport));
    }

    private static boolean matches(
            List<Band> windows, IntervalReading reading, HolidayCalendar holidays) {
        for (var window : windows) {
            if (window.matches(reading, holidays)) {
                return true;
            }
        }
        return false;
    }
}
