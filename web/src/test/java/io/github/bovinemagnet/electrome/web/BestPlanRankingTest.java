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
 * The ranking screen, through the HTTP layer.
 *
 * <p>Ten plans over two days of fixture consumption. The cheapest, at $10.44, is only that
 * cheap if its discount is earned; the cheapest with nothing attached is $11.60; the
 * household's own plan is $16.40. Whether the page says all three of those things is the
 * whole test.
 */
@QuarkusTest
@TestProfile(BestPlanRankingTest.TenPlans.class)
class BestPlanRankingTest {

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
    void ranksEveryPlanAndNamesTheCheapest() {
        given().when().get("/plans" + WINDOW + "&limit=all").then()
                .statusCode(200)
                .body(containsString("The answer"))
                .body(containsString("Cheapest of all 10 plans costed"))
                .body(containsString("Discounted Deal"))
                .body(containsString("$10.44"));
    }

    /** The verdict states the saving against the household's own tariff, not against the field. */
    @Test
    void statesWhatSwitchingIsWorthAgainstTheCurrentPlan() {
        given().when().get("/plans" + WINDOW + "&limit=all").then()
                .statusCode(200)
                // $16.40 on the current plan, $10.44 on the winner.
                .body(containsString("$5.96"))
                .body(containsString("less than your current plan"));
    }

    /**
     * The winner's total is conditional, and the sentence says so in the same breath rather
     * than in a footnote below the table.
     */
    @Test
    void admitsInTheVerdictThatTheWinningTotalMustBeEarned() {
        given().when().get("/plans" + WINDOW + "&limit=all").then()
                .statusCode(200)
                .body(containsString("That total holds only if you"))
                .body(containsString("pay every bill by its due date"));
    }

    @Test
    void marksTheConditionalRowInTheTableToo() {
        given().when().get("/plans" + WINDOW + "&limit=all").then()
                .statusCode(200)
                .body(containsString("class=\"conditional-total\""));
    }

    @Test
    void canRankOnlyTotalsThatDoNotHaveToBeEarned() {
        given().when().get("/plans" + WINDOW + "&limit=all&unconditional=true").then()
                .statusCode(200)
                .body(not(containsString("/fragments/browser/discounted?")))
                .body(containsString("Cheapest Open"));
    }

    @Test
    void canHidePlansThatDoNotSaveEnoughToBother() {
        // Savings against $16.40: the winner $5.96, Cheapest Open $4.80, the rest less. At a
        // $5 threshold only the winner clears it.
        given().when().get("/plans" + WINDOW + "&limit=all&minSaving=5").then()
                .statusCode(200)
                .body(containsString("/fragments/browser/discounted?"))
                .body(not(containsString("/fragments/browser/cheapest-open?")))
                .body(not(containsString("/fragments/browser/mid-b?")))
                // The counts line is what tells the reader rows were left out.
                .body(containsString("Showing 1 of 1 matching"))
                .body(containsString("from 10 costed"));

        // And a threshold it does clear keeps it.
        given().when().get("/plans" + WINDOW + "&limit=all&minSaving=4").then()
                .statusCode(200)
                .body(containsString("/fragments/browser/cheapest-open?"));
    }

    @Test
    void offersTheHouseholdCapabilityControls() {
        given().when().get("/plans" + WINDOW).then()
                .statusCode(200)
                .body(containsString("name=\"have\""))
                .body(containsString("value=\"ELECTRIC_VEHICLE\""))
                // Every requirement the classifier can recognise is answerable, or a household
                // holding one could never see the plans that ask for it.
                .body(containsString("value=\"CONCESSION\""))
                .body(containsString("value=\"MEMBERSHIP\""))
                .body(containsString("I have"));
    }

    /**
     * A criterion narrows the table, never the answer.
     *
     * <p>A verdict that moved as you browsed would be worth nothing, so it is drawn from every
     * plan costed and the page says so when the two could disagree.
     */
    @Test
    void keepsTheVerdictWhenTheTableIsNarrowed() {
        given().when().get("/plans" + WINDOW + "&search=Middling").then()
                .statusCode(200)
                .body(containsString("Discounted Deal"))
                .body(containsString("drawn from every plan costed"));
    }
}
