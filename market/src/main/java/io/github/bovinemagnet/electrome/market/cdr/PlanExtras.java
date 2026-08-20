package io.github.bovinemagnet.electrome.market.cdr;

import java.util.List;

/**
 * The published detail a {@link io.github.bovinemagnet.electrome.core.tariff.Plan} does not
 * carry.
 *
 * <p>Held beside the plan rather than inside it. {@code core} models what a bill costs; fees and
 * incentives are not costed, and putting them in the tariff model would imply they were.
 */
public record PlanExtras(List<PlanFee> fees, List<PlanIncentive> incentives) {

    private static final PlanExtras NONE = new PlanExtras(List.of(), List.of());

    public PlanExtras {
        fees = fees == null ? List.of() : List.copyOf(fees);
        incentives = incentives == null ? List.of() : List.copyOf(incentives);
    }

    public static PlanExtras none() {
        return NONE;
    }

    public boolean isEmpty() {
        return fees.isEmpty() && incentives.isEmpty();
    }
}
