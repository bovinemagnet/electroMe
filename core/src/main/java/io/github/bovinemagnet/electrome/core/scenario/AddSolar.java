package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.domain.UsageData;


/** Replaced in task 4. */
public record AddSolar() implements Scenario {
    @Override public String label() { return "solar (not yet implemented)"; }
    @Override public UsageData applyTo(UsageData source) { return source; }
}
