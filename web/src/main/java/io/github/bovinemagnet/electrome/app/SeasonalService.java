package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Costs every plan over each half of the year, to answer whether switching twice is worth it.
 *
 * <p>Each season is costed by slicing the usage series down to that season's months and handing
 * it to the unchanged costing engine. The engine counts billing days from the readings it is
 * given, so a season is charged supply for its own days and no others, and a season and the year
 * it came from can never disagree about arithmetic — only, sometimes, about accumulation.
 */
@ApplicationScoped
public class SeasonalService {

    /**
     * Below this the two seasons and the whole year are the same number.
     *
     * <p>Half a cent, measured on unrounded totals. Rounding each of the three figures to cents
     * independently can leave the two seasons a cent away from the year with nothing wrong at
     * all, and reporting that as a tariff that does not decompose would cry wolf on almost
     * every plan. Real accumulation effects are pounds, not cents.
     */
    private static final BigDecimal MATERIAL = new BigDecimal("0.005");

    private final CostingEngine engine = new CostingEngine();

    @Inject UsageStore usageStore;
    @Inject PlanStore planStore;

    public SeasonalReport report(DateRange range, SeasonSplit split) {
        return report(range, split, usageStore.usage(), planStore.plans());
    }

    /**
     * The report over a supplied usage series and plan list.
     *
     * <p>Separated from the injected stores so the arithmetic can be tested without a container
     * or a fixture directory.
     */
    public SeasonalReport report(
            DateRange range, SeasonSplit split, UsageData usage, List<Plan> plans) {

        var inFirst = only(usage, split::contains);
        var inSecond = only(usage, month -> !split.contains(month));

        var costs = new ArrayList<SeasonalReport.SeasonalPlanCost>();
        for (var plan : plans) {
            var first = engine.cost(inFirst, plan, range);
            var second = engine.cost(inSecond, plan, range);
            var wholeYear = engine.cost(usage, plan, range);
            costs.add(new SeasonalReport.SeasonalPlanCost(
                    plan,
                    first.totalRounded(),
                    second.totalRounded(),
                    wholeYear.totalRounded(),
                    // Compared before rounding, so the check sees the tariff rather than the
                    // display.
                    caveat(first.total(), second.total(), wholeYear.total())));
        }

        if (costs.isEmpty()) {
            return new SeasonalReport(range, split, List.of(), null, null, null);
        }

        costs.sort(Comparator.comparing(SeasonalReport.SeasonalPlanCost::wholeYear));
        return new SeasonalReport(
                range,
                split,
                costs,
                cheapestBy(costs, SeasonalReport.SeasonalPlanCost::first),
                cheapestBy(costs, SeasonalReport.SeasonalPlanCost::second),
                costs.get(0));
    }

    // -----------------------------------------------------------------

    /**
     * Whether this plan's two seasons add up to its year, and why not when they do not.
     *
     * <p>Measured rather than reasoned about. Most tariffs decompose cleanly, but a block rate
     * that resets quarterly gets a fresh allowance in each season, and a fixed-amount discount
     * is applied once per costing and so lands twice. Rather than enumerate the causes and hope
     * the list is complete, both figures are computed and compared: any tariff that does not
     * decompose says so, including one nobody thought of.
     */
    private static String caveat(BigDecimal first, BigDecimal second, BigDecimal wholeYear) {
        var gap = first.add(second).subtract(wholeYear);
        if (gap.abs().compareTo(MATERIAL) < 0) {
            return null;
        }
        var size = gap.abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        return gap.signum() > 0
                ? "Its two seasons cost " + size + " more than the same "
                        + "year costed in one go, because something in this tariff — a block "
                        + "that resets, or a fixed amount applied once a bill — is counted in "
                        + "both halves. Treat the seasonal figures as the upper bound."
                : "Its two seasons cost " + size + " less than the same "
                        + "year costed in one go, because splitting the year restarts an "
                        + "accumulation this tariff carries across it. Treat the seasonal "
                        + "figures as the lower bound.";
    }

    private static SeasonalReport.SeasonalPlanCost cheapestBy(
            List<SeasonalReport.SeasonalPlanCost> costs,
            java.util.function.Function<SeasonalReport.SeasonalPlanCost, BigDecimal> figure) {
        return costs.stream().min(Comparator.comparing(figure)).orElseThrow();
    }

    /** The same premises, with every reading outside the chosen months removed. */
    private static UsageData only(UsageData usage, Predicate<Month> months) {
        return new UsageData(
                filter(usage.consumption(), months),
                filter(usage.export(), months),
                filter(usage.controlled(), months));
    }

    private static UsageSeries filter(UsageSeries series, Predicate<Month> months) {
        var kept = new ArrayList<IntervalReading>();
        for (var reading : series.readings()) {
            if (months.test(reading.date().getMonth())) {
                kept.add(reading);
            }
        }
        return UsageSeries.of(kept);
    }
}
