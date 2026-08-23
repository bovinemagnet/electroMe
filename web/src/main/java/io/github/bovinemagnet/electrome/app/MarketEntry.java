package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.ingest.PlanLibrary;
import io.github.bovinemagnet.electrome.market.cdr.PlanExtras;
import java.math.BigDecimal;
import java.util.List;

/**
 * One published plan, as the market screen shows it: what it charges, and what saving it would
 * do to the plans directory.
 *
 * <p>The second half is the part the plan browser cannot say. A reader deciding what is worth
 * keeping needs to know whether they already have it and whether what they have is still what
 * the retailer publishes — a saved plan quietly going stale is the failure this screen exists
 * to make visible.
 *
 * @param total what this plan would have cost over the window, priced from what the register
 *     publishes now rather than from any file already on disk; null when no usage is loaded
 * @param differenceFromBaseline what it costs above the household's own tariff, negative when
 *     it is cheaper; null when no usage is loaded or no baseline plan is configured
 */
public record MarketEntry(
        Plan plan,
        PlanRates rates,
        PlanLibrary.Outcome outcome,
        String fileName,
        BigDecimal total,
        BigDecimal differenceFromBaseline,
        List<String> requirements,
        PlanExtras extras) {

    public MarketEntry {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        extras = extras == null ? PlanExtras.none() : extras;
    }

    /** Whether this plan has been priced against the household's own consumption. */
    public boolean costed() {
        return total != null;
    }

    /** Whether there is a plan of the household's own to measure this one against. */
    public boolean comparedToBaseline() {
        return differenceFromBaseline != null;
    }

    /** What switching to it would have saved: positive when it beats the household's plan. */
    public BigDecimal saving() {
        return differenceFromBaseline == null ? BigDecimal.ZERO : differenceFromBaseline.negate();
    }

    public boolean cheaperThanBaseline() {
        return differenceFromBaseline != null && differenceFromBaseline.signum() < 0;
    }

    public boolean isBaseline() {
        return differenceFromBaseline != null && differenceFromBaseline.signum() == 0;
    }

    public String id() {
        return plan.id();
    }

    public String planName() {
        return plan.name();
    }

    public String retailer() {
        return plan.retailer();
    }

    public String shape() {
        return switch (PlanQuery.PlanShape.of(plan)) {
            case FLAT -> "Flat rate";
            case TIME_OF_USE -> "Time of use";
            case BLOCK -> "Block";
            case DEMAND -> "Demand";
            case ANY -> "Unpriced";
        };
    }

    /** Whether a file in the plans directory already holds this published plan. */
    public boolean held() {
        return outcome == PlanLibrary.Outcome.UNCHANGED
                || outcome == PlanLibrary.Outcome.REPLACED;
    }

    /** Held, but what the retailer publishes today is no longer what the file says. */
    public boolean stale() {
        return outcome == PlanLibrary.Outcome.REPLACED;
    }

    public String statusLabel() {
        return switch (outcome) {
            case UNCHANGED -> "Held";
            case REPLACED -> "Rates moved";
            case NEW, RENAMED -> "Not held";
        };
    }

    public String statusNote() {
        return switch (outcome) {
            case UNCHANGED -> "Your file matches what is published";
            case REPLACED -> "Saving updates your file";
            case NEW -> "Would be a new file";
            case RENAMED -> "That name is taken, so a free one is used";
        };
    }

    /** The class the row's status marker carries, so the template chooses no colours. */
    public String statusClass() {
        return switch (outcome) {
            case UNCHANGED -> "held";
            case REPLACED -> "stale";
            case NEW, RENAMED -> "unheld";
        };
    }

    public boolean qualified() {
        return !requirements.isEmpty();
    }

    /**
     * Whether this plan sells exposure to the wholesale market rather than a rate.
     *
     * <p>Its published unit price is illustrative, so its total is too. Everything about the
     * row that looks like a price has to say so.
     */
    public boolean marketLinked() {
        return extras.marketLinked();
    }

    /** What the retailer says about how the price moves, verbatim, for the row's tooltip. */
    public String priceVariation() {
        return extras.priceVariation();
    }

    /**
     * Whether the export credit depends on when the household exports.
     *
     * <p>The rate column shows the best band, so a plan paying eleven cents at six o'clock and
     * next to nothing at noon would otherwise read as a generous flat offer.
     */
    public boolean feedInVaries() {
        return plan.charges().stream()
                .filter(io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn.class::isInstance)
                .map(io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn.class::cast)
                .anyMatch(io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn::varies);
    }

    /** The worst rate a varying credit pays, for the marker's tooltip. */
    public java.math.BigDecimal feedInLowest() {
        return plan.charges().stream()
                .filter(io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn.class::isInstance)
                .map(io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn.class::cast)
                .map(io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn::lowestRate)
                .findFirst()
                .orElse(null);
    }

    public boolean hasFees() {
        return feeCount() > 0;
    }

    /**
     * How many published fees the total does not include.
     *
     * <p>A membership that has been costed is not among them. Counting it would tell a reader
     * that a charge already in their total was missing from it, which is the same kind of
     * wrongness the chip exists to prevent.
     */
    public int feeCount() {
        return (int) extras.fees().stream()
                .filter(fee -> !(costsAMembership() && "MEMBERSHIP".equalsIgnoreCase(fee.type())))
                .count();
    }

    /** Whether a mandatory recurring fee is priced into this plan's total. */
    public boolean costsAMembership() {
        return plan.charges().stream()
                .anyMatch(io.github.bovinemagnet.electrome.core.tariff.Membership.class::isInstance);
    }

    /** What that membership comes to over a year, for the row's marker. */
    public java.math.BigDecimal membershipPerYear() {
        return plan.charges().stream()
                .filter(io.github.bovinemagnet.electrome.core.tariff.Membership.class::isInstance)
                .map(io.github.bovinemagnet.electrome.core.tariff.Membership.class::cast)
                .map(io.github.bovinemagnet.electrome.core.tariff.Membership::dollarsPerYear)
                .findFirst()
                .orElse(null);
    }
}
