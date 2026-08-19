package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Every plan costed over one window, cheapest first. */
public record Comparison(DateRange range, List<PlanResult> results) {

    public Comparison {
        results = List.copyOf(results);
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
