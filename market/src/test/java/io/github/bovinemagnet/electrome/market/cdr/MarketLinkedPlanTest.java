package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Telling a published rate apart from an illustrative one.
 *
 * <p>A handful of retailers sell exposure to the wholesale market rather than a rate. They still
 * publish a unit price, because the register has nowhere to put "it depends" — but that price is
 * explicitly illustrative, and costing a household's year against it produces a total that reads
 * exactly like every other total on the page and means nothing.
 *
 * <p>The register carries no flag for this. What it carries is the retailer saying so in
 * {@code variation}, which is where this looks. Deliberately narrow: matching on the phrase
 * "pass through" as well would catch a dozen ordinary plans whose fee schedule passes network
 * charges on, which is not the same thing at all.
 */
class MarketLinkedPlanTest {

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources", name));
    }

    @Test
    void marksAPlanWhoseRatesFollowTheWholesaleMarket() throws IOException {
        var extras = CdrPlanMapper.extrasOf(fixture("plan-detail-market-linked.json"));

        assertThat(extras.marketLinked()).isTrue();
        assertThat(extras.priceVariation()).contains("not fixed").contains("wholesale");
    }

    @Test
    void leavesAnOrdinaryPublishedTariffAlone() throws IOException {
        assertThat(CdrPlanMapper.extrasOf(fixture("plan-detail-tou.json")).marketLinked())
                .isFalse();
        assertThat(CdrPlanMapper.extrasOf(fixture("plan-detail-standing.json")).marketLinked())
                .isFalse();
    }

    /**
     * An annual price review is not market exposure.
     *
     * <p>Almost every plan in the register says its prices may change. If that counted, the
     * filter would hide the whole market.
     */
    @Test
    void doesNotMistakeAnOrdinaryPriceChangeNoticeForMarketExposure() {
        assertThat(CdrPlanMapper.marketLinked(
                "Prices are subject to change on 1 August each year with at least 5 business"
                        + " days prior notice.")).isFalse();
        assertThat(CdrPlanMapper.marketLinked(
                "Network charges are passed through to you at cost.")).isFalse();
    }

    @Test
    void recognisesTheWordingBothRetailersActuallyPublish() {
        // Amber Electric.
        assertThat(CdrPlanMapper.marketLinked(
                "Prices are not fixed. All usage is charged at the same wholesale electricity"
                        + " costs that are charged to us.")).isTrue();
        // Flow Power.
        assertThat(CdrPlanMapper.marketLinked(
                "Usage rates aren’t fixed. Rate = 43.36c/kWh base + monthly PEA based on"
                        + " usage timing vs 5-min wholesale prices.")).isTrue();
        // Spot exposure said another way.
        assertThat(CdrPlanMapper.marketLinked(
                "Your usage is billed at the half-hourly spot price.")).isTrue();
    }

    @Test
    void treatsNothingStatedAsAnOrdinaryPlan() {
        assertThat(CdrPlanMapper.marketLinked(null)).isFalse();
        assertThat(CdrPlanMapper.marketLinked("")).isFalse();
    }

    /**
     * Extras are dropped when they hold nothing worth showing, so the flag has to count as
     * something worth showing — otherwise a market-linked plan with no fees loses its marker on
     * the way out of the harvest.
     */
    @Test
    void countsAsSomethingWorthKeeping() {
        var flagOnly = new PlanExtras(java.util.List.of(), java.util.List.of(), true,
                "Prices follow the wholesale market.");

        assertThat(flagOnly.isEmpty()).isFalse();
        assertThat(PlanExtras.none().isEmpty()).isTrue();
    }
}
