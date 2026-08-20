package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountScope;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Optional;

/**
 * At what level of consumption does the cheaper plan stop being the cheaper plan?
 *
 * <p>A ranking says which plan wins on the consumption you had. It does not say how fragile that
 * answer is, and a household is choosing a contract for the next year rather than pricing the
 * last one. Two plans $40 apart may be separated by the daily supply charge alone, in which case
 * the ranking flips the moment they use less.
 *
 * <p>The method is to scale the usage series by a factor <em>k</em> and cost both plans at each
 * factor. For a tariff whose bill is {@code constant + k x usage}, the difference between two
 * plans is linear in <em>k</em> and the crossing has a closed form. Where that linearity does not
 * hold, the crossing is found by bisection over full re-costings instead.
 *
 * <p>Both paths report an approximation, because scaling a usage series uniformly is itself a
 * simplification — see {@link Crossover#caveat()}.
 */
public final class CrossoverAnalysis {

    /** How the crossing was found, so the screen can say which. */
    public enum Method {
        LINEAR,
        BISECTION
    }

    /** Above this multiple of current consumption a crossing is of no practical interest. */
    private static final BigDecimal MAX_SCALE = new BigDecimal("20");

    private static final BigDecimal MIN_SCALE = new BigDecimal("0.02");

    /** Enough to place the crossing well inside the rounding of the reported kWh figure. */
    private static final int BISECTION_STEPS = 34;

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final int SCAN_STEPS = 12;

    private static final CostingEngine ENGINE = new CostingEngine();

    private CrossoverAnalysis() {}

    /**
     * The result of comparing two plans across levels of consumption.
     *
     * @param crossoverAnnualKWh the annual consumption at which the winner changes, or null when
     *     one plan wins at every level — which is the common case, and a stronger finding than
     *     any number
     * @param scaleFactor the crossing as a multiple of current consumption, or null when none
     */
    public record Crossover(
            String planA,
            String planB,
            Method method,
            BigDecimal scaleFactor,
            BigDecimal crossoverAnnualKWh,
            BigDecimal householdAnnualKWh,
            String winnerBelow,
            String winnerAbove) {

        public boolean exists() {
            return crossoverAnnualKWh != null;
        }

        /** The plan that wins at every level of use, or null when the ranking flips. */
        public String alwaysWins() {
            return exists() ? null : winnerBelow;
        }

        /** True when the household already sits above the crossing. */
        public boolean householdAboveCrossover() {
            return exists() && householdAnnualKWh.compareTo(crossoverAnnualKWh) > 0;
        }

        /** The plan that wins on the consumption the household actually had. */
        public String winnerNow() {
            return householdAboveCrossover() ? winnerAbove : winnerBelow;
        }

        /**
         * The approximation the figure rests on, stated rather than buried.
         *
         * <p>Scaling every half hour by the same factor assumes a household grows its
         * consumption evenly across the day. In practice an increase usually lands in
         * particular windows, which is exactly where a time-of-use tariff prices it
         * differently.
         */
        public String caveat() {
            return "Estimated by scaling your consumption evenly across every half hour. "
                    + "Real households add load in particular windows rather than evenly, so "
                    + "treat this as a guide to how close the two plans are, not a threshold.";
        }
    }

    /**
     * The crossing between two plans, by whichever method is exact for them.
     *
     * @param range the window both plans are costed over
     */
    public static Crossover between(UsageData usage, Plan a, Plan b, DateRange range) {
        if (linear(a) && linear(b)) {
            return byClosedForm(usage, a, b, range);
        }
        return byBisection(usage, a, b, range);
    }

    /**
     * Solves {@code k* = (constantB - constantA) / (usageA - usageB)}.
     *
     * <p>The two terms are measured rather than read off the tariff: costing each plan at two
     * scale factors gives the constant and the per-unit term exactly, without having to decide
     * which charge kinds happen to scale.
     */
    static Crossover byClosedForm(UsageData usage, Plan a, Plan b, DateRange range) {
        var termsA = terms(usage, a, range);
        var termsB = terms(usage, b, range);

        var k = linearCrossover(termsA[0], termsA[1], termsB[0], termsB[1]);
        return describe(usage, a, b, range, Method.LINEAR, k.orElse(null));
    }

