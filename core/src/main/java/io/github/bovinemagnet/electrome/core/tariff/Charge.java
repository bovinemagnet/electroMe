package io.github.bovinemagnet.electrome.core.tariff;

/**
 * One component of a retail electricity tariff.
 *
 * <p>Sealed deliberately. The costing engine switches over this hierarchy without a default
 * branch, so adding a charge kind without teaching the engine to cost it is a compile error
 * rather than a silently wrong bill.
 */
public sealed interface Charge
        permits DailySupply, FlatRate, TimeOfUse, Tiered, Demand, SolarFeedIn, Discount,
                ControlledLoad, Membership {

    /** Human-readable name for the resulting bill line. */
    String label();
}
