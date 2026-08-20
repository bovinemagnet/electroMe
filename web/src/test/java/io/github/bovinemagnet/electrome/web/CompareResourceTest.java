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
 * The side-by-side comparison screen.
 *
 * <p>Six fixture plans spanning time-of-use, flat and block shapes, so the matrix has something
 * to align and the crossover has a reason to abandon its closed form.
 */
@QuarkusTest
@TestProfile(CompareResourceTest.TestData.class)
class CompareResourceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/compare-plans");
        }
    }

    // -----------------------------------------------------------------
    // Two to four plans.
    // -----------------------------------------------------------------

    @Test
    void comparesTwoPlansSideBySide() {
        given().queryParam("plans", "dear-evenings,kind-evenings")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("Dear Evenings"))
                .body(containsString("Kind Evenings"));
    }

    @Test
    void comparesFourPlans() {
        given().queryParam("plans", "dear-evenings,kind-evenings,simple-flat,solar-soak")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("Solar Soak"))
                .body(containsString("Simple Flat"));
    }

    @Test
    void everyDifferenceIsAttributableToANamedComponent() {
        given().queryParam("plans", "dear-evenings,kind-evenings")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("Daily supply"))
                .body(containsString("Evening peak"))
                .body(containsString("cheaper than"));
    }

    @Test
    void reportsTheConsumptionAtWhichAMarginalRankingFlips() {
        // These two are genuinely close: they swap places a little below what the household
        // actually used, which is exactly the fragility a ranking alone would hide.
        given().queryParam("plans", "dear-evenings,kind-evenings")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("swap places at about"))
                .body(containsString("kWh a year"))
                .body(containsString("you used"));
    }

    @Test
    void saysWhenAPlanWinsAtEveryLevelOfUse() {
        // Worse Everywhere is dearer on both supply and usage, so no crossover exists. Saying
        // so is a stronger finding than any number, and reporting a negative one would be wrong.
        given().queryParam("plans", "simple-flat,worse-everywhere")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("wins at every level of use"))
                .body(not(containsString("swap places")));
    }

    @Test
    void namesTheApproximationTheCrossoverRestsOn() {
        given().queryParam("plans", "dear-evenings,simple-flat")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("evenly"));
    }

    @Test
    void handlesABlockTariffWhoseCrossoverNeedsBisection() {
        given().queryParam("plans", "block-tariff,simple-flat")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("Block rates"));
    }

    // -----------------------------------------------------------------
    // Selections that are not quite right.
    // -----------------------------------------------------------------

    @Test
    void namesAnUnknownIdentifierRatherThanDroppingItSilently() {
        // Comparing three while believing you compared four is the failure to avoid.
        given().queryParam("plans", "dear-evenings,kind-evenings,no-such-plan")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("Dear Evenings"))
                .body(containsString("no-such-plan"));
    }

    @Test
    void comparesTheFirstFourAndNamesTheSurplus() {
        given().queryParam("plans",
                        "dear-evenings,kind-evenings,simple-flat,solar-soak,block-tariff,spare-plan")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("Dear Evenings"))
                .body(containsString("spare-plan"))
                .body(containsString("block-tariff"));
    }

    @Test
    void fewerThanTwoResolvablePlansExplainsRatherThanRenderingOneColumn() {
        given().queryParam("plans", "dear-evenings")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("at least two"))
                .body(containsString("/plans"))
                .body(not(containsString("cheaper than")));
    }

    @Test
    void noSelectionAtAllPointsAtTheBrowser() {
        given().when().get("/compare").then()
                .statusCode(200)
                .body(containsString("at least two"))
                .body(containsString("/plans"));
    }

    @Test
    void everyIdentifierUnknownIsNotAServerError() {
        given().queryParam("plans", "nope,also-nope")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("at least two"))
                .body(containsString("nope"));
    }

    // -----------------------------------------------------------------
    // The URL fully describes the comparison.
    // -----------------------------------------------------------------

    @Test
    void theComparisonIsLinkable() {
        // Same URL, same screen: nothing is held in a session.
        String first = given().queryParam("plans", "dear-evenings,kind-evenings")
                .queryParam("from", "2025-01-01").queryParam("to", "2025-01-02")
                .when().get("/compare").then().statusCode(200).extract().asString();
        String second = given().queryParam("plans", "dear-evenings,kind-evenings")
                .queryParam("from", "2025-01-01").queryParam("to", "2025-01-02")
                .when().get("/compare").then().statusCode(200).extract().asString();

        org.assertj.core.api.Assertions.assertThat(first).isEqualTo(second);
    }

    @Test
    void acceptsRepeatedParametersAsWellAsACommaSeparatedList() {
        // Checkboxes in the browser submit one parameter per plan; a hand-written link uses a
        // list. Both have to mean the same thing.
        given().queryParam("plans", "dear-evenings").queryParam("plans", "kind-evenings")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("Dear Evenings"))
                .body(containsString("Kind Evenings"));
    }

    @Test
    void carriesTheWindowOnEveryNavigationLink() {
        given().queryParam("plans", "dear-evenings,kind-evenings")
                .queryParam("from", "2025-01-02").queryParam("to", "2025-01-02")
                .when().get("/compare").then()
                .statusCode(200)
                .body(containsString("from=2025-01-02"));
    }

    @Test
    void aBadDateRangeIsStillRejected() {
        given().queryParam("plans", "dear-evenings,kind-evenings")
                .queryParam("from", "yesterday").queryParam("to", "2025-01-02")
                .when().get("/compare").then()
                .statusCode(400);
    }

    @Test
    void theBrowserOffersAWayToStartAComparison() {
        given().when().get("/plans").then()
                .statusCode(200)
                .body(containsString("/compare"))
                .body(containsString("Compare selected"));
    }
}
