package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.core.cost.ChargeKind;
import io.github.bovinemagnet.electrome.core.cost.ChargeLine;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Assigns a colour to each bill line.
 *
 * <p>Usage bands are coloured by what they MEAN, not by an arbitrary series index, so the same
 * hue means the same thing across every chart and every plan: red is the expensive evening
 * window, amber the middle-of-day solar window, green the cheapest window.
 *
 * <p>The daytime test comes first deliberately. Under the Victorian Default Offer the 11am-4pm
 * solar window is also the cheapest rate, and colouring it green would break the correspondence
 * with the same window on a tariff where it is not the cheapest.
 */
public final class BandPalette {

    public static final String PEAK = "peak";
    public static final String MIDDAY = "midday";
    public static final String SHOULDER = "shoulder";
    public static final String OFFPEAK = "offpeak";
    public static final String SUPPLY = "supply";
    public static final String CREDIT = "accent";

    private static final int DAYTIME_FROM = 10 * 60;
    private static final int DAYTIME_TO = 16 * 60;

    private BandPalette() {}

    /** A CSS custom-property reference, for markup the browser styles directly. */
    public static String cssVar(String token) {
        return "var(--" + token + ")";
    }

    /**
     * A deferred reference for chart options.
     *
     * <p>ECharts is handed JSON and cannot read CSS custom properties, so colours travel as
     * "@token" and the page script resolves them against the stylesheet at render time. That
     * also means charts follow the light and dark schemes without a second palette.
     */
    public static String chartRef(String token) {
        return "@" + token;
    }

    public static String colourFor(Plan plan, ChargeLine line) {
        return tokenFor(plan, line);
    }

    public static String tokenFor(Plan plan, ChargeLine line) {
        return switch (line.kind()) {
            case SUPPLY -> SUPPLY;
            case DEMAND -> PEAK;
            case FEED_IN, DISCOUNT -> CREDIT;
            case USAGE -> usageColour(plan, line);
        };
    }

    private static String usageColour(Plan plan, ChargeLine line) {
        var bands = timeOfUseBands(plan);
        if (bands.isEmpty() || line.rateCents() == null) {
            return SHOULDER;
        }
        var band = bands.stream()
                .filter(b -> ("Usage " + b.describe()).equals(line.label()))
                .findFirst();
        if (band.isPresent() && daytime(band.get())) {
            return MIDDAY;
        }

        BigDecimal dearest = bands.stream().map(Band::centsPerKWh)
                .max(BigDecimal::compareTo).orElseThrow();
        BigDecimal cheapest = bands.stream().map(Band::centsPerKWh)
                .min(BigDecimal::compareTo).orElseThrow();
        if (line.rateCents().compareTo(dearest) == 0) {
            return PEAK;
        }
        if (line.rateCents().compareTo(cheapest) == 0) {
            return OFFPEAK;
        }
        return SHOULDER;
    }

    /** True when the band sits wholly inside the middle of the day. */
    private static boolean daytime(Band band) {
        return !band.wrapsMidnight()
                && band.fromMinuteOfDay() >= DAYTIME_FROM
                && band.toMinuteOfDay() <= DAYTIME_TO;
    }

    public static List<Band> timeOfUseBands(Plan plan) {
        if (plan == null) {
            return List.of();
        }
        return plan.charges().stream()
                .filter(TimeOfUse.class::isInstance)
                .map(c -> ((TimeOfUse) c).bands())
                .findFirst()
                .orElse(List.of());
    }

    /** The band a viewer is being steered towards understanding: the dearest one. */
    public static Optional<Band> dearestBand(Plan plan) {
        return timeOfUseBands(plan).stream().max(java.util.Comparator.comparing(Band::centsPerKWh));
    }
}
