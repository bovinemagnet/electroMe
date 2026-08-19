package io.github.bovinemagnet.electrome.core.cost;

/** Category of a bill line, used to scope discounts and to stack a comparison chart. */
public enum ChargeKind {
    SUPPLY,
    USAGE,
    DEMAND,
    FEED_IN,
    DISCOUNT
}
