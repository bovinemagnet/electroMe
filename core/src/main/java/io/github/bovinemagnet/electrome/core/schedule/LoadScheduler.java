package io.github.bovinemagnet.electrome.core.schedule;

import io.github.bovinemagnet.electrome.core.appliance.LoadSchedule;
import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Chooses when an appliance runs, given what each half hour costs.
 *
 * <p>Because the marginal cost of adding energy in a half hour is the tariff's rate in that half
 * hour, this is a scan over 48 numbers rather than 48 re-costings. That is what makes optimising
 * against every plan at once affordable.
 *
 * <p>The schedule is <em>chosen</em> by marginal rate; the cost the screen reports comes from a
 * full costing of that schedule. So the reported figure is always exact, and only the choice of
 * slot can be marginally off — on block and demand tariffs, which say so.
 */
public final class LoadScheduler {

    private static final int SLOTS = MarginalRateProfile.SLOTS;
    private static final int MINUTES_PER_SLOT = 30;

    private LoadScheduler() {}

    public static LoadSchedule schedule(SchedulableLoad load, MarginalRateProfile profile) {
        var window = windowSlots(load, profile);
        int needed = load.slotsNeeded();

        if (window.size() < needed) {
            var deliverable = load.kWhPerSlot().multiply(BigDecimal.valueOf(window.size()));
            return LoadSchedule.cannotFit(
                    load.energyPerRunKWh().subtract(deliverable),
                    workableDeadline(load, needed),
                    deliverable);
        }

        var chosen = load.interruptible()
                ? cheapestSlots(window, profile, needed)
                : cheapestStretch(window, profile, needed);

        return build(load, profile, chosen);
    }

    /**
     * The half hours the appliance may run in, in window order.
     *
     * <p>A window running past midnight is normal for a car left on charge overnight, so the
     * sequence wraps. Half hours the profile marks unavailable — a controlled circuit that is
     * not energised — are excluded rather than merely expensive.
     */
    private static List<Integer> windowSlots(SchedulableLoad load, MarginalRateProfile profile) {
        int from = Math.floorMod(load.availableFromMinute() / MINUTES_PER_SLOT, SLOTS);
        int until = Math.floorMod(load.deadlineMinute() / MINUTES_PER_SLOT, SLOTS);
        int length = until > from ? until - from : SLOTS - from + until;
        if (length == 0) {
            length = SLOTS;
        }

        var slots = new ArrayList<Integer>(length);
        for (int step = 0; step < length; step++) {
            int slot = (from + step) % SLOTS;
            if (profile.availableAt(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** The cheapest half hours, wherever they fall: what a charger that can pause achieves. */
    private static List<Integer> cheapestSlots(
            List<Integer> window, MarginalRateProfile profile, int needed) {
        var byPrice = new ArrayList<>(window);
        // Ties resolve towards the earlier half hour, so the answer is stable rather than
        // depending on the order the window happened to be built in.
        byPrice.sort(Comparator
                .<Integer, BigDecimal>comparing(profile::rateAt)
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(byPrice.subList(0, needed));
    }

    /**
     * The cheapest unbroken stretch, for an appliance that cannot pause.
     *
     * <p>Consecutive within the window, which is what lets the run cross midnight when the
     * window does.
     */
    private static List<Integer> cheapestStretch(
            List<Integer> window, MarginalRateProfile profile, int needed) {
        BigDecimal best = null;
        int bestStart = 0;

        for (int start = 0; start + needed <= window.size(); start++) {
            var sum = BigDecimal.ZERO;
            boolean contiguous = true;
            for (int offset = 0; offset < needed; offset++) {
                // Availability gaps break a stretch: a run that cannot pause cannot skip one.
                if (offset > 0 && !adjacent(window.get(start + offset - 1),
                        window.get(start + offset))) {
                    contiguous = false;
                    break;
                }
                sum = sum.add(profile.rateAt(window.get(start + offset)));
            }
            if (contiguous && (best == null || sum.compareTo(best) < 0)) {
                best = sum;
                bestStart = start;
            }
        }
        return List.copyOf(window.subList(bestStart, bestStart + needed));
    }

    private static boolean adjacent(int earlier, int later) {
        return (earlier + 1) % SLOTS == later;
    }

    /**
     * Energy across the chosen half hours, and what the profile says it costs.
     *
     * <p>An appliance drawing full power finishes part way through its last half hour. Which
     * half hour is left short is a real choice, and leaving the dearest one short is what
     * least-cost scheduling means.
     */
    private static LoadSchedule build(
            SchedulableLoad load, MarginalRateProfile profile, List<Integer> chosen) {

        var perSlot = load.kWhPerSlot();
        var remaining = load.energyPerRunKWh();

        var order = new ArrayList<>(chosen);
        order.sort(Comparator.comparing(profile::rateAt));

        var energy = new TreeMap<Integer, BigDecimal>();
        var cost = BigDecimal.ZERO;
        for (var slot : order) {
            if (remaining.signum() <= 0) {
                break;
            }
            var delivered = remaining.min(perSlot);
            energy.put(slot, delivered);
            cost = cost.add(delivered.multiply(profile.rateAt(slot)));
            remaining = remaining.subtract(delivered);
        }

        return new LoadSchedule(energy, cost, true, BigDecimal.ZERO, null,
                profile.timingMatters());
    }

    /** The earliest deadline that would deliver the whole run, for a window that cannot. */
    private static int workableDeadline(SchedulableLoad load, int needed) {
        return Math.floorMod(
                load.availableFromMinute() + needed * MINUTES_PER_SLOT, 24 * 60);
    }
}
