package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(StoreTest.TestData.class)
class StoreTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    @Inject UsageStore usage;
    @Inject PlanStore plans;

    @Test
    void loadsTheUsageExport() {
        assertThat(usage.loaded()).isTrue();
        assertThat(usage.usage().consumption().readings()).hasSize(96);
        assertThat(usage.available().from()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(usage.available().to()).isEqualTo(LocalDate.of(2025, 1, 2));
    }

    @Test
    void defaultWindowClampsToAvailableData() {
        assertThat(usage.defaultWindow().to()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(usage.defaultWindow().from()).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void exposesTheQualityReport() {
        assertThat(usage.report().clean()).isTrue();
    }

    @Test
    void loadsPlansFromTheDirectory() {
        assertThat(plans.plans()).hasSize(2);
        assertThat(plans.byId("flat")).isPresent();
        assertThat(plans.byId("nonexistent")).isEmpty();
        assertThat(plans.loadErrors()).isEmpty();
    }

    @Test
    void reloadIsIdempotent() {
        plans.reload();
        assertThat(plans.plans()).hasSize(2);
    }
}
