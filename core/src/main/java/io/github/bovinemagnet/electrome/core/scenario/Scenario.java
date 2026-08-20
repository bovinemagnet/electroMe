package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.domain.UsageData;

/**
 * A pure transformation of a usage series.
 *
 * <p>Every scenario is costed by the unchanged costing engine, so a scenario and its baseline
 * can never disagree about arithmetic. Implementations must be deterministic and must not
 * depend on the current date or any external service.
 */
public sealed interface Scenario permits LoadShift, AddSolar, AddBattery, AddAppliance {

    /** Human-readable description for the comparison table. */
    String label();

    UsageData applyTo(UsageData source);
}
