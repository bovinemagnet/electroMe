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
@TestProfile(HomeResourceTest.TestData.class)
class HomeResourceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    @Test
    void servesThePageShell() {
        given().when().get("/").then()
                .statusCode(200)
                .body(containsString("electroMe"))
                .body(containsString("vendor/htmx.min.js"))
                .body(containsString("vendor/echarts.min.js"));
    }

    @Test
    void referencesNoExternalHosts() {
        given().when().get("/").then()
                .statusCode(200)
                .body(not(containsString("//unpkg.com")))
                .body(not(containsString("//cdn.jsdelivr.net")))
                .body(not(containsString("fonts.googleapis.com")))
                .body(not(containsString("fonts.gstatic.com")));
    }

    @Test
    void showsTheAvailableDateRangeInTheControls() {
        given().when().get("/").then()
                .statusCode(200)
                .body(containsString("2025-01-01"))
                .body(containsString("2025-01-02"));
    }

    @Test
    void servesTheVendoredAssetsItReferences() {
        given().when().get("/vendor/htmx.min.js").then().statusCode(200);
        given().when().get("/vendor/echarts.min.js").then().statusCode(200);
        given().when().get("/charts.js").then().statusCode(200);
        given().when().get("/app.css").then().statusCode(200);
        given().when().get("/fonts/plex-sans.woff2").then().statusCode(200);
        given().when().get("/fonts/plex-mono-400.woff2").then().statusCode(200);
    }

    @Test
    void stylesheetCarriesNoExternalFontImport() {
        given().when().get("/app.css").then()
                .statusCode(200)
                .body(not(containsString("fonts.googleapis.com")))
                .body(containsString("/fonts/plex-sans.woff2"));
    }
}
