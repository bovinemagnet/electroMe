package io.github.bovinemagnet.electrome.core.cost;

/** Category of a bill line, used to scope discounts and to stack a comparison chart. */
public enum ChargeKind {
    SUPPLY,
    USAGE,
    /** Energy on a separate controlled circuit, priced at its own rate. */
    CONTROLLED,
    DEMAND,
    FEED_IN,
    /** A recurring fee that must be paid to be on the plan, irrespective of consumption. */
    MEMBERSHIP,
    DISCOUNT
}
