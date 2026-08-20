package io.github.bovinemagnet.electrome.app;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * The one sentence the ranking exists to produce.
 *
 * <p>A table of two hundred tariffs is evidence, not an answer. The answer is which plan the
 * household should be on and what it is worth, and everything that qualifies it — a discount
 * that has to be earned, a fee that was not costed, plans that could not be priced at all —
 * belongs in the same breath rather than in a footnote below the fold.
 *
 * @param best the cheapest plan the household could actually sign up to, or null when nothing
 *     was costed
 * @param requirements what that plan asks for, which by definition the household has
 * @param cheaperThanEverythingIneligible false when some plan the household cannot have is
 *     cheaper still, which the sentence has to admit rather than quietly omit
 * @param unpriceable how many plans the harvester could not price, which is the size of the
 *     blind spot behind this answer
 */
public record Verdict(
        PlanResult best,
        Set<Requirement> requirements,
        boolean cheaperThanEverythingIneligible,
        int unpriceable,
        List<String> unpriceableReasons) {

    public Verdict {
        requirements = requirements == null ? Set.of() : Set.copyOf(requirements);
        unpriceableReasons = unpriceableReasons == null ? List.of()
                : List.copyOf(unpriceableReasons);
    }

    public static Verdict none() {
        return new Verdict(null, Set.of(), true, 0, List.of());
    }

    public boolean known() {
        return best != null;
    }

    public String planName() {
        return best.planName();
    }

    public String retailer() {
        return best.retailer();
    }

    public BigDecimal total() {
        return best.total();
    }

    /** Whether there is a household tariff to measure the winner against. */
    public boolean comparedToBaseline() {
        return best.comparedToBaseline();
    }

    public boolean savesMoney() {
        return best.cheaperThanBaseline();
    }

    public BigDecimal saving() {
        return best.savingAgainstBaseline();
    }

    public boolean isCurrentPlan() {
        return best.baseline();
    }

    /** True when the winning total holds only if the household earns a discount. */
    public boolean assumesDiscountCondition() {
        return best.bill().assumesConditions();
    }

    public String discountConditions() {
        return best.bill().discountConditionsText();
    }

    public boolean hasRequirements() {
        return !requirements.isEmpty();
    }

    public boolean hasUnpriceable() {
        return unpriceable > 0;
    }

    /** Anything that makes the headline figure less than the whole story. */
    public boolean qualified() {
        return assumesDiscountCondition() || hasRequirements()
                || !cheaperThanEverythingIneligible;
    }
}
