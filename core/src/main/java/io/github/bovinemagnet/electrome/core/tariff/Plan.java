package io.github.bovinemagnet.electrome.core.tariff;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A named retail tariff.
 *
 * @param gstInclusive whether the supplied rates already include GST; rates are normalised to
 *     GST inclusive at load time, and this records what the source provided
 * @param validFrom null means unbounded in the past
 * @param validTo null means unbounded in the future
 */
public record Plan(
        String id,
        String name,
        String retailer,
        DistributionZone zone,
        List<Charge> charges,
        boolean gstInclusive,
        LocalDate validFrom,
        LocalDate validTo) {

    public Plan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(retailer, "retailer");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(charges, "charges");
        if (charges.isEmpty()) {
            throw new IllegalArgumentException("Plan " + id + " has no charges");
        }
        charges = List.copyOf(charges);
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException(
                    "Plan " + id + " validity ends " + validTo + " before it starts " + validFrom);
        }
    }

    public boolean appliesOn(LocalDate date) {
        if (validFrom != null && date.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || !date.isAfter(validTo);
    }
}
