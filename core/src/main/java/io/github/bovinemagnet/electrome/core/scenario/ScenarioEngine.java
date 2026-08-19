package io.github.bovinemagnet.electrome.core.scenario;

import io.github.bovinemagnet.electrome.core.domain.UsageData;
import java.util.List;

/** Applies scenarios in order, each seeing the output of the last. */
public final class ScenarioEngine {

    private ScenarioEngine() {}

    /**
     * Ordering matters. Solar before battery is correct: the battery should charge and
     * discharge against consumption that solar has already offset, not against gross load.
     */
    public static UsageData apply(UsageData source, List<Scenario> scenarios) {
        var current = source;
        for (var scenario : scenarios) {
            current = scenario.applyTo(current);
        }
        return current;
    }
}
