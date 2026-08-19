package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CdrClientTest {

    private static String fixture() throws IOException {
        return Files.readString(
                Path.of("src/test/resources/plans-page.json"), StandardCharsets.UTF_8);
    }

    @Test
    void parsesPlanSummaries() throws IOException {
        var plans = CdrClient.parseList(fixture());
        assertThat(plans).isNotEmpty();
        assertThat(plans).allSatisfy(p -> assertThat(p.planId()).isNotBlank());
        assertThat(plans).allSatisfy(p -> assertThat(p.brand()).isNotBlank());
    }

    @Test
    void readsPaginationMetadata() throws IOException {
        assertThat(CdrClient.totalPages(fixture())).isGreaterThanOrEqualTo(1);
        assertThat(CdrClient.totalRecords(fixture())).isGreaterThan(0);
    }

    @Test
    void theListResponseCarriesTheDistributorsNeededToFilter() throws IOException {
        // There is no geography query parameter, so filtering has to happen client-side. It
        // costs nothing only because the cheap list response already carries this.
        assertThat(CdrClient.parseList(fixture()))
                .anySatisfy(p -> assertThat(p.distributors()).isNotEmpty());
    }

    @Test
    void matchesTheExactAusNetDistributorString() {
        var plan = new PlanSummary("OR1@VEC", "Plan", "Origin", "MARKET", "ELECTRICITY",
                "RESIDENTIAL", List.of("AusNet Services (electricity)"), "2026-08-01");
        assertThat(plan.servesZone(DistributionZone.AUSNET)).isTrue();
        assertThat(plan.servesZone(DistributionZone.POWERCOR)).isFalse();
    }

    @Test
    void doesNotMatchAusNetOnAShortenedName() {
        // The published string is "AusNet Services (electricity)". A substring match on
        // "AusNet" would also catch the gas network.
        var plan = new PlanSummary("X", "Plan", "B", "MARKET", "ELECTRICITY", "RESIDENTIAL",
                List.of("AusNet Services (gas)"), "2026-08-01");
        assertThat(plan.servesZone(DistributionZone.AUSNET)).isFalse();
    }

    @Test
    void identifiesResidentialElectricityPlans() {
        assertThat(summary("ELECTRICITY", "RESIDENTIAL").isResidentialElectricity()).isTrue();
        assertThat(summary("ELECTRICITY", "BUSINESS").isResidentialElectricity()).isFalse();
        assertThat(summary("GAS", "RESIDENTIAL").isResidentialElectricity()).isFalse();
    }

    @Test
    void identifiesVictorianPlansByTheirIdentifierSuffix() {
        assertThat(new PlanSummary("OR2786724MR@VEC", "P", "B", "MARKET", "ELECTRICITY",
                "RESIDENTIAL", List.of(), "x").isVictorian()).isTrue();
        assertThat(new PlanSummary("OR123", "P", "B", "MARKET", "ELECTRICITY",
                "RESIDENTIAL", List.of(), "x").isVictorian()).isFalse();
    }

    @Test
    void handlesAMissingGeographyBlock() {
        var json = """
                {"data": {"plans": [{"planId": "X", "displayName": "P", "brandName": "B",
                  "type": "MARKET", "fuelType": "ELECTRICITY", "customerType": "RESIDENTIAL"}]},
                 "meta": {"totalRecords": 1, "totalPages": 1}}
                """;
        var plans = CdrClient.parseList(json);
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).distributors()).isEmpty();
        assertThat(plans.get(0).servesZone(DistributionZone.AUSNET)).isFalse();
    }

    @Test
    void listAndDetailUseDifferentApiVersions() {
        // A guard against the easiest mistake here: the list is x-v 1, the detail x-v 3.
        assertThat(CdrClient.LIST_VERSION).isEqualTo("1");
        assertThat(CdrClient.DETAIL_VERSION).isEqualTo("3");
    }

    @Test
    void pageSizeIsCappedAtTheApiMaximum() {
        assertThat(CdrClient.MAX_PAGE_SIZE).isEqualTo(1000);
    }

    private static PlanSummary summary(String fuel, String customer) {
        return new PlanSummary("A", "P", "B", "MARKET", fuel, customer, List.of(), "x");
    }
}
