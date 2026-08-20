package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.app.CrossoverAnalysis;
import io.github.bovinemagnet.electrome.app.PlanMatrix;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one render of the comparison screen needs, assembled once.
 *
 * <p>The matrix is evidence; the {@link Verdict#sentence()} is the answer. A reader should be
 * able to stop after the sentence, which is why it is built here where it can be tested rather
 * than assembled out of fragments in a template.
 *
 * @param unknown identifiers that resolved to no plan, named rather than dropped
 * @param surplus identifiers beyond the column limit, named for the same reason
 */
public record MatrixView(
        PlanMatrix matrix,
        int winnerColumn,
        List<Verdict> verdicts,
        String rateChart,
        DateRange range,
        List<String> unknown,
        List<String> surplus) {

    /** How many named reasons a sentence can carry and stay readable. */
    private static final int NAMED_REASONS = 3;

    /** Below a dollar, a remainder is rounding rather than a reason. */
    private static final BigDecimal REMAINDER_FLOOR = BigDecimal.ONE;

    /**
     * One losing plan measured against the winner.
     *
     * @param gap what this plan costs above the winner
     * @param contributions every component of the gap, largest first, so the figures add up
     * @param named the few that make it into the sentence
     */
    public record Verdict(
            Plan against,
            Plan winner,
            BigDecimal gap,
            List<PlanMatrix.Difference> contributions,
            List<PlanMatrix.Difference> named,
            CrossoverAnalysis.Crossover crossover) {

        public Verdict {
            contributions = List.copyOf(contributions);
            named = List.copyOf(named);
        }

        /**
         * The whole comparison in one sentence.
         *
         * <p>"Kind Evenings is $571 cheaper: $410 of that is its lower evening peak, $180 its
         * lower daily supply, offset by $19 more overnight."
         */
        public String sentence() {
            var text = new StringBuilder();
            text.append(winner.name())
                    .append(" is ")
                    .append(Money.dollars(gap.abs()))
                    .append(" cheaper than ")
                    .append(against.name())
                    .append(" over this window");

            if (named.isEmpty()) {
                return text.append('.').toString();
            }

            text.append(": ");
            boolean first = true;
            boolean offsetOpened = false;
            for (var contribution : named) {
                // Negative favours the winner; positive is ground the winner loses.
                if (contribution.amount().signum() < 0) {
                    text.append(first ? "" : ", ")
                            .append(Money.dollars(contribution.size()))
                            .append(first ? " of that is its lower " : " its lower ")
                            .append(lower(contribution));
                } else {
                    text.append(offsetOpened ? ", and " : ", offset by ")
                            .append(Money.dollars(contribution.size()))
                            .append(" more on ")
                            .append(lower(contribution));
                    offsetOpened = true;
                }
                first = false;
            }

            // Without this, a reader adding up the three named reasons gets a different number
            // from the gap in the sentence's own opening clause.
            var left = remainder();
            if (left.abs().compareTo(REMAINDER_FLOOR) > 0) {
                text.append(left.signum() < 0 ? ", and " : ", less ")
                        .append(Money.dollars(left.abs()))
                        .append(" across smaller differences");
            }
            return text.append('.').toString();
        }

        /**
         * The part of the gap the named reasons do not cover.
         *
         * <p>Signed the same way as a contribution: negative where the unnamed remainder still
         * favours the winner.
         */
        public BigDecimal remainder() {
            var namedSum = named.stream()
                    .map(PlanMatrix.Difference::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var whole = contributions.stream()
                    .map(PlanMatrix.Difference::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return whole.subtract(namedSum);
        }

        private static String lower(PlanMatrix.Difference contribution) {
            return contribution.component().label().toLowerCase(java.util.Locale.ROOT);
        }

        /**
         * Whether the answer would survive a change in consumption.
         *
         * <p>This is the part a ranking cannot give. Two plans separated by the supply charge
         * alone swap places the moment a household uses less, and it is choosing a contract for
         * next year rather than pricing last year.
         */
        public String robustness() {
            if (!crossover.exists()) {
                return winner.name() + " wins at every level of use, so the answer does not "
                        + "depend on how much you consume.";
            }
            var atCrossover = Money.count(crossover.crossoverAnnualKWh()) + " kWh a year";
            var yours = Money.count(crossover.householdAnnualKWh()) + " kWh";
            return "These two swap places at about " + atCrossover + ", and you used " + yours
                    + " over a comparable period. "
                    + (crossover.householdAboveCrossover()
                            ? "Consume much less and the other plan wins."
                            : "Consume much more and the other plan wins.");
        }

        /** True when the household sits close enough to the crossing for it to matter. */
        public boolean marginal() {
            if (!crossover.exists()) {
                return false;
            }
            var ratio = crossover.crossoverAnnualKWh().divide(
                    crossover.householdAnnualKWh().max(BigDecimal.ONE),
                    java.math.MathContext.DECIMAL64);
            return ratio.compareTo(new BigDecimal("0.6")) > 0
                    && ratio.compareTo(new BigDecimal("1.6")) < 0;
        }

        public String caveat() {
            return crossover.caveat();
        }
    }

    public static MatrixView of(PlanMatrix matrix, UsageData usage, DateRange range,
            List<String> unknown, List<String> surplus) {

        int winner = cheapestColumn(matrix);
        var verdicts = new ArrayList<Verdict>();
        for (int column = 0; column < matrix.plans().size(); column++) {
            if (column == winner) {
                continue;
            }
            var contributions = matrix.differences(winner, column);
            verdicts.add(new Verdict(
                    matrix.plan(column),
                    matrix.plan(winner),
                    matrix.total(column).subtract(matrix.total(winner)),
                    contributions,
                    contributions.stream().limit(NAMED_REASONS).toList(),
                    CrossoverAnalysis.between(
                            usage, matrix.plan(winner), matrix.plan(column), range)));
        }

        return new MatrixView(
                matrix,
                winner,
                List.copyOf(verdicts),
                Charts.payload(ChartOptions.rateShapes(matrix.plans(), usage, range)),
                range,
                unknown == null ? List.of() : List.copyOf(unknown),
                surplus == null ? List.of() : List.copyOf(surplus));
    }

    public Plan winner() {
        return matrix.plan(winnerColumn);
    }

    public BigDecimal winnerTotal() {
        return matrix.total(winnerColumn);
    }

    /** True when the selection was not exactly what was asked for, and must be explained. */
    public boolean hasProblems() {
        return !unknown.isEmpty() || !surplus.isEmpty();
    }

    private static int cheapestColumn(PlanMatrix matrix) {
        int cheapest = 0;
        for (int column = 1; column < matrix.plans().size(); column++) {
            if (matrix.total(column).compareTo(matrix.total(cheapest)) < 0) {
                cheapest = column;
            }
        }
        return cheapest;
    }
}
