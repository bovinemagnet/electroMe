package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The date window survives navigation.
 *
 * <p>This is the regression most likely to appear and least likely to be noticed: a reader
 * narrows to a window, moves to another screen, and silently starts reading a different period
 * without being told. Every navigation link on every screen therefore has to carry the window.
 */
@QuarkusTest
@TestProfile(NavigationTest.TestData.class)
class NavigationTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    /** The fixture holds two days, so a window narrower than the data is still meaningful. */
    private static final String FROM = "2025-01-02";
    private static final String TO = "2025-01-02";

    private static final Pattern NAV_LINK =
            Pattern.compile("<a[^>]*class=\"[^\"]*nav-link[^\"]*\"[^>]*href=\"([^\"]*)\"");

    private static String screen(String path) {
        return given().queryParam("from", FROM).queryParam("to", TO)
                .when().get(path).then().statusCode(200).extract().asString();
    }

    private static void everyNavigationLinkCarriesTheWindow(String html) {
        Matcher matcher = NAV_LINK.matcher(html);
        int found = 0;
        while (matcher.find()) {
            found++;
            assertThat(matcher.group(1))
                    .as("navigation link %s", matcher.group(1))
                    .contains("from=" + FROM)
                    .contains("to=" + TO);
        }
        // A pattern that matches nothing would pass every assertion above it.
        assertThat(found).as("navigation links found").isGreaterThanOrEqualTo(2);
    }

    @Test
    void theDashboardCarriesTheWindowOnEveryNavigationLink() {
        everyNavigationLinkCarriesTheWindow(screen("/"));
    }

    @Test
    void theBrowserCarriesTheWindowOnEveryNavigationLink() {
        everyNavigationLinkCarriesTheWindow(screen("/plans"));
    }

    @Test
    void aPlanDetailScreenCarriesTheWindowOnEveryNavigationLink() {
        everyNavigationLinkCarriesTheWindow(screen("/plans/tou"));
    }

    @Test
    void theSeasonalScreenCarriesTheWindowOnEveryNavigationLink() {
        everyNavigationLinkCarriesTheWindow(screen("/seasons"));
    }

    @Test
    void theWindowControlIsPrefilledWithTheWindowInForce() {
        // Reflecting the window back into the control is what makes it survive the next change.
        assertThat(screen("/plans")).contains("value=\"" + FROM + "\"");
    }

    @Test
    void theDashboardsLinkToTheBrowserCarriesTheWindow() {
        // "Compare all N plans" is the main route into the browser and the likeliest place to
        // drop the window.
        assertThat(screen("/")).contains("/plans?from=" + FROM + "&amp;to=" + TO);
    }

    @Test
    void aScreenOpenedWithNoWindowFallsBackToTheDefaultRatherThanFailing() {
        given().when().get("/plans").then().statusCode(200);
        given().when().get("/").then().statusCode(200);
        given().when().get("/seasons").then().statusCode(200);
    }

    @Test
    void criteriaAndTheWindowCoexistInOneUrl() {
        String html = given()
                .queryParam("from", FROM).queryParam("to", TO)
                .queryParam("search", "flat")
                .when().get("/plans").then().statusCode(200).extract().asString();

        everyNavigationLinkCarriesTheWindow(html);
        assertThat(html).contains("flat");
    }
}
