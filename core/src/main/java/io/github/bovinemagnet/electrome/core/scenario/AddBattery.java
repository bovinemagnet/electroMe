package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.domain.UsageData;

/** Replaced in task 5. */
public record AddBattery() implements Scenario {
    @Override public String label() { return "battery (not yet implemented)"; }
    @Override public UsageData applyTo(UsageData source) { return source; }
}
