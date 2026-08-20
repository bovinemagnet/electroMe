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

    /** One appliance and the half hours this plan wants it to run in. */
    public record ScheduledLoad(ApplianceLoad load, LoadSchedule schedule) {}

    /** One plan's answer: when to run, what it costs, and what not bothering would cost. */
    public record PlanOutcome(
            Plan plan,
            List<ScheduledLoad> scheduled,
            BigDecimal totalWithout,
            BigDecimal totalWith,
            BigDecimal naiveCost,
            String naiveDescription,
            boolean timingMatters,
            String inexactBecause) {

        public PlanOutcome {
            scheduled = List.copyOf(scheduled);
        }

        /**
         * The only schedule, for the single-appliance question the screen asks.
         *
         * <p>Null where the appliance has no schedule to make, as air conditioning does not.
         */
        public LoadSchedule schedule() {
            return scheduled.isEmpty() ? null : scheduled.get(0).schedule();
        }

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
            List<ApplianceLoad> loads,
            List<PlanOutcome> perPlan,
            Comparison before,
            Comparison after,
            boolean fits,
            BigDecimal shortfallKWh,
            String workableDeadline) {

        public Outcome {
            loads = List.copyOf(loads);
        }

        /** The only appliance, for the single-appliance question the screen asks. */
        public ApplianceLoad load() {
            return loads.isEmpty() ? null : loads.get(0);
        }

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
        return evaluate(usage, plans, List.of(load), range);
    }

    /**
     * Several appliances at once, each scheduled on its own terms.
     *
     * <p>A household with two cars does not have one car twice. They want different amounts of
     * energy, are plugged in at different times, and are charged on different nights, so each
     * gets its own schedule against each plan's own rates. What they cost together is then a
     * single full costing of the series with all of them in it, which is why the figure is
     * exact even though the schedules were chosen independently.
     *
     * <p>Independent scheduling is the approximation that remains, and it only bites where one
     * appliance can change what the next one pays — a capped window, a block rate, a demand
     * charge. Those plans already say so through {@link PlanOutcome#inexactBecause()}.
     */
    public Outcome evaluate(
            UsageData usage, List<Plan> plans, List<ApplianceLoad> loads, DateRange range) {

        var outcomes = new ArrayList<PlanOutcome>();
        var billsBefore = new ArrayList<BillBreakdown>();
        var billsAfter = new ArrayList<BillBreakdown>();

        boolean fits = true;
        BigDecimal shortfall = BigDecimal.ZERO;
        String workableDeadline = null;

        for (var plan : plans) {
            var scheduled = new ArrayList<ScheduledLoad>();
            String inexactBecause = null;
            boolean timingMatters = false;
            var with = usage;

            for (var load : loads) {
                var profile = profileFor(plan, usage, load);
                var schedule = schedule(load, profile);

                if (schedule != null && !schedule.fits()) {
                    fits = false;
                    shortfall = schedule.shortfallKWh();
                    workableDeadline = clock(schedule.workableDeadlineMinute());
                }
                if (schedule != null && schedule.timingMatters()) {
                    timingMatters = true;
                }
                if (inexactBecause == null && profile != null) {
                    inexactBecause = profile.inexactBecause();
                }

                scheduled.add(new ScheduledLoad(load, schedule));
                with = new AddAppliance(load, schedule).applyTo(with);
            }

            var without = engine.cost(usage, plan, range);
            var after = engine.cost(with, plan, range);

            billsBefore.add(without);
            billsAfter.add(after);

            outcomes.add(new PlanOutcome(
                    plan,
                    List.copyOf(scheduled),
                    without.totalRounded(),
                    after.totalRounded(),
                    naiveCost(usage, plan, loads, range, without.totalRounded()),
                    naiveDescription(loads),
                    timingMatters,
                    inexactBecause));
        }

        return new Outcome(List.copyOf(loads), List.copyOf(outcomes),
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
     * What the appliances cost run as soon as they are available.
     *
     * <p>Plugging in and charging straight away is what happens without a timer, so the gap
     * between this and the recommendation is the value of setting one. Anything else would be
     * comparing the recommendation against a strawman.
     *
     * <p>All of them at once, because that is what an untimed household actually does: two cars
     * both plugged in on arrival draw together, and pricing them one at a time would miss it.
     */
    private BigDecimal naiveCost(UsageData usage, Plan plan, List<ApplianceLoad> loads,
            DateRange range, BigDecimal baseline) {

        var with = usage;
        boolean any = false;
        for (var load : loads) {
            if (!(load instanceof SchedulableLoad schedulable)) {
                continue;
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
            with = new AddAppliance(immediately, schedule).applyTo(with);
            any = true;
        }
        if (!any) {
            return BigDecimal.ZERO;
        }
        return engine.cost(with, plan, range).totalRounded().subtract(baseline);
    }

    /** When an untimed household would start them, named in the order they were asked for. */
    private static String naiveDescription(List<ApplianceLoad> loads) {
        var starts = new ArrayList<String>();
        for (var load : loads) {
            if (load instanceof SchedulableLoad schedulable) {
                starts.add(clock(schedulable.availableFromMinute()));
            }
        }
        if (starts.isEmpty()) {
            return "";
        }
        return "starting at " + String.join(" and ", starts) + ", as it would without a timer";
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
