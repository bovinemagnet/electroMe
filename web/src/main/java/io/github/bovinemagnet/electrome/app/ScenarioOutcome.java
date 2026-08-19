package io.github.bovinemagnet.electrome.app;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What one scenario would do to the bill.
 *
 * @param baselineBest the cheapest plan's cost today
 * @param scenarioBest the cheapest plan's cost under the scenario, which may be a different
 *     plan; part of the value of a battery is that it changes which tariff suits you
 * @param saving annualised, so a short comparison window is not read as a yearly figure
 */
public record ScenarioOutcome(
        String label,
        Comparison comparison,
        BigDecimal baselineBest,
        BigDecimal scenarioBest,
        BigDecimal saving) {

    public ScenarioOutcome {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(comparison, "comparison");
    }

    public boolean worthwhile() {
        return saving.signum() > 0;
    }

    public String bestPlanName() {
        return comparison.best().map(PlanResult::planName).orElse("");
    }

    /** True when this scenario would move you onto a different tariff. */
    public boolean changesPlan(String baselinePlanName) {
        return !bestPlanName().isBlank() && !bestPlanName().equals(baselinePlanName);
    }
}
