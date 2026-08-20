package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.appliance.SchedulableLoad;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plan picked out of the market, scheduled against like any other.
 *
 * <p>This is the seam the whole addendum was built towards, and it only holds if every part of
 * it does: the register's capped free window has to survive mapping, the tariff model has to
 * express the cap, the scheduler has to know how much of it the household has already spent,
 * and the shortlist has to carry the plan from the ranking table to the appliance screen
 * without it becoming something else on the way.
 */
class PickedPlanWhatIfTest {

    private static final LocalDate DAY = LocalDate.of(2025, 1, 1);
    private static final DateRange ONE_DAY = new DateRange(DAY, DAY);

    private static ShortlistService shortlistOn(Path directory) {
        var picks = new Shortlist();
        picks.fileName = ".shortlist";
        picks.plansDir = directory.toAbsolutePath().toString();
        picks.load();

        var market = new MarketPlanSource();
        market.enabled = true;
        market.zoneName = "AUSNET";
        market.cacheDir = "src/test/resources/market-cache";

        var plans = new PlanStore();
        plans.plansDir = "src/test/resources/test-plans";
        plans.market = market;
        plans.reload();

        var service = new ShortlistService();
        service.picks = picks;
        service.planStore = plans;
        service.market = market;
        return service;
    }

    /** A day that has already spent 46 kWh of the plan's 50 kWh free window. */
    private static UsageData almostSpentCap() {
        var readings = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            var kWh = minute >= 11 * 60 && minute < 15 * 60
                    ? new BigDecimal("5.75")
                    : new BigDecimal("0.1");
            readings.add(new IntervalReading(DAY.atStartOfDay().plusMinutes(minute),
                    Duration.ofMinutes(30), kWh, Quality.ACTUAL));
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    /** A 20 kWh charge that may run only inside the free window. */
    private static SchedulableLoad car() {
        return new SchedulableLoad("Car", new BigDecimal("20"), new BigDecimal("7.4"),
                11 * 60, 15 * 60, 7, Set.of(), true, false);
    }

    @Test
    void aPickedCappedPlanIsScheduledAgainstItsRemainingHeadroom(@TempDir Path directory) {
        var shortlist = shortlistOn(directory);
        shortlist.picks.add(List.of("CAP001@VEC"));

        // It arrived from the register with its cap intact.
        var picked = shortlist.entryFor("CAP001@VEC").orElseThrow().plan();
        var free = picked.charges().stream()
                .filter(TimeOfUse.class::isInstance).map(TimeOfUse.class::cast)
                .findFirst().orElseThrow()
                .bands().stream().filter(b -> b.fromMinuteOfDay() == 11 * 60)
                .findFirst().orElseThrow();
        assertThat(free.capped()).isTrue();
        assertThat(free.tiers().get(0).thresholdKWh()).isEqualByComparingTo("50");

        var outcome = new ApplianceService().evaluate(
                almostSpentCap(), shortlist.plans(), car(), ONE_DAY);

        // The plan is on the appliance screen at all, which is the join point under test.
        assertThat(outcome.perPlan()).extracting(o -> o.plan().id())
                .contains("CAP001@VEC", "flat", "tou");

        // And the schedule respects what the household has already spent: 4 kWh of the day's
        // allowance is left, so only 4 of the car's 20 kWh is free and 16 costs 9.9c.
        var schedule = outcome.forPlan("CAP001@VEC").schedule();
        assertThat(schedule.fits()).isTrue();
        assertThat(schedule.marginalCostCents()).isEqualByComparingTo("158.40044");
        assertThat(outcome.forPlan("CAP001@VEC").exact()).isFalse();
        assertThat(outcome.forPlan("CAP001@VEC").inexactBecause())
                .contains("11:00-15:00 is capped at 50 kWh/day");
    }

    /** A plan file and a picked plan are the same thing to the appliance modelling. */
    @Test
    void aPickedPlanIsTreatedNoDifferentlyFromAPlanFile(@TempDir Path directory) {
        var shortlist = shortlistOn(directory);
        shortlist.picks.add(List.of("CAP001@VEC"));

        var outcome = new ApplianceService().evaluate(
                almostSpentCap(), shortlist.plans(), car(), ONE_DAY);

        assertThat(outcome.perPlan()).hasSize(shortlist.plans().size());
        assertThat(outcome.perPlan()).allSatisfy(plan -> {
            assertThat(plan.totalWithout()).isNotNull();
            assertThat(plan.totalWith()).isNotNull();
        });
        assertThat(outcome.bestPlanAfter()).isNotNull();
    }

    /** Bands are read from the mapped plan, not assumed: a sanity check on the fixture. */
    @Test
    void theFixturePlanTilesTheDay() {
        var market = new MarketPlanSource();
        market.enabled = true;
        market.zoneName = "AUSNET";
        market.cacheDir = "src/test/resources/market-cache";
        var plan = market.fromCache("CAP001@VEC").orElseThrow();
        var bands = plan.charges().stream().filter(TimeOfUse.class::isInstance)
                .map(TimeOfUse.class::cast).findFirst().orElseThrow().bands();
        assertThat(bands).extracting(Band::describe)
                .contains("11:00-15:00", "16:00-23:00");
    }
}
