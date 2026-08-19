package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ComparisonServiceTest.TestData.class)
class ComparisonServiceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    @Inject ComparisonService service;

    private static final DateRange BOTH_DAYS =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    @Test
    void costsEveryPlan() {
        var comparison = service.compare(BOTH_DAYS);
        assertThat(comparison.results()).hasSize(2);
        assertThat(comparison.empty()).isFalse();
    }

    @Test
    void ranksCheapestFirst() {
        var comparison = service.compare(BOTH_DAYS);
        assertThat(comparison.results().stream().map(PlanResult::total).toList()).isSorted();
        assertThat(comparison.results().get(0).cheapest()).isTrue();
        assertThat(comparison.results().get(1).cheapest()).isFalse();
    }

    @Test
    void flatPlanCostsTheExpectedAmount() {
        // 48 kWh at 25c = $12.00 usage, plus 2 days at 100c = $2.00 supply.
        var flat = service.compare(BOTH_DAYS).results().stream()
                .filter(r -> r.bill().plan().id().equals("flat"))
                .findFirst().orElseThrow();
        assertThat(flat.total()).isEqualByComparingTo("14.00");
    }

    @Test
    void touPlanCostsTheExpectedAmount() {
        // Per day: 16 kWh at 20c, 5 kWh at 50c, 3 kWh at 20c = $6.30. Two days plus supply.
        var tou = service.compare(BOTH_DAYS).results().stream()
                .filter(r -> r.bill().plan().id().equals("tou"))
                .findFirst().orElseThrow();
        assertThat(tou.total()).isEqualByComparingTo("14.60");
    }

    @Test
    void differenceFromBestIsZeroForTheCheapest() {
        var comparison = service.compare(BOTH_DAYS);
        assertThat(comparison.results().get(0).differenceFromBest()).isEqualByComparingTo("0");
        assertThat(comparison.results().get(1).differenceFromBest()).isEqualByComparingTo("0.60");
    }

    @Test
    void spreadIsTheGapBetweenCheapestAndDearest() {
        assertThat(service.compare(BOTH_DAYS).spread()).isEqualByComparingTo("0.60");
    }

    @Test
    void bestIsTheCheapestResult() {
        assertThat(service.compare(BOTH_DAYS).best().orElseThrow().bill().plan().id())
                .isEqualTo("flat");
    }

    @Test
    void maxTotalScalesTheBars() {
        assertThat(service.compare(BOTH_DAYS).maxTotal()).isEqualByComparingTo("14.60");
    }
}
