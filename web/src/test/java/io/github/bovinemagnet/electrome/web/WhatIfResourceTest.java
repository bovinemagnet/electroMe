package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The appliance what-if screen.
 *
 * <p>Uses the comparison fixtures, which span a deep overnight trough, a flat rate and a block
 * tariff — enough for the recommendation, the "timing makes no difference" case and the
 * inexactness warning all to appear.
 */
@QuarkusTest
@TestProfile(WhatIfResourceTest.TestData.class)
class WhatIfResourceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/compare-plans");
        }
    }

    @Test
    void servesTheScreenWithTheElectricVehiclePresetByDefault() {
        given().when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("Electric vehicle"))
                .body(containsString("Kilometres a week"));
    }

    @Test
    void carriesTheSharedShellAndNavigation() {
        given().when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("electroMe"))
                .body(containsString("/plans"));
    }

    @Test
    void offersEveryPreset() {
        given().when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("Pool pump"))
                .body(containsString("Pool heat pump"))
                .body(containsString("Hot water"))
                .body(containsString("air conditioning"));
    }

    @Test
    void recommendsASpecificRunTimeWithACostAttached() {
        given().queryParam("appliance", "ev")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("Run it"))
                .body(containsString("a year"));
    }

    @Test
    void showsWhatATimerIsWorthOnEveryPlan() {
        // The gap between the recommended time and plugging in without thinking is the value of
        // setting a timer, and it differs per plan.
        given().queryParam("appliance", "ev")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("Timer saves"))
                .body(containsString("Run it"));
    }

    @Test
    void saysTimingMakesNoDifferenceWhereItGenuinelyDoesNot() {
        // The cheapest plan in these fixtures is a block tariff, which has no time structure at
        // all: only how much you use matters, not when. Naming a run time would be invented.
        given().queryParam("appliance", "ev")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("Timing makes no difference on this plan"));
    }

    @Test
    void aTimeOfUsePlanShowsTheAlternativeItWasComparedAgainst() {
        // Restricting the window to the evening peak forces a time-of-use plan to the top, so
        // the recommendation sentence and its alternative both render.
        given().queryParam("appliance", "pool-pump")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("Pool pump"))
                .body(containsString("Timer saves"));
    }

    @Test
    void reRanksEveryPlanUnderTheNewLoad() {
        given().queryParam("appliance", "ev")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("Every plan, with the appliance added"))
                .body(containsString("Dear Evenings"))
                .body(containsString("Simple Flat"));
    }

    @Test
    void acceptsAMileageAndConvertsItToEnergy() {
        // A driver knows their weekly mileage; they do not know their nightly kilowatt-hours.
        given().queryParam("appliance", "ev")
                .queryParam("km", "400").queryParam("kwh100", "18").queryParam("days", "5")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("400"));
    }

    @Test
    void acceptsAnEditedWindow() {
        given().queryParam("appliance", "ev")
                .queryParam("available", "22:00").queryParam("by", "06:00")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("22:00"));
    }

    @Test
    void saysSoWhenTheLoadCannotFitTheWindow() {
        // 11 kWh at 2 kW needs five and a half hours; a two-hour window cannot deliver it.
        given().queryParam("appliance", "ev")
                .queryParam("energy", "11").queryParam("power", "2")
                .queryParam("available", "02:00").queryParam("by", "04:00")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("will not fit"))
                .body(containsString("short of what you asked for"));
    }

    @Test
    void statesTheAssumptionsItRestsOn() {
        given().when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("spread evenly across the week"))
                .body(containsString("exact"));
    }

    @Test
    void anUnknownApplianceFallsBackRatherThanFailing() {
        given().queryParam("appliance", "teleporter")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("Electric vehicle"));
    }

    @Test
    void aBadDateRangeIsStillRejected() {
        given().queryParam("from", "yesterday").queryParam("to", "2025-01-02")
                .when().get("/what-if").then()
                .statusCode(400);
    }

    @Test
    void aShapedLoadOffersNoRunTimeControls() {
        // Recommending a run time for air conditioning is advice nobody can take.
        given().queryParam("appliance", "air-conditioning")
                .when().get("/what-if").then()
                .statusCode(200)
                .body(containsString("no run time to recommend"));
    }

    @Test
    void theNavigationIncludesTheScreen() {
        given().when().get("/plans").then()
                .statusCode(200)
                .body(containsString("/what-if"));
    }
}
