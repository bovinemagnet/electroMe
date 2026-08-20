package io.github.bovinemagnet.electrome.market.cdr;

/**
 * A credit, gift or benefit a plan advertises.
 *
 * <p>Display only, and frequently the place a retailer describes something the tariff itself
 * does not express — a separate feed-in rate, a one-off account credit, a bundled subscription.
 *
 * @param eligibility what a household must do to receive it; often the substance of the offer
 */
public record PlanIncentive(
        String displayName, String category, String description, String eligibility) {}
