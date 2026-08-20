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

/**
 * Savings measured against the household's own tariff rather than against the field.
 *
 * <p>"Cheapest plan" and "what switching saves you" are different questions, and only the
 * second is actionable. Without a nominated baseline the application could answer the first
 * and let a reader mistake it for the second.
 */
@QuarkusTest
@TestProfile(BaselineComparisonTest.OnTheFlatPlan.class)
class BaselineComparisonTest {

    public static class OnTheFlatPlan implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans",
                    "electrome.baseline.plan", "tou");
        }
    }

    @Inject ComparisonService service;

    private static final DateRange BOTH_DAYS =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    private static PlanResult resultFor(Comparison comparison, String id) {
        return comparison.results().stream()
                .filter(r -> r.bill().plan().id().equals(id))
                .findFirst().orElseThrow();
    }

    @Test
    void namesTheHouseholdsOwnTariff() {
        var comparison = service.compare(BOTH_DAYS);
        assertThat(comparison.baselinePlanId()).isEqualTo("tou");
        assertThat(comparison.baseline()).isPresent();
        assertThat(comparison.baseline().orElseThrow().bill().plan().id()).isEqualTo("tou");
    }

    @Test
    void measuresEveryPlanAgainstIt() {
        var comparison = service.compare(BOTH_DAYS);
        // The flat plan costs $14.00 and the household's own $14.60.
        var flat = resultFor(comparison, "flat");
        assertThat(flat.baseline()).isFalse();
        assertThat(flat.differenceFromBaseline()).isEqualByComparingTo("-0.60");
        assertThat(flat.savingAgainstBaseline()).isEqualByComparingTo("0.60");
        assertThat(flat.cheaperThanBaseline()).isTrue();
    }

    @Test
    void theBaselineCostsNothingAgainstItself() {
        var own = resultFor(service.compare(BOTH_DAYS), "tou");
        assertThat(own.baseline()).isTrue();
        assertThat(own.differenceFromBaseline()).isEqualByComparingTo("0");
        assertThat(own.cheaperThanBaseline()).isFalse();
    }
}
