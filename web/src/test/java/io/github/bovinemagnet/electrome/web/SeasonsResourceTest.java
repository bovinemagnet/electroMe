package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The seasonal screen, through the HTTP layer.
 *
 * <p>Rendered rather than asserted on the record, because a Qute typo in a template this size
 * would pass every unit test in the app package and fail only in front of a reader.
 */
@QuarkusTest
@TestProfile(SeasonsResourceTest.TestData.class)
class SeasonsResourceTest {

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
    void rendersTheDefaultPoolSeasonSplit() {
        given().when().get("/seasons" + WINDOW).then()
                .statusCode(200)
                .body(containsString("Nov-Mar"))
                .body(containsString("Apr-Oct"))
                .body(containsString("Flat rate"))
                .body(containsString("Time of use"));
    }

    @Test
    void acceptsACustomSplit() {
        given().when().get("/seasons" + WINDOW + "&seasonFrom=JANUARY&seasonTo=JUNE").then()
                .statusCode(200)
                .body(containsString("Jan-Jun"))
                .body(containsString("Jul-Dec"));
    }

    /** A mistyped month shows the default report rather than an error page. */
    @Test
    void fallsBackToTheDefaultForAMonthThatDoesNotExist() {
        given().when().get("/seasons" + WINDOW + "&seasonFrom=Smarch&seasonTo=JUNE").then()
                .statusCode(200)
                .body(containsString("Nov-Mar"));
    }

    /**
     * The fixture window is two days in January, so both fall in the first season and the
     * second is empty. The page must still answer rather than divide by a missing season.
     */
    @Test
    void answersWhenOneSeasonHoldsNoneOfTheWindow() {
        given().when().get("/seasons" + WINDOW).then()
                .statusCode(200)
                .body(containsString("$0.00"));
    }

    /** The controls read as English, not as enum constants. */
    @Test
    void namesTheMonthsReadably() {
        given().when().get("/seasons" + WINDOW).then()
                .statusCode(200)
                .body(containsString(">November<"))
                .body(containsString(">March<"))
                .body(containsString("value=\"NOVEMBER\""));
    }

    @Test
    void appearsInTheNavigation() {
        given().when().get("/" + WINDOW).then()
                .statusCode(200)
                .body(containsString("/seasons"))
                .body(containsString("Seasons"));
    }
}
