package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What one plan charges, as published, in the vocabulary the comparison already uses.
 *
 * <p>Read from the tariff rather than from a costed bill, and the difference matters. A bill
 * gives cost and quantity, and dividing one by the other on a capped window yields a blended
 * effective rate — a number the retailer never printed and the household cannot check against
 * anything. What a reader sorting by "cheapest overnight rate" means is the published rate.
 *
 * <p>A capped band reports the rate up to its cap, because that is the number the retailer
 * advertises and the reason the window exists; {@link #capped(PlanMatrix.Component)} says so,
 * and the rate beyond the cap is available separately rather than silently averaged in.
 */
public record PlanRates(
        Plan plan,
        Map<PlanMatrix.Component, BigDecimal> rates,
        Map<PlanMatrix.Component, BigDecimal> beyondCap) {

    public PlanRates {
        rates = Map.copyOf(rates);
        beyondCap = Map.copyOf(beyondCap);
    }

    /** Reads a plan's published rates. Cents per kilowatt hour, or per day for supply. */
    public static PlanRates of(Plan plan) {
        var rates = new EnumMap<PlanMatrix.Component, BigDecimal>(PlanMatrix.Component.class);
        var beyond = new EnumMap<PlanMatrix.Component, BigDecimal>(PlanMatrix.Component.class);
        var bands = BandComponent.bandsOf(plan);

        for (var charge : plan.charges()) {
            switch (charge) {
                case DailySupply supply ->
                        rates.put(PlanMatrix.Component.DAILY_SUPPLY, supply.centsPerDay());
                case FlatRate flat ->
                        rates.put(PlanMatrix.Component.FLAT, flat.centsPerKWh());
                case TimeOfUse tou -> {
                    for (var band : tou.bands()) {
                        var component = BandComponent.of(band, bands);
                        // Where two bands share a component — a shoulder either side of the
                        // peak — the cheaper is reported, which is the one a reader chasing a
                        // low rate is asking about.
                        rates.merge(component, band.centsPerKWh(), PlanRates::cheaper);
                        if (band.capped()) {
                            beyond.merge(component,
                                    band.tiers().get(band.tiers().size() - 1).centsPerKWh(),
                                    PlanRates::cheaper);
                        }
                    }
                }
                case Tiered tiered -> {
                    rates.put(PlanMatrix.Component.BLOCK, tiered.tiers().get(0).centsPerKWh());
                    beyond.put(PlanMatrix.Component.BLOCK,
                            tiered.tiers().get(tiered.tiers().size() - 1).centsPerKWh());
                }
                case Demand demand ->
                        rates.put(PlanMatrix.Component.DEMAND, demand.centsPerKWPerDay());
                case ControlledLoad controlled ->
                        rates.put(PlanMatrix.Component.CONTROLLED, controlled.centsPerKWh());
                case SolarFeedIn feedIn ->
                        rates.put(PlanMatrix.Component.FEED_IN, feedIn.centsPerKWh());
                case Discount discount -> {
                    if (discount.basis() == DiscountBasis.PERCENTAGE) {
                        rates.put(PlanMatrix.Component.DISCOUNT, discount.value());
                    }
                }
            }
        }
        return new PlanRates(plan, rates, beyond);
    }

    /** Null where the plan has no such charge, which a table renders as an absence. */
    public BigDecimal rate(PlanMatrix.Component component) {
        return rates.get(component);
    }

    public boolean has(PlanMatrix.Component component) {
        return rates.containsKey(component);
    }

    /** True where this component's rate holds only up to a daily or monthly allowance. */
    public boolean capped(PlanMatrix.Component component) {
        return beyondCap.containsKey(component);
    }

    /** What a unit costs once the allowance is spent, or null where nothing is capped. */
    public BigDecimal beyond(PlanMatrix.Component component) {
        return beyondCap.get(component);
    }

    /**
     * The rate to sort this plan by, for an order over a part of the day.
     *
     * <p>A flat tariff has one rate, and that rate is what it charges in the evening as much
     * as at midnight: ranking it as having no evening peak would answer a different question
     * from the one asked. So a plan without a band for the component falls back to whatever it
     * does charge for energy. A plan that charges nothing comparable sorts last.
     */
    public BigDecimal usageRate(PlanMatrix.Component component) {
        var rate = rates.get(component);
        if (rate != null) {
            return rate;
        }
        if (component == PlanMatrix.Component.DAILY_SUPPLY
                || component == PlanMatrix.Component.FEED_IN) {
            return null;
        }
        var flat = rates.get(PlanMatrix.Component.FLAT);
        return flat != null ? flat : rates.get(PlanMatrix.Component.BLOCK);
    }

    /** The components any of these plans charges, in the vocabulary's own order. */
    public static List<PlanMatrix.Component> columnsFor(List<PlanRates> all) {
        var present = new java.util.LinkedHashSet<PlanMatrix.Component>();
        for (var component : PlanMatrix.Component.values()) {
            for (var rates : all) {
                if (rates.has(component)) {
                    present.add(component);
                    break;
                }
            }
        }
        return List.copyOf(present);
    }

    private static BigDecimal cheaper(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
