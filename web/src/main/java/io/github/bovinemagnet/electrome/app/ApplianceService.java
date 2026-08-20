package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.appliance.ApplianceLoad;
import io.github.bovinemagnet.electrome.core.appliance.LoadSchedule;
import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.scenario.AddAppliance;
import io.github.bovinemagnet.electrome.core.schedule.LoadScheduler;
import io.github.bovinemagnet.electrome.core.schedule.MarginalRateProfile;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Schedules an appliance against every plan, costs it, and re-ranks under the new load.
 *
 * <p>Each plan gets its own schedule. Sharing one would understate every plan whose cheap window
 * sits somewhere else, which is precisely the effect being measured: a plan that loses on
 * current consumption may win comfortably on consumption with an electric vehicle in it.
 *
 * <p>Affordable because scheduling is a scan over 48 rates rather than a re-costing, so the only
 * full costings are the two per plan the answer actually reports.
 */
@ApplicationScoped
public class ApplianceService {

    private final CostingEngine engine = new CostingEngine();

    /** One plan's answer: when to run, what it costs, and what not bothering would cost. */
    public record PlanOutcome(
            Plan plan,
            LoadSchedule schedule,
            BigDecimal totalWithout,
            BigDecimal totalWith,
            BigDecimal naiveCost,
            String naiveDescription,
            boolean timingMatters,
            String inexactBecause) {

        /** What the appliance adds to the bill, by full costing rather than by estimate. */
        public BigDecimal annualApplianceCost() {
            return totalWith.subtract(totalWithout);
        }

        /** What setting a timer is worth on this plan. */
        public BigDecimal savingFromTiming() {
            var saving = naiveCost.subtract(annualApplianceCost());
            return saving.signum() < 0 ? BigDecimal.ZERO : saving;
        }

        public boolean exact() {
            return inexactBecause == null;
        }
    }

    /**
     * The whole answer.
     *
     * @param before every plan ranked on current consumption
     * @param after every plan ranked with the appliance added and optimally scheduled
     */
    public record Outcome(
            ApplianceLoad load,
            List<PlanOutcome> perPlan,
            Comparison before,
            Comparison after,
            boolean fits,
            BigDecimal shortfallKWh,
            String workableDeadline) {

        public PlanOutcome forPlan(String planId) {
            return perPlan.stream()
                    .filter(outcome -> outcome.plan().id().equals(planId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No outcome for " + planId));
        }

        public Plan bestPlanBefore() {
            return before.best().map(result -> result.bill().plan()).orElse(null);
        }

        public Plan bestPlanAfter() {
            return after.best().map(result -> result.bill().plan()).orElse(null);
        }

        /**
         * Whether owning the appliance changes which plan the household should be on.
         *
         * <p>The interesting question, and the one a ranking of current consumption cannot
         * answer.
         */
        public boolean bestPlanChanged() {
            var was = bestPlanBefore();
            var now = bestPlanAfter();
            return was != null && now != null && !was.id().equals(now.id());
        }

        public PlanOutcome recommended() {
            var best = bestPlanAfter();
            return best == null ? null : forPlan(best.id());
        }
    }

    public Outcome evaluate(
            UsageData usage, List<Plan> plans, ApplianceLoad load, DateRange range) {

        var outcomes = new ArrayList<PlanOutcome>();
        var billsBefore = new ArrayList<BillBreakdown>();
        var billsAfter = new ArrayList<BillBreakdown>();

        boolean fits = true;
        BigDecimal shortfall = BigDecimal.ZERO;
        String workableDeadline = null;

        for (var plan : plans) {
            var profile = profileFor(plan, usage, load);
            var schedule = schedule(load, profile);

            if (schedule != null && !schedule.fits()) {
                fits = false;
                shortfall = schedule.shortfallKWh();
                workableDeadline = clock(schedule.workableDeadlineMinute());
            }

            var without = engine.cost(usage, plan, range);
            var with = engine.cost(new AddAppliance(load, schedule).applyTo(usage), plan, range);

            billsBefore.add(without);
            billsAfter.add(with);

            outcomes.add(new PlanOutcome(
                    plan,
                    schedule,
                    without.totalRounded(),
                    with.totalRounded(),
                    naiveCost(usage, plan, load, range, without.totalRounded()),
                    naiveDescription(load),
                    schedule != null && schedule.timingMatters(),
                    profile == null ? null : profile.inexactBecause()));
        }

        return new Outcome(load, List.copyOf(outcomes),
                rank(billsBefore, range), rank(billsAfter, range),
                fits, shortfall, workableDeadline);
    }

    // -----------------------------------------------------------------

    private static MarginalRateProfile profileFor(
            Plan plan, UsageData usage, ApplianceLoad load) {
        if (load instanceof SchedulableLoad schedulable && schedulable.controlledCircuit()) {
            return MarginalRateProfile.controlled(plan, DaySelector.ALL);
        }
        return MarginalRateProfile.of(plan, usage, DaySelector.ALL);
    }

    /** A load whose timing is not a choice has no schedule to make. */
    private static LoadSchedule schedule(ApplianceLoad load, MarginalRateProfile profile) {
        if (load instanceof SchedulableLoad schedulable) {
            return LoadScheduler.schedule(schedulable, profile);
        }
        return null;
    }

    /**
     * What the appliance costs run as soon as it is available.
     *
     * <p>Plugging in and charging straight away is what happens without a timer, so the gap
     * between this and the recommendation is the value of setting one. Anything else would be
     * comparing the recommendation against a strawman.
     */
    private BigDecimal naiveCost(UsageData usage, Plan plan, ApplianceLoad load,
            DateRange range, BigDecimal baseline) {
        if (!(load instanceof SchedulableLoad schedulable)) {
            return BigDecimal.ZERO;
        }
        var immediately = new SchedulableLoad(
                schedulable.label(), schedulable.energyPerRunKWh(), schedulable.powerKW(),
                schedulable.availableFromMinute(),
                // A window exactly long enough to start straight away and run to completion.
                Math.floorMod(schedulable.availableFromMinute()
                        + schedulable.slotsNeeded() * 30, 24 * 60),
                schedulable.runsPerWeek(), schedulable.months(),
                false, schedulable.controlledCircuit());

        var profile = profileFor(plan, usage, immediately);
        var schedule = LoadScheduler.schedule(immediately, profile);
        if (!schedule.fits()) {
            return baseline;
        }
        var with = engine.cost(
                new AddAppliance(immediately, schedule).applyTo(usage), plan, range);
        return with.totalRounded().subtract(baseline);
    }

    private static String naiveDescription(ApplianceLoad load) {
        if (load instanceof SchedulableLoad schedulable) {
            return "starting at " + clock(schedulable.availableFromMinute())
                    + ", as it would without a timer";
        }
        return "";
    }

    private static Comparison rank(List<BillBreakdown> bills, DateRange range) {
        var sorted = new ArrayList<>(bills);
        sorted.sort(Comparator.comparing(BillBreakdown::totalRounded));

        var results = new ArrayList<PlanResult>();
        for (int i = 0; i < sorted.size(); i++) {
            results.add(new PlanResult(
                    sorted.get(i),
                    sorted.get(i).totalRounded().subtract(sorted.get(0).totalRounded()),
                    i == 0));
        }
        return new Comparison(range, results);
    }

    private static String clock(Integer minuteOfDay) {
        if (minuteOfDay == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
