package io.github.bovinemagnet.electrome.core.tariff;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A recurring fee a household must pay to be on the plan at all.
 *
 * <p>Distinct from every other fee a retailer publishes, and the distinction is the whole point.
 * A late payment fee, a paper bill fee, a card processing fee, a disconnection fee: every one of
 * those is a fact about what the household does, not about the tariff, and costing them would
 * charge a reader for behaviour they have not got. This one is unavoidable — it is the price of
 * access — so leaving it out understates the plan by exactly its amount, every year, for
 * everybody.
 *
 * <p>Held as cents per day rather than as the published annual or monthly figure, for the same
 * reason {@link DailySupply} is: a window of any length then costs correctly without the engine
 * needing to know when the billing period starts.
 *
 * <p>Not grossed up for GST anywhere. Published fee amounts already include it — the retailers
 * that mention tax at all say so in the fee's own description, "incl GST", across several
 * independent brands — which is the opposite of {@code unitPrice}, and is why this could not
 * simply be folded in with the rates.
 *
 * @param name what the retailer calls it, so a bill line can say "Membership" rather than "Fee"
 */
public record Membership(String name, BigDecimal centsPerDay) implements Charge {

    /** Kept for reference: what a yearly figure divides by to reach a daily one. */
    public static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");
    private static final BigDecimal CENTS_PER_DOLLAR = new BigDecimal("100");

    public Membership {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(centsPerDay, "centsPerDay");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A membership fee needs a name");
        }
        if (centsPerDay.signum() < 0) {
            throw new IllegalArgumentException(
                    "Membership fee must not be negative: " + centsPerDay);
        }
    }

    /** From a published yearly amount in dollars, GST already included. */
    public static Membership perYear(String name, BigDecimal dollarsPerYear) {
        return new Membership(name, dollarsPerYear
                .multiply(CENTS_PER_DOLLAR)
                .divide(DAYS_PER_YEAR, MathContext.DECIMAL64));
    }

    /** From a published monthly amount in dollars, GST already included. */
    public static Membership perMonth(String name, BigDecimal dollarsPerMonth) {
        return perYear(name, dollarsPerMonth.multiply(MONTHS_PER_YEAR));
    }

    /** What it comes to over a year, which is the figure the retailer advertises. */
    public BigDecimal dollarsPerYear() {
        return centsPerDay
                .multiply(DAYS_PER_YEAR)
                .divide(CENTS_PER_DOLLAR, 2, RoundingMode.HALF_UP);
    }

    @Override
    public String label() {
        return name;
    }
}
