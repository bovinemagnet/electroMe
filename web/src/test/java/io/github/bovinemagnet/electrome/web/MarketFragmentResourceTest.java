package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(MarketFragmentResourceTest.TestData.class)
class MarketFragmentResourceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans",
                    "electrome.market.enabled", "false");
        }
    }

    @Test
    void reportsThatHarvestingIsDisabledWithoutReachingTheNetwork() {
        given().when().get("/fragments/market").then()
                .statusCode(200)
                .body(containsString("disabled"))
                .body(not(containsString("Fetch market plans")));
    }

    @Test
    void thePageStillRendersWithoutAHarvest() {
        given().when().get("/").then()
                .statusCode(200)
                .body(containsString("id=\"market\""));
    }
}
