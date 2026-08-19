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
@TestProfile(DashboardTest.TestData.class)
class DashboardTest {

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
    void leadsWithTheVerdict() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(containsString("Cheapest of the 2 plans compared"))
                .body(containsString("$14.00"));
    }

    @Test
    void rendersTheComparisonTable() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(containsString("Flat rate"))
                .body(containsString("Time of use"))
                .body(containsString("$14.60"))
                .body(containsString("+$0.60"));
    }

    @Test
    void marksTheCheapestPlan() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(containsString("class=\"cheapest\""))
                .body(containsString("BEST"));
    }

    @Test
    void embedsChartDataInTheFragment() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(containsString("data-chart="))
                .body(containsString("heatmap"));
    }

    @Test
    void omitsThePeakShareTileWhenTheBestPlanHasNoTimeOfUseBands() {
        // The flat plan wins here, so a "share of bill from the peak window" figure would be
        // meaningless rather than merely zero.
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(not(containsString("of your bill, from")));
    }

    @Test
    void showsWhereTheMoneyGoesAsRankedBars() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(containsString("Where the money goes"))
                .body(containsString("class=\"ranked\""));
    }

    @Test
    void defaultsToTheAvailableWindowWhenNoDatesGiven() {
        given().when().get("/fragments/dashboard").then()
                .statusCode(200)
                .body(containsString("Flat rate"));
    }

    @Test
    void rejectsAnInvertedRange() {
        given().when().get("/fragments/dashboard?from=2025-01-02&to=2025-01-01").then()
                .statusCode(400);
    }

    @Test
    void rejectsAnUnparseableDate() {
        given().when().get("/fragments/dashboard?from=yesterday&to=2025-01-01").then()
                .statusCode(400);
    }

    @Test
    void reloadingPlansReturnsTheDashboard() {
        given().when().post("/fragments/plans/reload" + WINDOW).then()
                .statusCode(200)
                .body(containsString("Flat rate"));
    }
}
