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
        tiers = Tier.validatedBlocks(tiers);
    }

    @Override
    public String label() {
        return "Block usage";
    }
}
