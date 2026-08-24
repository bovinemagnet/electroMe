package io.github.bovinemagnet.electrome.market.cdr;

import java.util.List;

/**
 * The published detail a {@link io.github.bovinemagnet.electrome.core.tariff.Plan} does not
 * carry.
 *
 * <p>Held beside the plan rather than inside it. {@code core} models what a bill costs; fees and
 * incentives are not costed, and putting them in the tariff model would imply they were.
 *
 * @param marketLinked whether the retailer says the rates are not fixed but follow the wholesale
 *     market. Such a plan still publishes a unit price, because the register has nowhere to put
 *     "it depends", but that price is illustrative and any total costed from it is fiction
 *     dressed as a figure
 * @param priceVariation what the retailer says about how the price moves, verbatim, so a reader
 *     can judge the claim rather than take this classification on trust
 */
public record PlanExtras(
        List<PlanFee> fees,
        List<PlanIncentive> incentives,
        boolean marketLinked,
        String priceVariation) {

    private static final PlanExtras NONE = new PlanExtras(List.of(), List.of(), false, "");

    public PlanExtras {
        fees = fees == null ? List.of() : List.copyOf(fees);
        incentives = incentives == null ? List.of() : List.copyOf(incentives);
        priceVariation = priceVariation == null ? "" : priceVariation.trim();
    }

    /** Fees and incentives alone, for a plan whose price does not follow the market. */
    public PlanExtras(List<PlanFee> fees, List<PlanIncentive> incentives) {
        this(fees, incentives, false, "");
    }

    public static PlanExtras none() {
        return NONE;
    }

    /**
     * Whether there is nothing here worth carrying.
     *
     * <p>The harvest drops empty extras, so the market-linked flag has to count: without it, a
     * wholesale plan publishing no fees would lose its marker on the way out and be ranked as
     * though its illustrative rate were a price.
     */
    public boolean isEmpty() {
        return fees.isEmpty() && incentives.isEmpty() && !marketLinked;
    }
}
