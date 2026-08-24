package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The market screen, without touching the network.
 *
 * <p>Nothing here harvests. That is the property worth holding: the screen has to render, say
 * what it would do and offer the read, on a machine with no route to the register at all.
 */
@QuarkusTest
@TestProfile(MarketResourceTest.TestData.class)
class MarketResourceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans",
                    "electrome.market.enabled", "true",
                    "electrome.market.cache-dir", "src/test/resources/market-cache");
        }
    }

    @Test
    void offersTheReadWithoutHavingMadeIt() {
        given().when().get("/market").then()
                .statusCode(200)
                .body(containsString("Read the register"))
                .body(containsString("Nothing has been read from the register yet"));
    }

    /** Saving is the whole point, so the reader is told where it would land before it lands. */
    @Test
    void namesTheDirectoryItWouldWriteInto() {
        given().when().get("/market").then()
                .statusCode(200)
                .body(containsString("test-plans"));
    }

    @Test
    void appearsInTheNavigationOnEveryScreen() {
        given().when().get("/").then()
                .statusCode(200)
                .body(containsString("href=\"/market?"))
                .body(containsString(">Market</a>"));
    }

    @Test
    void marksItselfAsTheScreenInView() {
        given().when().get("/market").then()
                .statusCode(200)
                .body(containsString("nav-link current"));
    }

    /** A criteria change swaps the table alone, so the fragment has to stand up by itself. */
    @Test
    void servesTheTableAsAFragment() {
        given().when().get("/market/results?sort=PEAK_RATE&limit=25").then()
                .statusCode(200)
                .body(containsString("Nothing has been read"))
                .body(not(containsString("<html")));
    }

    @Test
    void savingNothingSaysSoRatherThanFailing() {
        given().formParam("plans", java.util.List.of())
                .when().post("/market/save").then()
                .statusCode(200)
                .body(containsString("Nothing was written"));
    }

    /** A window narrowed on another screen has to survive the trip to this one. */
    @Test
    void carriesTheDateWindowInEveryLink() {
        given().when().get("/market?from=2025-01-01&to=2025-03-31").then()
                .statusCode(200)
                .body(containsString("2025-01-01"))
                .body(containsString("2025-03-31"));
    }
}
