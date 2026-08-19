package io.github.bovinemagnet.electrome.core.domain;

import java.util.Objects;

/**
 * Consumption and export series for one premises.
 *
 * <p>Export is empty for a site without solar. Carrying it from the outset keeps the costing
 * engine signature stable when feed-in modelling arrives.
 */
public record UsageData(UsageSeries consumption, UsageSeries export) {

    public UsageData {
        Objects.requireNonNull(consumption, "consumption");
        Objects.requireNonNull(export, "export");
    }

    public static UsageData consumptionOnly(UsageSeries consumption) {
        return new UsageData(consumption, UsageSeries.empty());
    }
}
