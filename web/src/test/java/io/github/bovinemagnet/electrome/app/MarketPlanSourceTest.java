package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(MarketPlanSourceTest.TestData.class)
class MarketPlanSourceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans",
                    "electrome.market.enabled", "false");
        }
    }

    @Inject MarketPlanSource market;
    @Inject PlanStore plans;

    @Test
    void startsWithoutHarvesting() {
        // The application must be useful with no network at all.
        assertThat(market.harvested()).isFalse();
        assertThat(market.plans()).isEmpty();
        assertThat(market.lastReport()).isEmpty();
    }

    @Test
    void planStoreServesLocalPlansWithoutAHarvest() {
        assertThat(plans.plans()).hasSize(2);
        assertThat(plans.byId("flat")).isPresent();
        assertThat(plans.localPlans()).isEqualTo(plans.plans());
    }

    @Test
    void harvestingWhileDisabledReportsRatherThanThrows() {
        market.harvest();
        assertThat(market.harvestError()).isPresent();
        assertThat(market.harvestError().get()).contains("disabled");
        assertThat(market.plans()).isEmpty();
    }
}
