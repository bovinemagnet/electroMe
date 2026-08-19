package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.scenario.AddBattery;
import io.github.bovinemagnet.electrome.core.scenario.AddSolar;
import io.github.bovinemagnet.electrome.core.scenario.BatterySpecification;
import io.github.bovinemagnet.electrome.core.scenario.LoadShift;
import io.github.bovinemagnet.electrome.core.scenario.Scenario;
import io.github.bovinemagnet.electrome.core.scenario.ScenarioEngine;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Costs each what-if scenario against every plan. */
@ApplicationScoped
public class ScenarioService {

    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    @Inject UsageStore usageStore;
    @Inject PlanStore planStore;
    @Inject ComparisonService comparisons;

    public List<ScenarioOutcome> evaluate(DateRange range) {
        return evaluate(range, new BigDecimal("6.6"), new BigDecimal("13.5"),
                new BigDecimal("0.30"));
    }

    public List<ScenarioOutcome> evaluate(
            DateRange range, BigDecimal solarKW, BigDecimal batteryKWh, BigDecimal shift) {

        var baseline = comparisons.compare(range);
        BigDecimal baselineBest = baseline.best().map(PlanResult::total).orElse(BigDecimal.ZERO);

        var outcomes = new ArrayList<ScenarioOutcome>();
        outcomes.add(outcomeFor(LoadShift.outOfPeak(shift), range, baselineBest));
        outcomes.add(outcomeFor(AddSolar.of(solarKW), range, baselineBest));
        outcomes.add(outcomeFor(batteryScenario(batteryKWh), range, baselineBest));

        // Solar and battery together, since that is the combination people actually buy.
        var combined = List.<Scenario>of(AddSolar.of(solarKW), batteryScenario(batteryKWh));
        var combinedComparison =
                comparisons.compare(range, ScenarioEngine.apply(usageStore.usage(), combined));
        BigDecimal combinedBest =
                combinedComparison.best().map(PlanResult::total).orElse(BigDecimal.ZERO);
        outcomes.add(new ScenarioOutcome(
                "Add " + trim(solarKW) + " kW of solar and a " + trim(batteryKWh) + " kWh battery",
                combinedComparison, baselineBest, combinedBest,
                annualise(baselineBest.subtract(combinedBest), range)));

        return List.copyOf(outcomes);
    }

    private ScenarioOutcome outcomeFor(
            Scenario scenario, DateRange range, BigDecimal baselineBest) {
        var usage = ScenarioEngine.apply(usageStore.usage(), List.of(scenario));
        var comparison = comparisons.compare(range, usage);
        BigDecimal scenarioBest = comparison.best().map(PlanResult::total).orElse(BigDecimal.ZERO);
        return new ScenarioOutcome(scenario.label(), comparison, baselineBest, scenarioBest,
                annualise(baselineBest.subtract(scenarioBest), range));
    }

    /**
     * Charges in the cheapest band of the first time-of-use plan defined.
     *
     * <p>Falls back to a fixed overnight window when no time-of-use plan exists, since a
     * battery on a flat tariff has no arbitrage to exploit and the window is then arbitrary.
     */
    private AddBattery batteryScenario(BigDecimal capacityKWh) {
        var specification = new BatterySpecification(
                capacityKWh, new BigDecimal("5"), new BigDecimal("0.90"));

        for (var plan : planStore.plans()) {
            for (var charge : plan.charges()) {
                if (charge instanceof TimeOfUse tou) {
                    return AddBattery.arbitraging(specification, tou);
                }
            }
        }
        return new AddBattery(specification,
                List.of(Band.parse("00:00", "06:00", DaySelector.ALL, BigDecimal.ZERO)),
                List.of(Band.parse("16:00", "21:00", DaySelector.ALL, BigDecimal.ZERO)));
    }

    /** Scales a window's saving to a year, so a short window is not misread as annual. */
    private static BigDecimal annualise(BigDecimal saving, DateRange range) {
        if (range.days() == 0) {
            return BigDecimal.ZERO;
        }
        return saving.multiply(DAYS_PER_YEAR)
                .divide(BigDecimal.valueOf(range.days()), MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
