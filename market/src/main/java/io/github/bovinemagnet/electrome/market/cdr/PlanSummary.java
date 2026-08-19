package io.github.bovinemagnet.electrome.market.cdr;

import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import java.util.List;

/** One plan as it appears in the cheap list response. */
public record PlanSummary(
        String planId,
        String displayName,
        String brand,
        String type,
        String fuelType,
        String customerType,
        List<String> distributors,
        String lastUpdated) {

    public PlanSummary {
        distributors = distributors == null ? List.of() : List.copyOf(distributors);
    }

    /**
     * Exact match on the published distributor string, never a substring.
     *
     * <p>"AusNet Services (electricity)" and "AusNet Services (gas)" are different networks, and
     * "Jemena" names both a Victorian electricity network and a New South Wales gas one.
     */
    public boolean servesZone(DistributionZone zone) {
        return distributors.contains(zone.cdrDistributorName());
    }

    public boolean isResidentialElectricity() {
        return "ELECTRICITY".equalsIgnoreCase(fuelType)
                && "RESIDENTIAL".equalsIgnoreCase(customerType);
    }

    /** Victorian plans carry a @VEC suffix on the plan identifier. */
    public boolean isVictorian() {
        return planId != null && planId.endsWith("@VEC");
    }
}
