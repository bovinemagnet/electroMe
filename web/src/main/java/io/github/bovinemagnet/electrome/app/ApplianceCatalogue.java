package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.appliance.ApplianceLoad;
import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.appliance.ShapedLoad;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Month;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Appliances a household can add, with defaults it would recognise.
 *
 * <p>Nobody knows their pool pump's draw. The presets carry real figures so the screen can be
 * useful before anything is typed, and every field stays editable.
 *
 * <p>The electric vehicle is expressed the way a driver thinks — kilometres a week and
 * consumption per hundred kilometres — and converted to kilowatt hours here. A household knows
 * its weekly mileage; it does not know its nightly kilowatt-hours.
 */
public final class ApplianceCatalogue {

    /** What the screen offers, and what each preset defaults to. */
    public record Preset(
            String id,
            String name,
            String explanation,
            BigDecimal powerKW,
            BigDecimal hoursPerRun,
            int availableFromMinute,
            int deadlineMinute,
            int runsPerWeek,
            Set<Month> months,
            boolean interruptible,
            boolean controlledCircuit,
            boolean shaped) {

        public BigDecimal energyPerRunKWh() {
            return powerKW.multiply(hoursPerRun, MathContext.DECIMAL64);
        }
    }

    /** A driver's units: how far, and how thirsty. */
    public static final BigDecimal DEFAULT_KM_PER_WEEK = new BigDecimal("250");

    public static final BigDecimal DEFAULT_KWH_PER_HUNDRED_KM = new BigDecimal("16");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final Map<String, Preset> PRESETS = presets();

    private ApplianceCatalogue() {}

    private static Map<String, Preset> presets() {
        var presets = new LinkedHashMap<String, Preset>();

        presets.put("ev", new Preset("ev", "Electric vehicle",
                "A weekday top-up, charged while the car is plugged in overnight. A charger can "
                        + "pause and resume, so the cheapest half hours are taken wherever they "
                        + "fall.",
                new BigDecimal("7.4"), new BigDecimal("1.5"),
                18 * 60, 7 * 60, 5, Set.of(), true, false, false));

        presets.put("pool-pump", new Preset("pool-pump", "Pool pump",
                "Filtration over the swimming season. A pump runs a set number of hours a day "
                        + "and cannot usefully be interrupted.",
                new BigDecimal("1.1"), new BigDecimal("6"),
                0, 24 * 60, 7,
                EnumSet.of(Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY,
                        Month.MARCH),
                false, false, false));

        presets.put("pool-heater", new Preset("pool-heater", "Pool heat pump",
                "Heating over a longer season than filtration, and by far the heaviest load "
                        + "here.",
                new BigDecimal("5"), new BigDecimal("8"),
                0, 24 * 60, 7,
                EnumSet.of(Month.OCTOBER, Month.NOVEMBER, Month.DECEMBER, Month.JANUARY,
                        Month.FEBRUARY, Month.MARCH, Month.APRIL),
                false, false, false));

        presets.put("hot-water", new Preset("hot-water", "Hot water",
                "On the controlled circuit, which the retailer energises for a stated window "
                        + "and prices at its own, cheaper rate.",
                new BigDecimal("3.6"), new BigDecimal("3"),
                0, 24 * 60, 7, Set.of(), false, true, false));

        presets.put("air-conditioning", new Preset("air-conditioning", "More air conditioning",
                "Weather-driven, so there is no run time to recommend: you cool the house when "
                        + "it is hot. Your data already contains some cooling, so this models "
                        + "using it more rather than adding it from nothing.",
                new BigDecimal("3.5"), new BigDecimal("4"),
                0, 24 * 60, 7, Set.of(), false, false, true));

        return Map.copyOf(presets);
    }

    public static List<Preset> all() {
        return List.copyOf(presets().values());
    }

    public static Preset preset(String id) {
        var preset = PRESETS.get(id == null ? "" : id.toLowerCase(Locale.ROOT).trim());
        return preset == null ? PRESETS.get("ev") : preset;
    }

    /** Kilometres a week at a stated efficiency, as energy for one charge. */
    public static BigDecimal chargeKWh(
            BigDecimal kmPerWeek, BigDecimal kWhPerHundredKm, int runsPerWeek) {
        if (runsPerWeek <= 0) {
            return BigDecimal.ZERO;
        }
        return kmPerWeek.multiply(kWhPerHundredKm, MathContext.DECIMAL64)
                .divide(HUNDRED, MathContext.DECIMAL64)
                .divide(BigDecimal.valueOf(runsPerWeek), MathContext.DECIMAL64);
    }

    /**
     * A preset with the household's own figures applied.
     *
     * <p>Anything not supplied keeps the preset's default, so a reader who changes one field
     * does not have to restate the rest.
     */
    public static ApplianceLoad build(Preset preset, BigDecimal energyPerRunKWh,
            BigDecimal powerKW, Integer availableFromMinute, Integer deadlineMinute,
            Integer runsPerWeek) {

        if (preset.shaped()) {
            return airConditioning(preset, energyPerRunKWh);
        }
        return new SchedulableLoad(
                preset.name(),
                energyPerRunKWh == null || energyPerRunKWh.signum() <= 0
                        ? preset.energyPerRunKWh()
                        : energyPerRunKWh,
                powerKW == null || powerKW.signum() <= 0 ? preset.powerKW() : powerKW,
                availableFromMinute == null ? preset.availableFromMinute() : availableFromMinute,
                deadlineMinute == null ? preset.deadlineMinute() : deadlineMinute,
                runsPerWeek == null ? preset.runsPerWeek() : runsPerWeek,
                preset.months(),
                preset.interruptible(),
                preset.controlledCircuit());
    }

    /**
     * Cooling: summer-weighted and afternoon-peaked.
     *
     * <p>The shape is deliberately crude. It exists to put the load where cooling actually
     * happens rather than to model a building.
     */
    private static ShapedLoad airConditioning(Preset preset, BigDecimal dailyKWh) {
        var shape = new ArrayList<BigDecimal>(48);
        for (int slot = 0; slot < 48; slot++) {
            int hour = slot / 2;
            BigDecimal weight;
            if (hour >= 13 && hour < 21) {
                weight = new BigDecimal("3");
            } else if (hour >= 10 && hour < 13) {
                weight = new BigDecimal("1.5");
            } else if (hour >= 21 || hour < 2) {
                weight = new BigDecimal("1");
            } else {
                weight = new BigDecimal("0.2");
            }
            shape.add(weight);
        }

        var perDay = dailyKWh == null || dailyKWh.signum() <= 0
                ? preset.energyPerRunKWh()
                : dailyKWh;

        // Cooling in summer, a little in the shoulder seasons, none in winter.
        var seasonal = Map.of(
                ShapedLoad.Season.SUMMER, perDay.multiply(new BigDecimal("90")),
                ShapedLoad.Season.AUTUMN, perDay.multiply(new BigDecimal("18")),
                ShapedLoad.Season.WINTER, BigDecimal.ZERO,
                ShapedLoad.Season.SPRING, perDay.multiply(new BigDecimal("18")));

        return new ShapedLoad(preset.name(), shape, seasonal);
    }
}
