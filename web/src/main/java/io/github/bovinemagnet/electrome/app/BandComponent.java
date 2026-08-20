package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Which part of the day a tariff band belongs to.
 *
 * <p>Shared, because two screens describing the same band differently is worse than either of
 * them being wrong on its own: a reader comparing the matrix against the rate columns would
 * have no way to tell which one to believe.
 *
 * <p>The rule is relative to the plan rather than absolute. There is no rate that is
 * intrinsically a peak rate; a band is the peak because it is the dearest one this tariff
 * charges, and the middle of the day is where a solar-soak rate sits whatever it costs.
 */
public final class BandComponent {

    /** Wholly inside the middle of the day, where a solar-soak rate sits. */
    private static final int DAY_STARTS = 9 * 60;

    private static final int DAY_ENDS = 16 * 60;

    private BandComponent() {}

    /** The bands of the plan's time-of-use charge, or empty for a tariff without one. */
    public static List<Band> bandsOf(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof TimeOfUse tou) {
                return tou.bands();
            }
        }
        return List.of();
    }

    public static boolean hasBlocks(Plan plan) {
        return plan.charges().stream().anyMatch(Tiered.class::isInstance);
    }

    /**
     * Where one band sits in its own plan's day.
     *
     * <p>Compared on the band's first block. A capped window's headline rate is the one it
     * charges up to the cap — the number the retailer advertises and the reason the window
     * exists — so that is what decides whether it reads as the cheap end of the day.
     */
    public static PlanMatrix.Component of(Band band, List<Band> bands) {
        if (daytime(band)) {
            return PlanMatrix.Component.MIDDAY;
        }
        BigDecimal dearest = bands.stream().map(Band::centsPerKWh)
                .max(BigDecimal::compareTo).orElseThrow();
        BigDecimal cheapest = bands.stream().map(Band::centsPerKWh)
                .min(BigDecimal::compareTo).orElseThrow();
        BigDecimal rate = band.centsPerKWh();
        if (rate.compareTo(dearest) == 0) {
            return PlanMatrix.Component.PEAK;
        }
        if (rate.compareTo(cheapest) == 0) {
            return PlanMatrix.Component.OFFPEAK;
        }
        return PlanMatrix.Component.SHOULDER;
    }

    /**
     * The band a bill line was priced by.
     *
     * <p>A line names its window and, on a capped band, which block of it: "Usage 11:00-15:00
     * to 50 kWh". Matching on the window alone gathers every block of a band back onto the one
     * component, which is what a reader means by "the middle of the day".
     */
    public static Optional<Band> bandFor(String lineLabel, List<Band> bands) {
        if (lineLabel == null || !lineLabel.startsWith("Usage ")) {
            return Optional.empty();
        }
        return bands.stream()
                .filter(band -> {
                    String window = "Usage " + band.describe();
                    return lineLabel.equals(window) || lineLabel.startsWith(window + " ");
                })
                .findFirst();
    }

    private static boolean daytime(Band band) {
        return !band.wrapsMidnight()
                && band.fromMinuteOfDay() >= DAY_STARTS
                && band.toMinuteOfDay() <= DAY_ENDS;
    }
}
