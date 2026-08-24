package io.github.bovinemagnet.electrome.core.schedule;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.HolidayCalendar;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @param caps 48 entries, non-null where the slot's rate only holds until a daily or monthly
 *     cap is spent
 */
public record MarginalRateProfile(
        List<BigDecimal> rates, DaySelector days, String inexactBecause, List<Cap> caps) {

    public static final int SLOTS = 48;

    /**
     * The cap that limits a slot's cheap rate.
     *
     * <p>A capped window's rate is not a number but a schedule: free until the day's allowance
     * is gone, then dearer. Slots of the same band share one allowance, which is what
     * {@code band} identifies, and the household's own consumption has usually spent part of it
     * before the appliance starts — which is what {@code headroomKWh} already accounts for.
     *
     * @param headroomKWh what is left of the cap on a typical day, after the household's own use
     */
    public record Cap(String band, BigDecimal headroomKWh, BigDecimal balanceCentsPerKWh) {}

    public MarginalRateProfile {
        if (rates.size() != SLOTS) {
            throw new IllegalArgumentException(
                    "A rate profile needs " + SLOTS + " slots, got " + rates.size());
        }
        if (caps.size() != SLOTS) {
            throw new IllegalArgumentException(
                    "A rate profile needs " + SLOTS + " cap slots, got " + caps.size());
        }
        rates = Collections.unmodifiableList(new ArrayList<>(rates));
        caps = Collections.unmodifiableList(new ArrayList<>(caps));
    }

    /** A profile whose rates hold however much is consumed. */
    public MarginalRateProfile(List<BigDecimal> rates, DaySelector days, String inexactBecause) {
        this(rates, days, inexactBecause, Collections.nCopies(SLOTS, null));
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
        var headroom = headroomPerCappedBand(plan, usage);
        var rates = new ArrayList<BigDecimal>(SLOTS);
        var caps = new ArrayList<Cap>(SLOTS);

        for (int slot = 0; slot < SLOTS; slot++) {
            if (exporting[slot]) {
                // Consuming displaces a sale, so no import cap is in play at all.
                rates.add(feedIn);
                caps.add(null);
                continue;
            }
            rates.add(importCents(plan, slot, days));
            caps.add(capAt(plan, slot, days, headroom));
        }
        return new MarginalRateProfile(rates, days, inexactBecause(plan), caps);
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

    /** The cap limiting this slot's rate, or null when the rate holds whatever is consumed. */
    public Cap capAt(int slot) {
        return caps.get(slot);
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

    /**
     * What a kilowatt hour of export is worth, for deciding when running an appliance is dearest.
     *
     * <p>The lowest rate the plan pays, not the best. This figure is what consuming instead of
     * exporting gives up, and assuming the household always forgoes the evening rate would
     * overstate the cost of running a dishwasher at noon on a plan that pays nothing at noon.
     */
    private static BigDecimal feedInCents(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof SolarFeedIn feedIn) {
                return feedIn.lowestRate();
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
        Arrays.fill(totals, BigDecimal.ZERO);
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
     * <p>It is, for flat and uncapped time-of-use tariffs. It is not for block rates, whose
     * marginal rate depends on cumulative consumption within the reset period, nor for a
     * capped band, whose rate depends on how much of the cap the day has already spent, nor
     * for demand charges, where added load costs nothing or a great deal depending on whether
     * it lifts the peak. A usage-scoped discount scales every rate equally and so does not
     * change which slot wins.
     */
    private static String inexactBecause(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof TimeOfUse tou) {
                for (var band : tou.bands()) {
                    if (band.capped()) {
                        return cappedBandNote(band);
                    }
                }
            }
        }
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

    /** The cap in force in one half hour, with the household's own use already deducted. */
    private static Cap capAt(
            Plan plan, int slot, DaySelector days, Map<Band, BigDecimal> headroom) {
        int minute = slot * 30;
        for (var charge : plan.charges()) {
            if (!(charge instanceof TimeOfUse tou)) {
                continue;
            }
            for (var band : tou.bands()) {
                if (band.matchesTime(minute) && appliesOn(band.days(), days)) {
                    if (!band.capped()) {
                        return null;
                    }
                    return new Cap(
                            band.describe(),
                            headroom.getOrDefault(band, band.tiers().get(0).thresholdKWh()),
                            band.tiers().get(1).centsPerKWh());
                }
            }
        }
        return null;
    }

    /**
     * What is left of each cap on a typical day, once the household's own use is counted.
     *
     * <p>An electric vehicle does not get the whole free window: the house, the pool and
     * everything else already inside that window spend part of it first. Scheduling against
     * the published cap rather than the headroom would price a session as free that is not.
     *
     * <p>A typical day, not a specific one, because the profile answers "when should this run"
     * for every day at once. A day heavier than usual spends the cap sooner, which is what
     * {@link #inexactBecause()} says.
     */
    private static Map<Band, BigDecimal> headroomPerCappedBand(Plan plan, UsageData usage) {
        var headroom = new LinkedHashMap<Band, BigDecimal>();
        if (usage == null || usage.consumption().isEmpty()) {
            return headroom;
        }
        var holidays = HolidayCalendar.none();

        for (var charge : plan.charges()) {
            if (!(charge instanceof TimeOfUse tou)) {
                continue;
            }
            for (var band : tou.bands()) {
                if (!band.capped()) {
                    continue;
                }
                var perDay = new HashMap<LocalDate, BigDecimal>();
                var allDays = new HashSet<LocalDate>();
                for (var reading : usage.consumption().readings()) {
                    allDays.add(reading.date());
                    if (band.matches(reading, holidays)) {
                        perDay.merge(reading.date(), reading.kWh(), BigDecimal::add);
                    }
                }
                var total = BigDecimal.ZERO;
                for (var kWh : perDay.values()) {
                    total = total.add(kWh);
                }
                var mean = allDays.isEmpty()
                        ? BigDecimal.ZERO
                        : total.divide(BigDecimal.valueOf(allDays.size()),
                                MathContext.DECIMAL64);
                var left = band.tiers().get(0).thresholdKWh().subtract(mean);
                headroom.put(band, left.signum() < 0 ? BigDecimal.ZERO : left);
            }
        }
        return headroom;
    }

    /** Names the cap, its window and what a unit costs once it is spent. */
    private static String cappedBandNote(Band band) {
        var first = band.tiers().get(0);
        var next = band.tiers().get(1);
        return band.describe() + " is capped at "
                + first.thresholdKWh().stripTrailingZeros().toPlainString() + " kWh/"
                + band.reset().noun() + "; beyond it a unit costs "
                + next.centsPerKWh().stripTrailingZeros().toPlainString()
                + "c. The cost above deducts what your own household typically uses in that "
                + "window, but a heavier day than usual, or a second appliance scheduled "
                + "separately, will spend the cap sooner and cost more.";
    }
}
