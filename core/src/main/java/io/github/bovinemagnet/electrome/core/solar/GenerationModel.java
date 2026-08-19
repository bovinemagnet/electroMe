package io.github.bovinemagnet.electrome.core.solar;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Clear-sky photovoltaic output for a site.
 *
 * <p>There is no weather in this model. Output on any given day is what a cloudless sky would
 * deliver, so a single day's figure is optimistic and should be read as an upper bound. Over a
 * year the shape is what matters for a tariff comparison, and the shape is right: generation
 * peaks near solar noon and has largely finished by the time an evening peak window opens.
 */
public final class GenerationModel {

    private final SitePosition site;
    private final ArrayGeometry array;

    public GenerationModel(SitePosition site, ArrayGeometry array) {
        this.site = Objects.requireNonNull(site, "site");
        this.array = Objects.requireNonNull(array, "array");
    }

    /** Energy generated across one interval, in kWh. */
    public BigDecimal generationKWh(LocalDateTime start, Duration length) {
        // Sample at the interval midpoint rather than its start: over half an hour near
        // sunrise the difference is material, and the midpoint is the unbiased choice.
        LocalDateTime midpoint = start.plus(length.dividedBy(2));

        double elevation = SolarGeometry.elevationDegrees(midpoint, site);
        if (elevation <= 0) {
            return BigDecimal.ZERO;
        }

        double directNormal = SolarGeometry.clearSkyDirectNormalKW(elevation);
        double solarAzimuth = SolarGeometry.azimuthDegrees(midpoint, site);

        double tilt = Math.toRadians(array.tiltDegrees());
        double elevationRadians = Math.toRadians(elevation);
        double azimuthDifference = Math.toRadians(solarAzimuth - array.azimuthDegrees());

        double cosIncidence =
                Math.sin(elevationRadians) * Math.cos(tilt)
                        + Math.cos(elevationRadians) * Math.sin(tilt) * Math.cos(azimuthDifference);

        double beam = directNormal * Math.max(0.0, cosIncidence);
        double diffuse =
                0.1 * directNormal * Math.sin(elevationRadians) * (1 + Math.cos(tilt)) / 2.0;
        double planeOfArray = beam + diffuse;

        // Rated at 1 kW/m2 under standard test conditions; clipped at the array rating to
        // represent the inverter.
        double sizeKW = array.sizeKW().doubleValue();
        double outputKW = Math.min(sizeKW, sizeKW * planeOfArray * array.performanceRatio());

        double hours = length.toSeconds() / 3600.0;
        return new BigDecimal(outputKW * hours, MathContext.DECIMAL64).max(BigDecimal.ZERO);
    }

    /**
     * Generation aligned to the intervals of a supplied series.
     *
     * <p>Taking the shape from an existing series guarantees generation and consumption share
     * timestamps exactly, so netting them never requires interpolation.
     */
    public UsageSeries generate(UsageSeries shape) {
        var generated = new ArrayList<IntervalReading>(shape.readings().size());
        for (var reading : shape.readings()) {
            generated.add(new IntervalReading(
                    reading.start(),
                    reading.length(),
                    generationKWh(reading.start(), reading.length()),
                    Quality.ACTUAL));
        }
        return UsageSeries.of(generated);
    }
}
