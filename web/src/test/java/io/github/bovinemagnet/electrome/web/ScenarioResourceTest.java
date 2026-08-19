package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ScenarioResourceTest.TestData.class)
class ScenarioResourceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    private static final String WINDOW = "?from=2025-01-01&to=2025-01-02";

    @Test
    void rendersTheScenarioPanel() {
        given().when().get("/fragments/scenarios" + WINDOW).then()
                .statusCode(200)
                .body(containsString("What would change the answer"))
                .body(containsString("solar"))
                .body(containsString("battery"))
                .body(containsString("data-chart="));
    }

    @Test
    void honoursScenarioParameters() {
        given().when()
                .get("/fragments/scenarios" + WINDOW + "&solarKW=10&batteryKWh=20&shiftPercent=50")
                .then()
                .statusCode(200)
                .body(containsString("10 kW"))
                .body(containsString("20 kWh"))
                .body(containsString("50%"));
    }

    @Test
    void statesTheAssumptionsItRestsOn() {
        given().when().get("/fragments/scenarios" + WINDOW).then()
                .statusCode(200)
                .body(containsString("cloudless"))
                .body(containsString("scaled to a year"))
                .body(containsString("not a perfect-foresight"));
    }

    @Test
    void rejectsAnInvertedRange() {
        given().when().get("/fragments/scenarios?from=2025-01-02&to=2025-01-01").then()
                .statusCode(400);
    }
}
