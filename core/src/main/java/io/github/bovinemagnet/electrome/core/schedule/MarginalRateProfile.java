package io.github.bovinemagnet.electrome.core.schedule;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one more kilowatt hour would cost, in each half hour of the day.
 *
 * <p>This is the shortcut that makes scheduling affordable. Trying every start time by
 * re-costing the whole bill for every plan is hundreds of millions of interval operations; the
 * marginal cost of adding energy in a half hour is simply the tariff's rate in that half hour,
 * so the same search is a scan over 48 numbers.
 *
 * <p><strong>Solar changes the answer, and must.</strong> For a household exporting at midday
 * the marginal cost of consuming then is not the import rate — it is the feed-in rate forgone,
 * typically a few cents against forty or fifty. That is the whole reason "charge at midday" is
 * right for a solar household and wrong without one.
 *
 * @param rates 48 entries, index 0 being 00:00 to 00:30; a null entry means the circuit is not
 *     available in that half hour
 * @param inexactBecause why the marginal rate is not the true marginal cost, or null when it is
 */
public record MarginalRateProfile(
        List<BigDecimal> rates, DaySelector days, String inexactBecause) {

    public static final int SLOTS = 48;

    public MarginalRateProfile {
        if (rates.size() != SLOTS) {
            throw new IllegalArgumentException(
                    "A rate profile needs " + SLOTS + " slots, got " + rates.size());
        }
        rates = Collections.unmodifiableList(new ArrayList<>(rates));
    }

    /**
     * The ordinary import rates for a plan, adjusted for what the household already exports.
     *
     * @param usage the series being scheduled against, so that a surplus half hour is priced as
     *     forgone feed-in rather than as import
     */
    public static MarginalRateProfile of(Plan plan, UsageData usage, DaySelector days) {
        var exporting = exportingSlots(usage);
        var feedIn = feedInCents(plan);
        var rates = new ArrayList<BigDecimal>(SLOTS);

        for (int slot = 0; slot < SLOTS; slot++) {
            rates.add(exporting[slot] ? feedIn : importCents(plan, slot, days));
        }
        return new MarginalRateProfile(rates, days, inexactBecause(plan));
    }

    /**
     * The controlled circuit's own rate, for an appliance assigned to it.
     *
     * <p>Outside the energised window the circuit is not available at all, which is an absence
     * rather than an expensive slot: the scheduler must not be free to choose it.
     */
    public static MarginalRateProfile controlled(Plan plan, DaySelector days) {
        ControlledLoad charge = null;
        for (var candidate : plan.charges()) {
            if (candidate instanceof ControlledLoad found) {
                charge = found;
                break;
            }
        }
        var rates = new ArrayList<BigDecimal>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) {
            if (charge == null) {
                rates.add(null);
                continue;
            }
            rates.add(availableAt(charge, slot) ? charge.centsPerKWh() : null);
        }
        return new MarginalRateProfile(rates, days, inexactBecause(plan));
    }

    public BigDecimal rateAt(int slot) {
        return rates.get(slot);
    }

    public boolean availableAt(int slot) {
        return rates.get(slot) != null;
    }

    public boolean exact() {
        return inexactBecause == null;
    }

    /** The dearest rate less the cheapest: the size of the decision about when to run. */
    public BigDecimal spread() {
        BigDecimal cheapest = null;
        BigDecimal dearest = null;
        for (var rate : rates) {
            if (rate == null) {
                continue;
            }
            cheapest = cheapest == null || rate.compareTo(cheapest) < 0 ? rate : cheapest;
            dearest = dearest == null || rate.compareTo(dearest) > 0 ? rate : dearest;
        }
        return cheapest == null ? BigDecimal.ZERO : dearest.subtract(cheapest);
    }

    /**
     * Whether when you run something changes what it costs.
     *
     * <p>On a flat tariff it does not, and the honest answer is to say so rather than to report
     * an arbitrary slot as though it had been chosen.
     */
    public boolean timingMatters() {
        return spread().signum() > 0;
    }

    public int cheapestSlot() {
        int best = -1;
        for (int slot = 0; slot < SLOTS; slot++) {
            var rate = rates.get(slot);
            if (rate == null) {
                continue;
            }
            if (best < 0 || rate.compareTo(rates.get(best)) < 0) {
                best = slot;
            }
        }
        return best;
    }

    // -----------------------------------------------------------------

    private static boolean availableAt(ControlledLoad charge, int slot) {
        if (!charge.windowed()) {
            return true;
        }
        return new Band(charge.fromMinuteOfDay(), charge.toMinuteOfDay(), DaySelector.ALL,
                        charge.centsPerKWh())
                .matchesTime(slot * 30);
    }

    /** The import rate for one half hour, or zero where the plan prices no usage at all. */
    private static BigDecimal importCents(Plan plan, int slot, DaySelector days) {
        int minute = slot * 30;
        for (var charge : plan.charges()) {
            if (charge instanceof TimeOfUse tou) {
                for (var band : tou.bands()) {
                    if (band.matchesTime(minute) && appliesOn(band.days(), days)) {
                        return band.centsPerKWh();
                    }
                }
            }
        }
        for (var charge : plan.charges()) {
            if (charge instanceof FlatRate flat) {
                return flat.centsPerKWh();
            }
            if (charge instanceof Tiered tiered) {
                // The block a household spends most of its time in. Only an estimate, which is
                // why a block tariff is reported as inexact.
                return tiered.tiers().get(0).centsPerKWh();
            }
        }
        return BigDecimal.ZERO;
    }

    /** A band restricted to weekdays does not price a weekend half hour, and vice versa. */
    private static boolean appliesOn(DaySelector bandDays, DaySelector wanted) {
        return bandDays == DaySelector.ALL || wanted == DaySelector.ALL || bandDays == wanted;
    }

    private static BigDecimal feedInCents(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof SolarFeedIn feedIn) {
                return feedIn.centsPerKWh();
            }
        }
        // Nothing is forgone by consuming energy that would have been exported for nothing.
        return BigDecimal.ZERO;
    }

    /** Half hours in which the household exports on average, so consuming displaces a sale. */
    private static boolean[] exportingSlots(UsageData usage) {
        var exporting = new boolean[SLOTS];
        if (usage == null || usage.export().isEmpty()) {
            return exporting;
        }
        var totals = new BigDecimal[SLOTS];
        java.util.Arrays.fill(totals, BigDecimal.ZERO);
        for (IntervalReading reading : usage.export().readings()) {
            int slot = Math.min(SLOTS - 1, reading.minuteOfDay() / 30);
            totals[slot] = totals[slot].add(reading.kWh());
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            exporting[slot] = totals[slot].signum() > 0;
        }
        return exporting;
    }

    /**
     * Whether the marginal rate is the true marginal cost.
     *
     * <p>It is, for flat and time-of-use tariffs. It is not for block rates, whose marginal rate
     * depends on cumulative consumption within the reset period, nor for demand charges, where
     * added load costs nothing or a great deal depending on whether it lifts the peak. A
     * usage-scoped discount scales every rate equally and so does not change which slot wins.
     */
    private static String inexactBecause(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof Tiered) {
                return "This plan has block rates, so what a unit costs depends on how much you "
                        + "have already used in the period. The chosen time may be slightly off; "
                        + "the cost shown is a full costing and is exact.";
            }
            if (charge instanceof Demand) {
                return "This plan has a demand charge, so added load costs nothing or a great "
                        + "deal depending on whether it raises your monthly peak. The chosen "
                        + "time may be slightly off; the cost shown is a full costing and is "
                        + "exact.";
            }
        }
        return null;
    }
}
