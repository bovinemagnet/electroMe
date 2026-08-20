package io.github.bovinemagnet.electrome.core.appliance;

import io.github.bovinemagnet.electrome.core.domain.UsageSeries;

/**
 * Load an appliance would add that is not in the metered data at all.
 *
 * <p>Sealed into two kinds deliberately. A block of energy that must fit inside a window is a
 * scheduling question — the household chooses whether, the software chooses when. Weather-driven
 * load is not: recommending a run time for air conditioning is advice nobody can take, and
 * treating an electric vehicle as unschedulable throws away the question being asked.
 *
 * <p>Distinct from a {@code Scenario}, which only rearranges energy the household already uses.
 */
public sealed interface ApplianceLoad permits SchedulableLoad, ShapedLoad {

    String label();

    /**
     * The series with this appliance's load added.
     *
     * @param schedule when to run; ignored by loads whose timing is not a choice
     */
    UsageSeries addedTo(UsageSeries shape, LoadSchedule schedule);
}
