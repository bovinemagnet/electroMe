package io.github.bovinemagnet.electrome.core.appliance;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * When an appliance runs: which half hours, and how much energy in each.
 *
 * <p>The decision, separated from the appliance so that the same appliance can be scheduled
 * differently on different tariffs — which is the point of the exercise.
 *
 * @param energyBySlot half-hour index to kWh delivered in it
 * @param fits false when the window cannot deliver the energy asked for
 * @param shortfallKWh what could not be delivered, zero when it fits
 * @param workableDeadlineMinute the earliest deadline that would work, or null when it fits
 */
public record LoadSchedule(
        SortedMap<Integer, BigDecimal> energyBySlot,
        BigDecimal marginalCostCents,
        boolean fits,
        BigDecimal shortfallKWh,
        Integer workableDeadlineMinute,
        boolean timingMatters) {

    public LoadSchedule {
        energyBySlot = Collections.unmodifiableSortedMap(new TreeMap<>(energyBySlot));
    }

    /** Nothing scheduled, because the window cannot deliver what was asked for. */
    public static LoadSchedule cannotFit(
            BigDecimal shortfallKWh, int workableDeadlineMinute, BigDecimal deliverable) {
        return new LoadSchedule(new TreeMap<>(), BigDecimal.ZERO, false, shortfallKWh,
                workableDeadlineMinute, true);
    }

    public List<Integer> slots() {
        return List.copyOf(energyBySlot.keySet());
    }

    public BigDecimal deliveredKWh() {
        return energyBySlot.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean empty() {
        return energyBySlot.isEmpty();
    }

    /** The first half hour the appliance runs in, as a clock time. */
    public String startTime() {
        return empty() ? "" : clock(energyBySlot.firstKey());
    }

    /**
     * The run described the way a timer would be set.
     *
     * <p>An interruptible load may occupy slots that are not adjacent, so this reads as a range
     * only when they are; otherwise it names the hours involved.
     */
    public String describe() {
        if (empty()) {
            return "not scheduled";
        }
        var slots = slots();
        boolean contiguous = slots.get(slots.size() - 1) - slots.get(0) == slots.size() - 1;
        if (contiguous) {
            return clock(slots.get(0)) + " to " + clock((slots.get(slots.size() - 1) + 1) % 48);
        }
        var parts = new java.util.ArrayList<String>();
        for (var slot : slots) {
            parts.add(clock(slot));
        }
        return String.join(", ", parts);
    }

    private static String clock(int slot) {
        return String.format(Locale.ROOT, "%02d:%02d", slot / 2, slot % 2 == 0 ? 0 : 30);
    }
}
