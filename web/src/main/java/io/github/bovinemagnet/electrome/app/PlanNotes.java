package io.github.bovinemagnet.electrome.app;

import java.util.List;
import java.util.Set;

/**
 * What a ranked row has to say beyond its total.
 *
 * <p>A column of dollar figures invites the reader to take the smallest one. Everything that
 * would make that the wrong move — equipment the plan requires, a discount that has to be
 * earned, a fee nobody costed, energy the tariff did not price — travels with the row rather
 * than living in a legend somewhere below it.
 *
 * @param requirements what the household must own or join to be offered the plan
 * @param eligibility the retailer's own words for those requirements, for a tooltip
 * @param uncostedFees true when the plan publishes fees the total does not include
 * @param fullyPriced false when some of the household's energy fell outside every charge the
 *     plan defines, which makes its total a floor rather than a figure
 * @param shortlisted true when the household has picked this plan out of the market
 * @param withdrawn true when the register no longer publishes it and the tariff shown is the
 *     last thing the retailer did publish
 */
public record PlanNotes(
        Set<Requirement> requirements,
        List<String> eligibility,
        boolean uncostedFees,
        boolean fullyPriced,
        boolean shortlisted,
        boolean withdrawn) {

    private static final PlanNotes PLAIN =
            new PlanNotes(Set.of(), List.of(), false, true, false, false);

    /** Notes from before a plan could be picked. */
    public PlanNotes(Set<Requirement> requirements, List<String> eligibility,
            boolean uncostedFees, boolean fullyPriced) {
        this(requirements, eligibility, uncostedFees, fullyPriced, false, false);
    }

    public PlanNotes {
        requirements = requirements == null ? Set.of() : Set.copyOf(requirements);
        eligibility = eligibility == null ? List.of() : List.copyOf(eligibility);
    }

    /** A plan anyone can take, with nothing left out of its total. */
    public static PlanNotes plain() {
        return PLAIN;
    }

    public boolean hasRequirements() {
        return !requirements.isEmpty();
    }

    /** The requirements as chips, in a stable order. */
    public List<Requirement> chips() {
        return requirements.stream().sorted().toList();
    }

    public String eligibilityText() {
        return String.join(" ", eligibility);
    }

    /** True when anything about this row deserves a second look before acting on its total. */
    public boolean qualified() {
        return hasRequirements() || uncostedFees || !fullyPriced || withdrawn;
    }
}
