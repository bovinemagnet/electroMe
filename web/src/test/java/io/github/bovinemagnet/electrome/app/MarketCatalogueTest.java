package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.ingest.PlanLibrary;
import io.github.bovinemagnet.electrome.market.cdr.PlanExtras;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeping a published plan, and everything that has to be true afterwards.
 *
 * <p>The whole point of the screen is the last step: a plan that has been written but not
 * re-read is invisible on the dashboard, the plan browser and the rates table — which is
 * exactly where the reader has just been told to go and look.
 */
class MarketCatalogueTest {

    @TempDir Path plansDir;

    /** The two days the interval fixture covers. */
    private static final DateRange RANGE =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    private static Plan published(String id, String name, String retailer, Charge... charges) {
        return new Plan(id, name, retailer, DistributionZone.AUSNET, List.of(charges), true,
                null, null);
    }

    private static Plan globird(String supplyCents) {
        return published("GLO785330MR@VEC", "GloBird BOOST Residential", "GloBird Energy",
                new DailySupply(new BigDecimal(supplyCents)),
                new FlatRate(new BigDecimal("31.98")));
    }

    private static Plan ovo() {
        return published("OVO123@VEC", "The One Plan", "OVO Energy",
                new DailySupply(new BigDecimal("106.40")),
                new FlatRate(new BigDecimal("29.50")));
    }

    /** A catalogue with no usage: the screen still lists what plans charge, uncosted. */
    private MarketCatalogue catalogueOffering(List<Plan> harvested) {
        return catalogue(harvested, false);
    }

    /** A catalogue that can price plans against a real interval series. */
    private MarketCatalogue costedCatalogueOffering(List<Plan> harvested) {
        return catalogue(harvested, true);
    }

    /** Extras the fake harvest serves, so a plan can be marked market-linked. */
    private final java.util.Map<String, PlanExtras> extrasByPlanId = new java.util.HashMap<>();

    private void markMarketLinked(String planId) {
        extrasByPlanId.put(planId, new PlanExtras(List.of(), List.of(), true,
                "Prices are not fixed. All usage is charged at wholesale electricity costs."));
    }

    private MarketCatalogue catalogue(List<Plan> harvested, boolean withUsage) {
        var market = new MarketPlanSource() {
            @Override
            public List<Plan> plans() {
                return harvested;
            }

            @Override
            public java.util.Map<String, PlanExtras> extras() {
                return extrasByPlanId;
            }
        };
        market.enabled = true;
        market.zoneName = "AUSNET";

        var store = new PlanStore();
        store.plansDir = plansDir.toAbsolutePath().toString();
        store.market = market;
        store.reload();

        var usage = new UsageStore();
        usage.csvPath = withUsage ? "src/test/resources/test-usage.csv" : "no-such-file.csv";
        usage.defaultWindowDays = 365;
        usage.load();

        var comparisons = new ComparisonService();
        comparisons.usageStore = usage;
        comparisons.planStore = store;
        comparisons.baselinePlanId = Optional.ofNullable(withUsage ? BASELINE_ID : null);

        var catalogue = new MarketCatalogue();
        catalogue.plansDir = plansDir.toAbsolutePath().toString();
        catalogue.market = market;
        catalogue.plans = store;
        catalogue.usage = usage;
        catalogue.comparisons = comparisons;
        return catalogue;
    }

    private static final String BASELINE_ID = "my-current-tariff";

    /** A hand-written baseline: no source block, so nothing may ever overwrite it. */
    private void writeBaseline(String flatCents) throws IOException {
        Files.writeString(plansDir.resolve("my-current-tariff.yaml"), """
                id: my-current-tariff
                name: What I pay now
                retailer: AGL
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: dailySupply
                    cents: 110.00
                  - type: flatRate
                    cents: %s
                """.formatted(flatCents));
    }

    private static MarketQuery all() {
        return MarketQuery.of(null, null, null, "SUPPLY_CHARGE", null, "all");
    }

