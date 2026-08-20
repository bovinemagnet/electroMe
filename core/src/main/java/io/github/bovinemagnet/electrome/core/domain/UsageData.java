package io.github.bovinemagnet.electrome.core.domain;

import java.util.Objects;

/**
 * Consumption and export series for one premises.
 *
 * <p>Export is empty for a site without solar. Carrying it from the outset keeps the costing
 * engine signature stable when feed-in modelling arrives.
 */
public record UsageData(
        UsageSeries consumption, UsageSeries export, UsageSeries controlled) {

    public UsageData {
        Objects.requireNonNull(consumption, "consumption");
        Objects.requireNonNull(export, "export");
        Objects.requireNonNull(controlled, "controlled");
    }

    /**
     * A premises with no controlled circuit, which is the common case.
     *
     * <p>Kept so that adding the third series left every existing call site unchanged: a
     * household without a separate hot water tariff should not have to say so.
     */
    public UsageData(UsageSeries consumption, UsageSeries export) {
        this(consumption, export, UsageSeries.empty());
    }

    public static UsageData consumptionOnly(UsageSeries consumption) {
        return new UsageData(consumption, UsageSeries.empty(), UsageSeries.empty());
    }

    public boolean hasControlledLoad() {
        return !controlled.isEmpty();
    }
}
