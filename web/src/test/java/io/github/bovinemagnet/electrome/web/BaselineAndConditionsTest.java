package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the rendered page says about savings and about what a total assumes.
 *
 * <p>Asserted through the HTTP layer rather than on the view records, because the point of
 * both features is that a reader sees them. A Qute typo that silently dropped the marker would
 * pass every unit test in the view package.
 */
@QuarkusTest
@TestProfile(BaselineAndConditionsTest.OnAKnownPlan.class)
class BaselineAndConditionsTest {

    public static class OnAKnownPlan implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/baseline-plans",
                    "electrome.baseline.plan", "mine");
        }
    }

    private static final String WINDOW = "?from=2025-01-01&to=2025-01-02";

    /**
     * The household's own plan costs $16.40, the capped plan $13.88 and the plain cheaper one
     * $14.00. Ranking alone says "capped is best"; only the baseline says what switching to it
     * is worth.
     */
    @Test
    void showsWhatSwitchingWouldSaveAgainstTheHouseholdsOwnPlan() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(containsString("vs your plan"))
                .body(containsString("your plan"))
                .body(containsString("&minus;$2.52"))
                .body(containsString("&minus;$2.40"));
    }

    @Test
    void marksATotalThatDependsOnAConditionalDiscount() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(containsString("class=\"conditional-total\""))
                .body(containsString("Pay on time: pay every bill by its due date"));
    }

    /** A capped band reconciles line by line: one line per block, each with its own rate. */
    @Test
    void breaksACappedWindowIntoItsBlocks() {
        given().when().get("/fragments/browser/capped" + WINDOW).then()
                .statusCode(200)
                .body(containsString("Usage 11:00-15:00 to 1 kWh"))
                .body(containsString("Usage 11:00-15:00 balance"))
                .body(containsString("This total assumes a conditional discount"));
    }
}
