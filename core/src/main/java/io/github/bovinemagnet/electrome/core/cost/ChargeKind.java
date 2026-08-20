package io.github.bovinemagnet.electrome.core.cost;

/** Category of a bill line, used to scope discounts and to stack a comparison chart. */
public enum ChargeKind {
    SUPPLY,
    USAGE,
    /** Energy on a separate controlled circuit, priced at its own rate. */
    CONTROLLED,
    DEMAND,
    FEED_IN,
    DISCOUNT
}
