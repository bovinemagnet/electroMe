package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Ticking a plan out of the market and finding it everywhere else.
 *
 * <p>Ordered deliberately: the selection is state that outlives a request, so picking, seeing
 * the effect and unpicking are one story rather than three independent assertions. The plans
 * directory is the copy under {@code build}, because the shortlist is written beside the plan
 * files and a test should not leave anything in the source tree.
 */
@QuarkusTest
@TestProfile(ShortlistPickingTest.WithAMarketCache.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShortlistPickingTest {

    public static class WithAMarketCache implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "build/resources/test/test-plans",
                    "electrome.baseline.plan", "flat",
                    "electrome.market.enabled", "true",
                    "electrome.market.cache-dir", "src/test/resources/market-cache");
        }
    }

    private static final String FROM = "2025-01-01";
    private static final String TO = "2025-01-02";
    private static final String WINDOW = "?from=" + FROM + "&to=" + TO;

    @Test
    @Order(1)
    void startsWithOnlyTheHouseholdsOwnPlans() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(not(containsString("Four Hour Free")));
    }

    @Test
    @Order(2)
    void picksAPlanAndSaysSoOnTheRow() {
        given().formParam("plans", "CAP001@VEC")
                .formParam("from", FROM).formParam("to", TO)
                .when().post("/fragments/browser/shortlist").then()
                .statusCode(200)
                .body(containsString("Shortlisted"));
    }

    /** The acceptance case: a picked plan reaches the dashboard's comparison. */
    @Test
    @Order(3)
    void thePickedPlanJoinsTheDashboardShortlist() {
        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(containsString("Four Hour Free"));
    }

    /** And the appliance screen schedules against it, on the same footing as a plan file. */
    @Test
    @Order(4)
    void thePickedPlanReachesTheApplianceScreen() {
        given().when().get("/what-if" + WINDOW + "&appliance=ev").then()
                .statusCode(200)
                .body(containsString("Four Hour Free"));
    }

    /**
     * Its detail view says where it came from and when.
     *
     * <p>These fixtures were never harvested, so the register does not list them: the plan is
     * shown as no longer published, priced from what was last cached.
     */
    @Test
    @Order(5)
    void thePickedPlanCarriesItsProvenance() {
        given().when().get("/fragments/browser/CAP001@VEC" + WINDOW).then()
                .statusCode(200)
                .body(containsString("CAP001@VEC"))
                .body(containsString("No longer published"));
    }

    /** The row carries the control that takes it off again, not just the endpoint. */
    @Test
    @Order(6)
    void theRowOffersAWayToRemoveIt() {
        given().when().get("/fragments/browser" + WINDOW).then()
                .statusCode(200)
                .body(containsString("/fragments/browser/shortlist/remove"))
                .body(containsString("CAP001@VEC"));
    }

    @Test
    @Order(7)
    void unpickingRemovesItAgain() {
        given().formParam("plan", "CAP001@VEC")
                .formParam("from", FROM).formParam("to", TO)
                .when().post("/fragments/browser/shortlist/remove").then()
                .statusCode(200);

        given().when().get("/fragments/dashboard" + WINDOW).then()
                .statusCode(200)
                .body(not(containsString("Four Hour Free")));
    }
}
