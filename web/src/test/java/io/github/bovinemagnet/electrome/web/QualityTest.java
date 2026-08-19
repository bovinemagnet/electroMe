package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(QualityTest.TestData.class)
class QualityTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    @Test
    void reportsCleanDataAsSuch() {
        given().when().get("/fragments/quality").then()
                .statusCode(200)
                .body(containsString("96 intervals"))
                .body(containsString("Nothing to report"));
    }

    @Test
    void reportsTheHeaviestDayAgainstTheMedian() {
        given().when().get("/fragments/quality").then()
                .statusCode(200)
                .body(containsString("Heaviest day"))
                .body(containsString("24.0"));
    }
}
