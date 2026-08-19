package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(MissingDataTest.NoData.class)
class MissingDataTest {

    public static class NoData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "does-not-exist.csv",
                    "electrome.plans.dir", "does-not-exist");
        }
    }

    @Inject UsageStore usage;
    @Inject PlanStore plans;

    @Test
    void missingUsageFileIsReportedNotThrown() {
        assertThat(usage.loaded()).isFalse();
        assertThat(usage.loadError()).contains("does-not-exist.csv");
    }

    @Test
    void missingPlanDirectoryIsReportedNotThrown() {
        assertThat(plans.plans()).isEmpty();
        assertThat(plans.loadErrors()).isNotEmpty();
    }
}
