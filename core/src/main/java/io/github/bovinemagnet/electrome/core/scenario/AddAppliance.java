package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.appliance.ApplianceLoad;
import io.github.bovinemagnet.electrome.core.appliance.LoadSchedule;
import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import java.util.Objects;

/**
 * Load an appliance would add that is not in the metered data at all.
 *
 * <p>The other scenarios only rearrange energy the household already uses: shifting conserves it,
 * solar offsets it, a battery moves it in time. This one adds it, which is why it needed a new
 * abstraction rather than an extension of an existing one.
 *
 * <p>Applied before solar and before a battery. The appliance changes the load, solar then
 * offsets what it can, and the battery arbitrages the remainder — any other order would have
 * solar offsetting a load that did not yet exist.
 *
 * @param schedule when the appliance runs; null for a load whose timing is not a choice
 */
public record AddAppliance(ApplianceLoad load, LoadSchedule schedule) implements Scenario {

    public AddAppliance {
        Objects.requireNonNull(load, "load");
    }

    @Override
    public String label() {
        if (schedule == null || schedule.empty()) {
            return "Add " + load.label();
        }
        return "Add " + load.label() + ", running " + schedule.describe();
    }

    /**
     * Whether the appliance sits on the controlled circuit.
     *
     * <p>Hot water usually does, and its energy belongs in the controlled series so that it is
     * priced at the controlled rate rather than at the ordinary one.
     */
    public boolean onControlledCircuit() {
        return load instanceof SchedulableLoad schedulable && schedulable.controlledCircuit();
    }

    @Override
    public UsageData applyTo(UsageData source) {
        // A run that cannot fit its window delivers nothing rather than delivering less than
        // was asked for: a cost for energy never supplied would be worse than an explanation.
        if (schedule != null && !schedule.fits()) {
            return source;
        }
        if (onControlledCircuit()) {
            // The controlled series may be empty, in which case the appliance establishes it.
            var shape = source.controlled().isEmpty() ? source.consumption() : source.controlled();
            var added = load.addedTo(shape, schedule);
            var onlyAdded = source.controlled().isEmpty()
                    ? subtract(added, source.consumption())
                    : added;
            return new UsageData(source.consumption(), source.export(), onlyAdded);
        }
        return new UsageData(
                load.addedTo(source.consumption(), schedule), source.export(),
                source.controlled());
    }

    /**
     * The readings an appliance contributed, without the shape it was laid over.
     *
     * <p>A load added to a premises with no controlled circuit has to be scheduled against the
     * household's own daily shape, but only the appliance's own energy belongs on that circuit.
     */
    private static io.github.bovinemagnet.electrome.core.domain.UsageSeries subtract(
            io.github.bovinemagnet.electrome.core.domain.UsageSeries combined,
            io.github.bovinemagnet.electrome.core.domain.UsageSeries original) {
        var originals = new java.util.ArrayList<>(original.readings());
        var kept = new java.util.ArrayList<
                io.github.bovinemagnet.electrome.core.domain.IntervalReading>();
        for (var reading : combined.readings()) {
            if (!originals.remove(reading)) {
                kept.add(reading);
            }
        }
        return io.github.bovinemagnet.electrome.core.domain.UsageSeries.of(kept);
    }
}
