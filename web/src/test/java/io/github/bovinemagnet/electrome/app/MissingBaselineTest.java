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
 * A baseline naming a plan that is not there must not break the page.
 *
 * <p>The rest of the comparison is still true; it simply has nothing to call "your plan".
 */
@QuarkusTest
@TestProfile(MissingBaselineTest.PointingAtNothing.class)
class MissingBaselineTest {

    public static class PointingAtNothing implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans",
                    "electrome.baseline.plan", "a-plan-nobody-has");
        }
    }

    @Inject ComparisonService service;

    @Test
    void ranksPlansWithNoBaselineToMeasureAgainst() {
        var comparison = service.compare(
                new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2)));
        assertThat(comparison.results()).hasSize(2);
        assertThat(comparison.baseline()).isEmpty();
        assertThat(comparison.results()).allSatisfy(
                r -> assertThat(r.comparedToBaseline()).isFalse());
    }
}
