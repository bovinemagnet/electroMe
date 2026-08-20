package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Every plan costed over one window, cheapest first.
 *
 * @param baselinePlanId the household's own tariff, against which a saving is a saving; null
 *     when none is configured, in which case plans are only ranked against each other
 */
public record Comparison(DateRange range, List<PlanResult> results, String baselinePlanId) {

    public Comparison {
        results = List.copyOf(results);
    }

    /** A comparison with no household tariff to measure against. */
    public Comparison(DateRange range, List<PlanResult> results) {
        this(range, results, null);
    }

    /** The household's own tariff, when it is among the plans costed. */
    public Optional<PlanResult> baseline() {
        return results.stream().filter(PlanResult::baseline).findFirst();
    }

    public boolean empty() {
        return results.isEmpty();
    }

    public Optional<PlanResult> best() {
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** The gap between the cheapest and the dearest plan: the size of the decision. */
    public BigDecimal spread() {
        if (results.size() < 2) {
            return BigDecimal.ZERO;
        }
        return results.get(results.size() - 1).total().subtract(results.get(0).total());
    }

    /** The largest single plan total, so bars across rows share one scale. */
    public BigDecimal maxTotal() {
        return results.stream()
                .map(PlanResult::total)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ONE);
    }
}
