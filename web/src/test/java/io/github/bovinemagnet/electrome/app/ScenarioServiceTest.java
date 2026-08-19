package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ScenarioServiceTest.TestData.class)
class ScenarioServiceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    @Inject ScenarioService service;

    private static final DateRange BOTH_DAYS =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    @Test
    void evaluatesEveryScenarioPlusTheCombination() {
        var outcomes = service.evaluate(BOTH_DAYS);
        assertThat(outcomes).hasSize(4);
        assertThat(outcomes).extracting(ScenarioOutcome::label)
                .anySatisfy(l -> assertThat(l).contains("Shift"))
                .anySatisfy(l -> assertThat(l).contains("solar"))
                .anySatisfy(l -> assertThat(l).contains("battery"));
    }

    @Test
    void shiftingLoadNeverMakesTheCheapestOptionDearer() {
        var shift = service.evaluate(BOTH_DAYS).stream()
                .filter(o -> o.label().contains("Shift")).findFirst().orElseThrow();
        assertThat(shift.saving().signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void solarReducesTheBestAvailableBill() {
        var solar = service.evaluate(BOTH_DAYS).stream()
                .filter(o -> o.label().contains("solar") && !o.label().contains("battery"))
                .findFirst().orElseThrow();
        assertThat(solar.scenarioBest()).isLessThan(solar.baselineBest());
        assertThat(solar.saving()).isPositive();
        assertThat(solar.worthwhile()).isTrue();
    }

    @Test
    void savingIsAnnualisedFromTheWindow() {
        // Two days scaled to 365 means the annual figure far exceeds the raw difference.
        var solar = service.evaluate(BOTH_DAYS).stream()
                .filter(o -> o.label().contains("solar") && !o.label().contains("battery"))
                .findFirst().orElseThrow();
        assertThat(solar.saving())
                .isGreaterThan(solar.baselineBest().subtract(solar.scenarioBest()));
    }

    @Test
    void eachScenarioIsScoredAgainstTheBestPlanUnderThatScenario() {
        assertThat(service.evaluate(BOTH_DAYS))
                .allSatisfy(o -> assertThat(o.comparison().best()).isPresent());
    }

    @Test
    void honoursTheSuppliedParameters() {
        var outcomes = service.evaluate(BOTH_DAYS, new BigDecimal("10"),
                new BigDecimal("20"), new BigDecimal("0.5"));
        assertThat(outcomes).extracting(ScenarioOutcome::label)
                .anySatisfy(l -> assertThat(l).contains("10 kW"))
                .anySatisfy(l -> assertThat(l).contains("20 kWh"))
                .anySatisfy(l -> assertThat(l).contains("50%"));
    }

    @Test
    void aBiggerArraySavesMore() {
        var small = service.evaluate(BOTH_DAYS, new BigDecimal("3"),
                new BigDecimal("13.5"), new BigDecimal("0.3"));
        var large = service.evaluate(BOTH_DAYS, new BigDecimal("10"),
                new BigDecimal("13.5"), new BigDecimal("0.3"));
        var pick = (java.util.function.Function<java.util.List<ScenarioOutcome>, BigDecimal>)
                list -> list.stream()
                        .filter(o -> o.label().contains("solar") && !o.label().contains("battery"))
                        .findFirst().orElseThrow().saving();
        assertThat(pick.apply(large)).isGreaterThan(pick.apply(small));
    }
}