    @Test
    void listsEveryPublishedPlanAsNotYetHeld() {
        var page = catalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.heldCount()).isZero();
        assertThat(page.entries()).extracting(MarketEntry::statusLabel)
                .containsOnly("Not held");
        assertThat(page.entries()).extracting(MarketEntry::fileName)
                .contains("globird-energy-boost-residential.yaml");
    }

    @Test
    void writesTheChosenPlansAndNothingElse() {
        var catalogue = catalogueOffering(List.of(globird("106.70"), ovo()));

        var report = catalogue.save(List.of("GLO785330MR@VEC"));

        assertThat(report.count()).isEqualTo(1);
        assertThat(report.written().get(0).outcome()).isEqualTo(PlanLibrary.Outcome.NEW);
        assertThat(listing()).containsExactly("globird-energy-boost-residential.yaml");
    }

    /** Saving is only useful if the rest of the application then sees the file. */
    @Test
    void makesASavedPlanAvailableToEveryOtherScreen() {
        var catalogue = catalogueOffering(List.of(globird("106.70"), ovo()));
        assertThat(catalogue.plans.localPlans()).isEmpty();

        catalogue.save(List.of("GLO785330MR@VEC"));

        assertThat(catalogue.plans.localPlans()).extracting(Plan::id)
                .containsExactly("GLO785330MR@VEC");
    }

    @Test
    void reportsAPlanAsHeldOnceItIsSaved() {
        var catalogue = catalogueOffering(List.of(globird("106.70"), ovo()));
        catalogue.save(List.of("GLO785330MR@VEC"));

        var entry = entryFor(catalogue.page(RANGE, all()), "GLO785330MR@VEC");

        assertThat(entry.held()).isTrue();
        assertThat(entry.stale()).isFalse();
        assertThat(entry.statusLabel()).isEqualTo("Held");
    }

    /**
     * The reason the screen has a status column at all: a saved plan quietly going out of date
     * is invisible everywhere else in the application.
     */
    @Test
    void reportsAHeldPlanAsMovedWhenTheRegisterNowPublishesSomethingElse() {
        var catalogue = catalogueOffering(List.of(globird("106.70")));
        catalogue.save(List.of("GLO785330MR@VEC"));

        var reprice = catalogueOffering(List.of(globird("112.40")));
        var entry = entryFor(reprice.page(RANGE, all()), "GLO785330MR@VEC");

        assertThat(entry.held()).isTrue();
        assertThat(entry.stale()).isTrue();
        assertThat(entry.statusLabel()).isEqualTo("Rates moved");
        assertThat(reprice.page(RANGE, all()).staleCount()).isEqualTo(1);
    }

    @Test
    void savingAgainUpdatesTheFileInPlace() {
        catalogueOffering(List.of(globird("106.70"))).save(List.of("GLO785330MR@VEC"));

        var report = catalogueOffering(List.of(globird("112.40")))
                .save(List.of("GLO785330MR@VEC"));

        assertThat(report.written().get(0).outcome()).isEqualTo(PlanLibrary.Outcome.REPLACED);
        assertThat(listing()).containsExactly("globird-energy-boost-residential.yaml");
    }

    @Test
    void hidesTheHeldPlansWhenAskedTo() {
        var catalogue = catalogueOffering(List.of(globird("106.70"), ovo()));
        catalogue.save(List.of("GLO785330MR@VEC"));

        var page = catalogue.page(RANGE, MarketQuery.of(null, null, null, null, "true", "all"));

        assertThat(page.entries()).extracting(MarketEntry::id).containsExactly("OVO123@VEC");
        assertThat(page.heldCount()).isEqualTo(1);
    }

    /** A plan identifier that is not on offer is dropped, not looked up somewhere else. */
    @Test
    void ignoresAnIdentifierTheRegisterIsNotOffering() {
        var report = catalogueOffering(List.of(globird("106.70")))
                .save(List.of("NOT-A-PLAN@VEC"));

        assertThat(report.written()).isEmpty();
        assertThat(listing()).isEmpty();
    }

    @Test
    void savingNothingWritesNothing() {
        var report = catalogueOffering(List.of(globird("106.70"))).save(List.of());

        assertThat(report.anything()).isFalse();
        assertThat(listing()).isEmpty();
    }

    @Test
    void narrowsToOneRetailer() {
        var catalogue = catalogueOffering(List.of(globird("106.70"), ovo()));

        var page = catalogue.page(RANGE, MarketQuery.of(null, "OVO Energy", null, null, null, "all"));

        assertThat(page.entries()).extracting(MarketEntry::id).containsExactly("OVO123@VEC");
        assertThat(page.retailers()).containsExactly("GloBird Energy", "OVO Energy");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void searchesNameAndRetailerAlike() {
        var catalogue = catalogueOffering(List.of(globird("106.70"), ovo()));

        assertThat(catalogue.page(RANGE, MarketQuery.of("boost", null, null, null, null, "all")).entries())
                .extracting(MarketEntry::id).containsExactly("GLO785330MR@VEC");
        assertThat(catalogue.page(RANGE, MarketQuery.of("ovo", null, null, null, null, "all")).entries())
                .extracting(MarketEntry::id).containsExactly("OVO123@VEC");
    }

    @Test
    void ordersByTheCheapestDailySupplyFirst() {
        var page = catalogueOffering(List.of(ovo(), globird("96.70"))).page(RANGE, all());

        assertThat(page.entries()).extracting(MarketEntry::id)
                .containsExactly("GLO785330MR@VEC", "OVO123@VEC");
    }

    // ---------- what a plan is worth ----------

    /** With no interval data there is nothing to price against, and the screen says so. */
    @Test
    void listsRatesWithoutPricingThemWhenThereIsNoUsage() {
        var page = catalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());

        assertThat(page.costed()).isFalse();
        assertThat(page.hasScale()).isFalse();
        assertThat(page.entries()).allSatisfy(entry -> {
            assertThat(entry.costed()).isFalse();
            assertThat(entry.total()).isNull();
        });
    }

    @Test
    void pricesEveryPublishedPlanAgainstTheHouseholdsOwnUsage() throws IOException {
        writeBaseline("30.00");

        var page = costedCatalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());

        assertThat(page.costed()).isTrue();
        assertThat(page.entries()).allSatisfy(entry ->
                assertThat(entry.total()).isNotNull().isGreaterThan(BigDecimal.ZERO));
    }

    /**
     * The anchor is the household's own plan, so a plan dearer than it reads as dearer even
     * when it is the cheapest thing on the page.
     */
    @Test
    void placesEachPlanOnEitherSideOfTheHouseholdsOwnTariff() throws IOException {
        writeBaseline("20.00");

        var page = costedCatalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());

        assertThat(page.hasScale()).isTrue();
        assertThat(page.scale().anchorName()).isEqualTo("What I pay now");
        // Both published plans charge more per kilowatt hour than the 20c baseline.
        assertThat(page.entries()).allSatisfy(entry -> {
            assertThat(entry.cheaperThanBaseline()).isFalse();
            assertThat(page.valueDirection(entry)).isEqualTo("dearer");
        });
    }

    @Test
    void marksAPlanCheaperThanTheHouseholdsOwnAsCheaper() throws IOException {
        writeBaseline("60.00");

        var page = costedCatalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());

        assertThat(page.entries()).allSatisfy(entry -> {
            assertThat(entry.cheaperThanBaseline()).isTrue();
            assertThat(page.valueDirection(entry)).isEqualTo("cheaper");
            assertThat(entry.saving()).isGreaterThan(BigDecimal.ZERO);
        });
    }

    /** The widest gap on the page fills its half of the track; nothing exceeds it. */
    @Test
    void drawsEveryBarWithinTheScale() throws IOException {
        writeBaseline("30.00");

        var page = costedCatalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());

        assertThat(page.entries()).allSatisfy(entry -> {
            assertThat(page.valueBar(entry)).isBetween(0, 100);
            assertThat(page.valueStep(entry)).isBetween(0, 4);
        });
        assertThat(page.entries()).anySatisfy(entry ->
                assertThat(page.valueBar(entry)).isEqualTo(100));
    }

    /**
     * The screen prices what the register publishes, not the copy on disk.
     *
     * <p>Otherwise a plan shown as "Rates moved" would be priced from the stale file beside the
     * warning saying it was stale.
     */
    @Test
    void pricesThePublishedRatesRatherThanAStaleSavedCopy() throws IOException {
        writeBaseline("30.00");
        costedCatalogueOffering(List.of(globird("106.70"))).save(List.of("GLO785330MR@VEC"));

        var dearer = globird("306.70");
        var page = costedCatalogueOffering(List.of(dearer)).page(RANGE, all());
        var entry = entryFor(page, "GLO785330MR@VEC");

        assertThat(entry.stale()).isTrue();
        var asPublished = costedCatalogueOffering(List.of(dearer))
                .comparisons.compareAsPublished(RANGE, List.of(dearer))
                .results().stream()
                .filter(r -> r.bill().plan().id().equals("GLO785330MR@VEC"))
                .findFirst().orElseThrow();
        assertThat(entry.total()).isEqualByComparingTo(asPublished.total());
    }

    // ---------- how the rates rank within their column ----------

    @Test
    void shadesTheCheapestRateInAColumnLightestAndTheDearestDarkest() {
        var page = catalogueOffering(List.of(globird("96.70"), ovo())).page(RANGE, all());

        var cheapest = entryFor(page, "GLO785330MR@VEC");
        var dearest = entryFor(page, "OVO123@VEC");

        assertThat(page.heat().stepFor(cheapest, PlanMatrix.Component.DAILY_SUPPLY)).isEqualTo(1);
        assertThat(page.heat().stepFor(dearest, PlanMatrix.Component.DAILY_SUPPLY)).isEqualTo(4);
        assertThat(page.heatClass(cheapest, PlanMatrix.Component.DAILY_SUPPLY))
                .isEqualTo("heat-1");
    }

    /** A column only one plan prices has no ranking to show, so it is left unshaded. */
    @Test
    void leavesAColumnUnshadedWhenOnlyOnePlanChargesIt() {
        var page = catalogueOffering(List.of(globird("106.70"))).page(RANGE, all());

        assertThat(page.heatClass(page.entries().get(0), PlanMatrix.Component.DAILY_SUPPLY))
                .isEmpty();
    }

    // ---------- plans sold as exposure to the wholesale market ----------

    /**
     * Left out by default.
     *
     * <p>They publish a unit price because the register has nowhere to put "it depends", so they
     * cost and rank exactly like a real tariff while the total means nothing. A fiction sorting
     * near the top of a ranking is worse than an absence.
     */
    @Test
    void leavesMarketLinkedPlansOutUnlessTheyAreAskedFor() {
        markMarketLinked("OVO123@VEC");
        var page = catalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());

        assertThat(page.entries()).extracting(MarketEntry::id)
                .containsExactly("GLO785330MR@VEC");
        assertThat(page.marketLinkedHidden()).isEqualTo(1);
        assertThat(page.anyMarketLinkedHidden()).isTrue();
        assertThat(page.anyMarketLinkedShown()).isFalse();
    }

    @Test
    void letsThemInWhenTheTickBoxIsTicked() {
        markMarketLinked("OVO123@VEC");
        var catalogue = catalogueOffering(List.of(globird("106.70"), ovo()));

        var page = catalogue.page(RANGE,
                MarketQuery.of(null, null, null, "SUPPLY_CHARGE", null, "true", "all"));

        assertThat(page.entries()).extracting(MarketEntry::id)
                .containsExactlyInAnyOrder("GLO785330MR@VEC", "OVO123@VEC");
        assertThat(page.marketLinkedHidden()).isZero();
        assertThat(page.anyMarketLinkedShown()).isTrue();
        assertThat(entryFor(page, "OVO123@VEC").marketLinked()).isTrue();
        assertThat(entryFor(page, "OVO123@VEC").priceVariation()).contains("wholesale");
    }

    /** An ordinary plan is never caught by the filter. */
    @Test
    void keepsOrdinaryPlansWhicheverWayTheTickBoxIsSet() {
        var hidden = catalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());
        var shown = catalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE,
                MarketQuery.of(null, null, null, "SUPPLY_CHARGE", null, "true", "all"));

        assertThat(hidden.entries()).hasSize(2);
        assertThat(shown.entries()).hasSize(2);
        assertThat(hidden.marketLinkedHidden()).isZero();
    }

    /** Hidden from the ranking, but still counted as harvested: the total must not shrink. */
    @Test
    void stillCountsAHiddenPlanAmongEverythingRead() {
        markMarketLinked("OVO123@VEC");
        var page = catalogueOffering(List.of(globird("106.70"), ovo())).page(RANGE, all());

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.matched()).isEqualTo(1);
    }

    private static MarketEntry entryFor(MarketPage page, String id) {
        return page.entries().stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entry for " + id));
    }

    private List<String> listing() {
        try (var files = Files.list(plansDir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
