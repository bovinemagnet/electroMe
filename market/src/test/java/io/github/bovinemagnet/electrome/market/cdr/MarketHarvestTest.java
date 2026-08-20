package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketHarvestTest {

    private static Plan plan(String id, String retailer, String supply, String usage) {
        List<Charge> charges = List.of(
                new DailySupply(new BigDecimal(supply)), new FlatRate(new BigDecimal(usage)));
        return new Plan(id, "Plan " + id, retailer, DistributionZone.AUSNET,
                charges, true, null, null);
    }

    @Test
    void collapsesIdenticalTariffsFromOneRetailer() {
        var plans = List.of(
                plan("A1", "Origin", "128.24", "31.98"),
                plan("A2", "Origin", "128.24", "31.98"),
                plan("A3", "Origin", "128.24", "31.98"));
        assertThat(MarketHarvest.deduplicate(plans)).hasSize(1);
    }

    @Test
    void keepsIdenticalTariffsFromDifferentRetailers() {
        // Two retailers at the same price is a real choice, not duplication.
        assertThat(MarketHarvest.deduplicate(List.of(
                        plan("A1", "Origin", "128.24", "31.98"),
                        plan("B1", "AGL", "128.24", "31.98"))))
                .hasSize(2);
    }

    @Test
    void keepsDifferentTariffsFromOneRetailer() {
        assertThat(MarketHarvest.deduplicate(List.of(
                        plan("A1", "Origin", "128.24", "31.98"),
                        plan("A2", "Origin", "110.00", "29.50"))))
                .hasSize(2);
    }

    @Test
    void preservesOrderAndKeepsTheFirstOfEachDuplicate() {
        assertThat(MarketHarvest.deduplicate(List.of(
                        plan("A1", "Origin", "128.24", "31.98"),
                        plan("B1", "AGL", "110.00", "29.50"),
                        plan("A2", "Origin", "128.24", "31.98"))))
                .extracting(Plan::id).containsExactly("A1", "B1");
    }

    @Test
    void handlesAnEmptyList() {
        assertThat(MarketHarvest.deduplicate(List.of())).isEmpty();
    }

    @Test
    void reportSummarisesWhatHappened() {
        var report = new HarvestReport(20, 40000, 400, 380, 46,
                List.of("A: no electricityContract", "B: no electricityContract",
                        "C: unrepresentable day selection: WED"),
                Duration.ofSeconds(42));
        assertThat(report.summary()).anySatisfy(l -> assertThat(l).contains("20 retailers"));
        assertThat(report.summary()).anySatisfy(l -> assertThat(l).contains("46 distinct"));
        assertThat(report.topSkipReasons(5))
                .containsExactly("2 x no electricityContract",
                        "1 x unrepresentable day selection: WED");
    }

    // ---------------------------------------------------------------------
    // Metadata read alongside the tariff — eligibility conditions, fees and
    // incentives — is collected per plan identifier while mapping, before
    // deduplication has decided which identifiers survive.
    // ---------------------------------------------------------------------

    @Test
    void keepsMetadataOnlyForThePlansThatSurvivedDeduplication() {
        var surviving = List.of(plan("A1", "Origin", "128.24", "31.98"));
        var collected = java.util.Map.of(
                "A1", List.of("Requires solar"),
                "A2", List.of("Requires a battery"));

        // A2 was collapsed into A1. Carrying its conditions forward would attach a requirement
        // to a plan that does not have one.
        assertThat(MarketHarvest.retainFor(surviving, collected))
                .containsOnlyKeys("A1");
    }

    @Test
    void dropsEmptyMetadataRatherThanKeepingAnEmptyEntry() {
        var surviving = List.of(
                plan("A1", "Origin", "128.24", "31.98"),
                plan("B1", "AGL", "110.00", "29.50"));
        var collected = java.util.Map.of(
                "A1", PlanExtras.none(),
                "B1", new PlanExtras(
                        List.of(new PlanFee("EXIT", "FIXED", new BigDecimal("22.00"), null, "")),
                        List.of()));

        // "Has no fees" and "carries an empty fee list" must not render differently.
        assertThat(MarketHarvest.retainFor(surviving, collected, PlanExtras::isEmpty))
                .containsOnlyKeys("B1");
    }
}
