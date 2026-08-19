package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.solar.ArrayGeometry;
import io.github.bovinemagnet.electrome.core.solar.GenerationModel;
import io.github.bovinemagnet.electrome.core.solar.SitePosition;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

/**
 * Nets photovoltaic generation against consumption.
 *
 * <p>Netting happens within each interval. Generation first offsets consumption in the same
 * half hour; only the surplus is exported and only the shortfall imported.
 *
 * <p>That granularity is the whole point. Netting daily would treat a midday surplus and an
 * evening import as cancelling, when the tariff does the opposite: imported energy in the
 * evening peak costs around 50c per kWh while exported energy earns a few cents. Coarse
 * netting overstates the value of solar by a large factor.
 */
public record AddSolar(SitePosition site, ArrayGeometry array) implements Scenario {

    public AddSolar {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(array, "array");
    }

    /** A typical north-facing array of the given size, in Melbourne. */
    public static AddSolar of(BigDecimal sizeKW) {
        return new AddSolar(SitePosition.melbourne(), ArrayGeometry.typical(sizeKW));
    }

    @Override
    public String label() {
        return "Add " + array.sizeKW().stripTrailingZeros().toPlainString() + " kW of solar";
    }

    @Override
    public UsageData applyTo(UsageData source) {
        var model = new GenerationModel(site, array);
        var generation = model.generate(source.consumption());

        // Index existing export so a site that already has solar can model adding more.
        var existingExport = new HashMap<LocalDateTime, BigDecimal>();
        for (var reading : source.export().readings()) {
            existingExport.merge(reading.start(), reading.kWh(), BigDecimal::add);
        }

        var netConsumption = new ArrayList<IntervalReading>();
        var netExport = new ArrayList<IntervalReading>();

        var generated = generation.readings();
        var consumed = source.consumption().readings();
        for (int i = 0; i < consumed.size(); i++) {
            var reading = consumed.get(i);
            BigDecimal produced = generated.get(i).kWh();

            BigDecimal imported = reading.kWh().subtract(produced).max(BigDecimal.ZERO);
            BigDecimal surplus = produced.subtract(reading.kWh()).max(BigDecimal.ZERO);
            BigDecimal exported =
                    surplus.add(existingExport.getOrDefault(reading.start(), BigDecimal.ZERO));

            netConsumption.add(new IntervalReading(
                    reading.start(), reading.length(), imported, reading.quality()));
            if (exported.signum() > 0) {
                netExport.add(new IntervalReading(
                        reading.start(), reading.length(), exported, Quality.ACTUAL));
            }
        }

        return new UsageData(UsageSeries.of(netConsumption), UsageSeries.of(netExport));
    }
}
