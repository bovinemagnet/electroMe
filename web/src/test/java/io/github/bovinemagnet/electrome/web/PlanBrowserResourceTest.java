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
 * The plan browser screen.
 *
 * <p>Two fixture plans: "Flat rate" and "Time of use", both from the retailer "Test", neither
 * carrying an eligibility requirement.
 */
@QuarkusTest
@TestProfile(PlanBrowserResourceTest.TestData.class)
class PlanBrowserResourceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    @Test
    void servesTheBrowserScreen() {
        given().when().get("/plans").then()
                .statusCode(200)
                .body(containsString("Flat rate"))
                .body(containsString("Time of use"));
    }

    @Test
    void theBrowserScreenCarriesTheSharedShell() {
        given().when().get("/plans").then()
                .statusCode(200)
                .body(containsString("electroMe"))
                .body(containsString("vendor/htmx.min.js"));
    }

    // -----------------------------------------------------------------
    // Criteria arrive in the query string.
    // -----------------------------------------------------------------

    @Test
    void honoursASearchCriterion() {
        given().queryParam("search", "flat").when().get("/fragments/browser").then()
                .statusCode(200)
                .body(containsString("Flat rate"))
                .body(not(containsString("Time of use")));
    }

    @Test
    void honoursAShapeCriterion() {
        // Asserted on the row link rather than the plan name: the verdict above the table
        // names the cheapest plan of all, which is deliberately not narrowed by criteria.
        given().queryParam("shape", "TIME_OF_USE").when().get("/fragments/browser").then()
                .statusCode(200)
                .body(containsString("Time of use"))
                .body(not(containsString("/fragments/browser/flat?")));
    }

    @Test
    void honoursASortCriterion() {
        given().queryParam("sort", "NAME").when().get("/fragments/browser").then()
                .statusCode(200)
                .body(containsString("Flat rate"));
    }

    @Test
    void anUnknownCriterionFallsBackRatherThanFailing() {
        // A hand-edited URL should still show plans.
        given().queryParam("sort", "BY_VIBES").queryParam("shape", "SQUARE")
                .when().get("/fragments/browser").then()
                .statusCode(200)
                .body(containsString("Flat rate"));
    }

    @Test
    void aBadDateRangeIsStillRejected() {
        given().queryParam("from", "not-a-date").queryParam("to", "2025-01-02")
                .when().get("/fragments/browser").then()
                .statusCode(400);
    }

    // -----------------------------------------------------------------
    // Empty states. Never a blank table.
    // -----------------------------------------------------------------

    @Test
    void aCriterionMatchingNothingNamesWhatExcludedEverything() {
        given().queryParam("search", "nothing-matches-this")
                .when().get("/fragments/browser").then()
                .statusCode(200)
                .body(containsString("nothing-matches-this"))
                .body(containsString("No plans match"));
    }

    @Test
    void anEmptyResultOffersToClearTheCriteria() {
        given().queryParam("search", "nothing-matches-this")
                .when().get("/fragments/browser").then()
                .statusCode(200)
                .body(containsString("Clear"));
    }

    // -----------------------------------------------------------------
    // The detail panel.
    // -----------------------------------------------------------------

    @Test
    void opensAPlanToShowItsBandsAndRates() {
        given().when().get("/fragments/browser/tou").then()
                .statusCode(200)
                .body(containsString("Time of use"))
                .body(containsString("16:00-21:00"))
                .body(containsString("Daily supply"));
    }

    @Test
    void aPlanDetailIsLinkableOnItsOwn() {
        given().when().get("/plans/tou").then()
                .statusCode(200)
                .body(containsString("16:00-21:00"));
    }

    @Test
    void anUnknownPlanIdIs404RatherThanAStackTrace() {
        given().when().get("/fragments/browser/no-such-plan").then().statusCode(404);
        given().when().get("/plans/no-such-plan").then().statusCode(404);
    }

    @Test
    void aLocalPlanIsMarkedAsComingFromAFile() {
        // Local files win on identifier collision, so a reader has to be able to tell.
        given().when().get("/fragments/browser/flat").then()
                .statusCode(200)
                .body(containsString("file"));
    }

    @Test
    void theDetailPanelStatesThatFeesAreNotCosted() {
        given().when().get("/fragments/browser/tou").then()
                .statusCode(200)
                .body(containsString("not included in the costed figures"));
    }
}
