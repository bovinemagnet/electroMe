package io.github.bovinemagnet.electrome.core.appliance;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Weather-driven load whose timing is not a choice.
 *
 * <p>Air conditioning and space heating. Modelling these as schedulable would invite a
 * recommendation nobody can act on: you do not run the air conditioner at 3am because it is
 * cheap, you run it when the house is hot.
 *
 * @param profileByHalfHour 48 relative weights, normalised on construction
 * @param seasonalKWh how much energy the appliance uses in each season
 */
public record ShapedLoad(
        String label,
        List<BigDecimal> profileByHalfHour,
        Map<Season, BigDecimal> seasonalKWh) implements ApplianceLoad {

    /** Australian seasons, which is how a household thinks about heating and cooling. */
    public enum Season {
        SUMMER,
        AUTUMN,
        WINTER,
        SPRING;

        public static Season of(LocalDate date) {
            return switch (date.getMonth()) {
                case DECEMBER, JANUARY, FEBRUARY -> SUMMER;
                case MARCH, APRIL, MAY -> AUTUMN;
                case JUNE, JULY, AUGUST -> WINTER;
                case SEPTEMBER, OCTOBER, NOVEMBER -> SPRING;
            };
        }

        /** Days in the season, for spreading a seasonal total across its days. */
        public int days() {
            return switch (this) {
                case SUMMER -> 90;
                case AUTUMN -> 92;
                case WINTER -> 92;
                case SPRING -> 91;
            };
        }
    }

    public ShapedLoad {
        Objects.requireNonNull(label, "label");
        if (profileByHalfHour.size() != 48) {
            throw new IllegalArgumentException(
                    "A shape needs 48 half hours, got " + profileByHalfHour.size());
        }
        profileByHalfHour = normalise(profileByHalfHour);
        seasonalKWh = Map.copyOf(seasonalKWh);
    }

    /**
     * Weights scaled to sum to one, so a shape describes distribution and nothing else.
     *
     * <p>Without this, editing the shape would silently change how much energy the appliance
     * uses as well as when it uses it.
     */
    private static List<BigDecimal> normalise(List<BigDecimal> weights) {
        var total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("A shape must have some load in it");
        }
        var scaled = new ArrayList<BigDecimal>(weights.size());
        for (var weight : weights) {
            scaled.add(weight.divide(total, MathContext.DECIMAL64));
        }
        return List.copyOf(scaled);
    }

    public BigDecimal annualKWh() {
        return seasonalKWh.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public UsageSeries addedTo(UsageSeries shape, LoadSchedule schedule) {
        var added = new ArrayList<IntervalReading>(shape.readings());
        for (LocalDate day : shape.billingDays()) {
            var season = Season.of(day);
            var seasonTotal = seasonalKWh.get(season);
            if (seasonTotal == null || seasonTotal.signum() == 0) {
                continue;
            }
            var perDay = seasonTotal.divide(
                    BigDecimal.valueOf(season.days()), MathContext.DECIMAL64);
            for (int slot = 0; slot < 48; slot++) {
                var kWh = perDay.multiply(profileByHalfHour.get(slot), MathContext.DECIMAL64);
                if (kWh.signum() == 0) {
                    continue;
                }
                added.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT)
                                .plusMinutes(slot * 30L),
                        Duration.ofMinutes(30), kWh, Quality.ESTIMATED));
            }
        }
        return UsageSeries.of(added);
    }
}
