package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One block of a tiered usage charge.
 *
 * @param thresholdKWh the upper bound of this block within the reset period, or null for the
 *     final unbounded block
 */
public record Tier(BigDecimal thresholdKWh, BigDecimal centsPerKWh) {

    public Tier {
        Objects.requireNonNull(centsPerKWh, "centsPerKWh");
        if (centsPerKWh.signum() < 0) {
            throw new IllegalArgumentException("Tier rate must not be negative: " + centsPerKWh);
        }
        if (thresholdKWh != null && thresholdKWh.signum() <= 0) {
            throw new IllegalArgumentException("Tier threshold must be positive: " + thresholdKWh);
        }
    }

    public boolean unbounded() {
        return thresholdKWh == null;
    }

    /** A single unbounded block: the degenerate form of a tier list priced at one rate. */
    public static List<Tier> single(BigDecimal centsPerKWh) {
        return List.of(new Tier(null, centsPerKWh));
    }

    /**
     * Checks a block list is usable and returns it defensively copied.
     *
     * <p>Shared by {@link Tiered} and by {@link Band}, which carries the same block structure
     * scoped to a time window. Duplicating the rules would let the two drift apart, and a
     * tariff that validates one way in one place and another way in another is worse than one
     * that is simply rejected.
     */
    public static List<Tier> validatedBlocks(List<Tier> tiers) {
        Objects.requireNonNull(tiers, "tiers");
        var copy = List.copyOf(tiers);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("At least one tier is required");
        }
        for (int i = 0; i < copy.size() - 1; i++) {
            if (copy.get(i).unbounded()) {
                throw new IllegalArgumentException(
                        "Only the last tier may be unbounded, found unbounded tier at index " + i);
            }
        }
        if (!copy.get(copy.size() - 1).unbounded()) {
            throw new IllegalArgumentException("The last tier must be unbounded");
        }
        for (int i = 1; i < copy.size() - 1; i++) {
            if (copy.get(i).thresholdKWh().compareTo(copy.get(i - 1).thresholdKWh()) <= 0) {
                throw new IllegalArgumentException(
                        "Tier thresholds must be strictly ascending, breach at index " + i);
            }
        }
        return copy;
    }
}
