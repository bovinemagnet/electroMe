package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What each plan charges, as opposed to what it would cost this household.
 *
 * <p>Two different questions. A tariff's rates are a fact about the tariff; its total is a fact
 * about the consumption it was priced against. A reader deciding whether to move a pool pump
 * into the middle of the day is asking the first.
 */
@QuarkusTest
@TestProfile(RatesResourceTest.TenPlans.class)
class RatesResourceTest {

    public static class TenPlans implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/ranking-plans",
                    "electrome.baseline.plan", "mine",
                    "electrome.market.enabled", "false");
        }
    }

    private static final String WINDOW = "?from=2025-01-01&to=2025-01-02";

    @Test
    void showsAColumnPerChargeAnyPlanMakes() {
        given().when().get("/rates" + WINDOW + "&limit=all").then()
                .statusCode(200)
                .body(containsString("Daily supply"))
                .body(containsString("Flat usage rate"))
                .body(containsString("Evening peak"))
                // The fixture's flat plans charge 100c/day and 20c to 34c.
                .body(containsString("100.00c"))
                .body(containsString("20.00c"));
    }

    /** A plan with no such charge shows an absence, which is information rather than a gap. */
    @Test
    void marksAChargeAPlanDoesNotMake() {
        given().when().get("/rates" + WINDOW + "&limit=all").then()
                .statusCode(200)
                .body(containsString("class=\"absent\""));
    }

    @Test
    void appearsInTheNavigation() {
        given().when().get("/" + WINDOW).then()
                .statusCode(200)
                .body(containsString("/rates"))
                .body(containsString("Rates"));
    }

    @Test
    void sortsByAPublishedRateRatherThanByCost() {
        given().when().get("/rates" + WINDOW + "&limit=all&sort=PEAK_RATE").then()
                .statusCode(200)
                .body(containsString("Time of Use Plan"));
    }

    // ---------- the export ----------

    @Test
    void offersTheSameTableAsAFile() {
        var response = given().when().get("/rates.csv" + WINDOW).then()
                .statusCode(200)
                .header("Content-Disposition", containsString("electrome-rates-2025-01-01"))
                .extract();

        assertThat(response.contentType()).startsWith("text/csv");

        var csv = response.asString().strip().lines().toList();
        assertThat(csv.get(0)).startsWith(
                "plan_id,plan_name,retailer,shape,total_dollars,average_cents_per_kwh");
        assertThat(csv.get(0)).contains("Daily supply").contains("Evening peak beyond cap");
        // Ten plans, one header.
        assertThat(csv).hasSize(11);
        assertThat(csv).anySatisfy(line -> assertThat(line).startsWith("mine,My current plan,"));
    }

    /**
     * A charge the plan does not make is an empty cell.
     *
     * <p>Writing a zero would say the plan charges nothing for the evening, which is the
     * opposite of the truth: it has no separate evening rate at all.
     */
    @Test
    void leavesAnAbsentRateEmptyRatherThanZero() {
        var csv = given().when().get("/rates.csv" + WINDOW).then()
                .statusCode(200).extract().asString();

        var flatRow = csv.lines().filter(line -> line.startsWith("cheapest-open,"))
                .findFirst().orElseThrow();
        assertThat(flatRow).contains(",,");
        assertThat(flatRow).doesNotContain(",0,");
    }

    @Test
    void theExportIgnoresThePageLimit() {
        // A file has no reason to be paginated: the reader asked for the data.
        var csv = given().when().get("/rates.csv" + WINDOW + "&limit=10").then()
                .statusCode(200).extract().asString();
        assertThat(csv.strip().lines().count()).isEqualTo(11);
    }

    @Test
    void theExportHonoursTheCriteria() {
        var csv = given().when().get("/rates.csv" + WINDOW + "&search=Middling").then()
                .statusCode(200).extract().asString();
        assertThat(csv.strip().lines().count()).isEqualTo(5);
    }
}
