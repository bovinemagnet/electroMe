package io.github.bovinemagnet.electrome.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * No screen ships more than 100 KB of HTML.
 *
 * <p>This is the defect the phase exists to fix. Rendering every harvested plan as a table row
 * produced roughly half a megabyte per request and ten thousand pixels of scrolling: it worked,
 * and it was unusable.
 */
@QuarkusTest
@TestProfile(PlanBrowserSizeTest.ManyPlans.class)
class PlanBrowserSizeTest {

    private static final int PLANS = 184;
    private static final int BUDGET_BYTES = 100 * 1024;

    /**
     * Writes a harvest-sized plan directory before the application starts.
     *
     * <p>Generated rather than committed: 184 near-identical YAML files would be noise in the
     * repository, and the only property that matters here is how many there are.
     */
    public static class ManyPlans implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Path directory = Path.of("build/test-plans-many");
            try {
                Files.createDirectories(directory);
                for (int i = 0; i < PLANS; i++) {
                    Files.writeString(
                            directory.resolve(String.format("plan-%03d.yaml", i)),
                            planYaml(i), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write the generated plan fixtures", e);
            }
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", directory.toString());
        }

        private static String planYaml(int index) {
            return """
                    id: generated-%03d
                    name: Generated Plan %03d With A Realistically Long Retail Name
                    retailer: Retailer %02d
                    zone: AUSNET
                    gstInclusive: true
                    charges:
                      - type: dailySupply
                        cents: %d.20
                      - type: timeOfUse
                        bands:
                          - { from: "00:00", to: "16:00", days: ALL, cents: %d.99 }
                          - { from: "16:00", to: "21:00", days: ALL, cents: %d.54 }
                          - { from: "21:00", to: "24:00", days: ALL, cents: %d.77 }
                    """.formatted(index, index, index % 12,
                    90 + index % 40, 15 + index % 10, 40 + index % 15, 20 + index % 8);
        }
    }

    private static int sizeOf(String path) {
        return given().when().get(path).then().statusCode(200)
                .extract().asByteArray().length;
    }

    @Test
    void theFixtureIsHarvestSized() {
        // Without this the budget assertions below would pass on an empty directory.
        given().when().get("/plans").then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString(Integer.toString(PLANS)));
    }

    @Test
    void theBrowserScreenStaysWithinBudget() {
        assertThat(sizeOf("/plans")).isLessThan(BUDGET_BYTES);
    }

    @Test
    void theDashboardStaysWithinBudget() {
        assertThat(sizeOf("/")).isLessThan(BUDGET_BYTES);
    }

    @Test
    void theDashboardComparisonPanelStaysWithinBudget() {
        // The panel that used to render every plan, and the reason for the shortlist.
        //
        // Measured without the chart payloads. They are data rather than markup, they do not
        // grow with the number of plans, and the full-resolution heatmap is a deliberate
        // earlier decision this phase has no business quietly reversing. Asserting on the raw
        // response would pass here only because the fixture holds two days, and would say
        // nothing about the growth this phase exists to bound.
        assertThat(markupBytes("/fragments/dashboard")).isLessThan(BUDGET_BYTES);
    }

    /** The response with every {@code data-chart} payload removed. */
    private static int markupBytes(String path) {
        String html = given().when().get(path).then().statusCode(200).extract().asString();
        return html.replaceAll("data-chart=\"[^\"]*\"", "data-chart=\"\"")
                .getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void aPlanDetailScreenStaysWithinBudget() {
        assertThat(sizeOf("/plans/generated-000")).isLessThan(BUDGET_BYTES);
    }

    @Test
    void showingEveryPlanIsStillBoundedByTheLimitControl() {
        // "Show all" is a deliberate act, and is allowed to be larger — but the default is not.
        int bounded = sizeOf("/fragments/browser");
        int everything = sizeOf("/fragments/browser?limit=all");

        assertThat(bounded).isLessThan(BUDGET_BYTES);
        assertThat(everything).isGreaterThan(bounded);
    }
}
