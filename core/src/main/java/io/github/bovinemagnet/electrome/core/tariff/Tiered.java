package io.github.bovinemagnet.electrome.core.tariff;

import java.util.List;
import java.util.Objects;

/**
 * Block usage rates that reset periodically.
 *
 * <p>Victorian block tariffs frequently price every block identically, and retailers encode
 * the same economics three different ways. This type expresses all of them.
 */
public record Tiered(ResetPeriod reset, List<Tier> tiers) implements Charge {

    public Tiered {
        Objects.requireNonNull(reset, "reset");
        Objects.requireNonNull(tiers, "tiers");
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("Tiered requires at least one tier");
        }
        tiers = List.copyOf(tiers);
        for (int i = 0; i < tiers.size() - 1; i++) {
            if (tiers.get(i).unbounded()) {
                throw new IllegalArgumentException(
                        "Only the last tier may be unbounded, found unbounded tier at index " + i);
            }
        }
        if (!tiers.get(tiers.size() - 1).unbounded()) {
            throw new IllegalArgumentException("The last tier must be unbounded");
        }
        for (int i = 1; i < tiers.size() - 1; i++) {
            if (tiers.get(i).thresholdKWh().compareTo(tiers.get(i - 1).thresholdKWh()) <= 0) {
                throw new IllegalArgumentException(
                        "Tier thresholds must be strictly ascending, breach at index " + i);
            }
        }
    }

    @Override
    public String label() {
        return "Block usage";
    }
}