    /**
     * The crossing of two straight lines, where one exists at a consumption a household could
     * actually have.
     *
     * <p>A negative solution means one plan is cheaper on both terms and therefore wins at every
     * positive consumption. A zero denominator means the lines are parallel — same usage rate,
     * different standing charge — and again one wins everywhere. Zero itself means they are
     * equal only when nothing is used, which is not a threshold anyone is on either side of.
     * All three are reported as "no crossover" rather than as a number.
     */
    static Optional<BigDecimal> linearCrossover(
            BigDecimal constantA, BigDecimal usageA, BigDecimal constantB, BigDecimal usageB) {
        var denominator = usageA.subtract(usageB);
        if (denominator.signum() == 0) {
            return Optional.empty();
        }
        var k = constantB.subtract(constantA).divide(denominator, MathContext.DECIMAL64);
        if (k.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(k);
    }

    /**
     * The crossing found by re-costing, for tariffs the closed form cannot describe.
     *
     * <p>Public because it is also the check on the closed form: run against a pair both methods
     * handle, the two must agree.
     */
    public static Crossover byBisection(UsageData usage, Plan a, Plan b, DateRange range) {
        var atMin = difference(usage, a, b, range, MIN_SCALE);

        // Walk outwards for a sign change. A tariff with blocks is piecewise-linear, so the
        // difference can turn more than once; the first crossing above current consumption is
        // the one a household would meet.
        BigDecimal lower = MIN_SCALE;
        BigDecimal lowerDiff = atMin;
        BigDecimal bracketLow = null;
        BigDecimal bracketHigh = null;

        for (int step = 1; step <= SCAN_STEPS; step++) {
            var upper = scanPoint(step);
            var upperDiff = difference(usage, a, b, range, upper);
            if (lowerDiff.signum() != 0 && upperDiff.signum() != 0
                    && lowerDiff.signum() != upperDiff.signum()) {
                bracketLow = lower;
                bracketHigh = upper;
                break;
            }
            lower = upper;
            lowerDiff = upperDiff;
        }

        if (bracketLow == null) {
            return describe(usage, a, b, range, Method.BISECTION, null);
        }

        for (int i = 0; i < BISECTION_STEPS; i++) {
            var mid = bracketLow.add(bracketHigh).divide(TWO, MathContext.DECIMAL64);
            var midDiff = difference(usage, a, b, range, mid);
            if (midDiff.signum() == 0) {
                bracketLow = mid;
                bracketHigh = mid;
                break;
            }
            if (midDiff.signum() == difference(usage, a, b, range, bracketLow).signum()) {
                bracketLow = mid;
            } else {
                bracketHigh = mid;
            }
        }

        var k = bracketLow.add(bracketHigh).divide(TWO, MathContext.DECIMAL64);
        return describe(usage, a, b, range, Method.BISECTION, k);
    }

    /** Which plan is cheaper when consumption is scaled by {@code k}. */
    public static String cheaperAt(
            UsageData usage, Plan a, Plan b, DateRange range, BigDecimal k) {
        return difference(usage, a, b, range, k).signum() < 0 ? a.id() : b.id();
    }

    // -----------------------------------------------------------------

    private static Crossover describe(UsageData usage, Plan a, Plan b, DateRange range,
            Method method, BigDecimal k) {

        var annual = annualKWh(usage, range);

        if (k == null) {
            // No crossing: whichever wins now wins at every level of use.
            var winner = cheaperAt(usage, a, b, range, BigDecimal.ONE);
            return new Crossover(a.id(), b.id(), method, null, null, annual, winner, winner);
        }

        var below = cheaperAt(usage, a, b, range, k.multiply(new BigDecimal("0.8")));
        var above = cheaperAt(usage, a, b, range, k.multiply(new BigDecimal("1.25")));

        if (below.equals(above)) {
            // The algebra produced a root the engine does not agree changes the winner. Trust
            // the engine: a crossing nobody can be on either side of is not one worth printing.
            return new Crossover(a.id(), b.id(), method, null, null, annual, below, above);
        }

        return new Crossover(a.id(), b.id(), method, k,
                annual.multiply(k).setScale(0, RoundingMode.HALF_UP), annual, below, above);
    }

    /**
     * The constant and per-unit terms of a plan's bill, measured at two scale factors.
     *
     * <p>{@code bill(k) = constant + k x usage}, so two costings determine both exactly.
     */
    private static BigDecimal[] terms(UsageData usage, Plan plan, DateRange range) {
        var atOne = cost(usage, plan, range, BigDecimal.ONE);
        var atTwo = cost(usage, plan, range, TWO);
        var perUnit = atTwo.subtract(atOne);
        return new BigDecimal[] {atOne.subtract(perUnit), perUnit};
    }

    private static BigDecimal difference(
            UsageData usage, Plan a, Plan b, DateRange range, BigDecimal k) {
        return cost(usage, a, range, k).subtract(cost(usage, b, range, k));
    }

    private static BigDecimal cost(UsageData usage, Plan plan, DateRange range, BigDecimal k) {
        return ENGINE.cost(scale(usage, k), plan, range).total();
    }

    /** A geometric scan from {@link #MIN_SCALE} to {@link #MAX_SCALE}. */
    private static BigDecimal scanPoint(int step) {
        double ratio = Math.pow(
                MAX_SCALE.doubleValue() / MIN_SCALE.doubleValue(), (double) step / SCAN_STEPS);
        return MIN_SCALE.multiply(BigDecimal.valueOf(ratio), MathContext.DECIMAL64);
    }

    /**
     * Consumption scaled by {@code k}.
     *
     * <p>Export is left alone. Scaling what a household draws from the grid is the question being
     * asked; scaling what its panels generate is a different one, and the what-if screen already
     * answers it.
     */
    private static UsageData scale(UsageData usage, BigDecimal k) {
        if (k.compareTo(BigDecimal.ONE) == 0) {
            return usage;
        }
        var scaled = new ArrayList<IntervalReading>(usage.consumption().readings().size());
        for (var reading : usage.consumption().readings()) {
            scaled.add(new IntervalReading(reading.start(), reading.length(),
                    reading.kWh().multiply(k, MathContext.DECIMAL64), reading.quality()));
        }
        return new UsageData(UsageSeries.of(scaled), usage.export());
    }

    /** Consumption over the window, projected to a year so the figure is comparable. */
    private static BigDecimal annualKWh(UsageData usage, DateRange range) {
        var sliced = usage.consumption().slice(range);
        var days = sliced.billingDays().size();
        if (days == 0) {
            return BigDecimal.ZERO;
        }
        return sliced.totalKWh()
                .multiply(new BigDecimal("365"))
                .divide(BigDecimal.valueOf(days), MathContext.DECIMAL64);
    }

    /**
     * Whether a plan's bill is a straight line in the scale factor.
     *
     * <p>Block thresholds reset per period and do not scale, so a block tariff is genuinely
     * piecewise-linear. Demand charges and usage-scoped discounts happen to stay linear under
     * <em>uniform</em> scaling, but only as a coincidence of that method: a shape-preserving
     * scaling would break the demand term immediately. The closed form is therefore reserved
     * for tariffs whose linearity is a property of the charge rather than of the approximation.
     */
    private static boolean linear(Plan plan) {
        for (var charge : plan.charges()) {
            boolean breaksLinearity = switch (charge) {
                case Tiered unused -> true;
                case Demand unused -> true;
                case Discount discount -> discount.scope() != DiscountScope.TOTAL;
                default -> false;
            };
            if (breaksLinearity) {
                return false;
            }
        }
        return true;
    }
}
