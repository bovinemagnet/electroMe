package io.github.bovinemagnet.electrome.core.tariff;

/**
 * Victorian electricity distribution zones.
 *
 * <p>The strings returned by {@link #cdrDistributorName()} are the exact values published in
 * the CDR product reference data {@code geography.distributors} array, verified against live
 * data. AusNet in particular is published as "AusNet Services (electricity)".
 */
public enum DistributionZone {
    AUSNET("AusNet Services (electricity)"),
    CITIPOWER("Citipower"),
    POWERCOR("Powercor"),
    UNITED_ENERGY("United Energy"),
    JEMENA("Jemena"),
    OTHER("");

    private final String cdrDistributorName;

    DistributionZone(String cdrDistributorName) {
        this.cdrDistributorName = cdrDistributorName;
    }

    public String cdrDistributorName() {
        return cdrDistributorName;
    }
}
