package io.github.bovinemagnet.electrome.market.cdr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * One fee a plan publishes.
 *
 * <p>Display only. Published fee amounts are GST inclusive while unit prices are exclusive, so
 * folding these into the costing engine mixes two tax bases and is deliberately deferred.
 *
 * @param amount a fixed dollar amount, GST inclusive; null when the fee is a percentage
 * @param rate a fraction of the bill, so 0.0027 means 0.27%; null when the fee is fixed
 */
public record PlanFee(
        String type, String term, BigDecimal amount, BigDecimal rate, String description) {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public PlanFee {
        Objects.requireNonNull(type, "type");
    }

    public boolean percentOfBill() {
        return rate != null;
    }

    /** The charge itself, without its explanatory text. */
    public String describe() {
        if (percentOfBill()) {
            return rate.multiply(ONE_HUNDRED).stripTrailingZeros().toPlainString()
                    + "% of the bill";
        }
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
