package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The working set every screen reads.
 *
 * <p>A tariff the household ticked out of the market and a tariff it wrote by hand sit here on
 * the same footing, because the appliance modelling and the comparison have no business caring
 * which is which. What they do care about is that a plan does not silently vanish: a plan being
 * withdrawn may be the very reason the household is looking, so it stays, marked, priced from
 * the last thing the retailer published.
 */
class ShortlistServiceTest {

    /** A cache directory holding two published plan responses and nothing else. */
    private static final String CACHE = "src/test/resources/market-cache";

    private static ShortlistService serviceOn(Path shortlistDirectory, String plansDir) {
        var picks = new Shortlist();
        picks.fileName = ".shortlist";
        picks.plansDir = shortlistDirectory.toAbsolutePath().toString();
        picks.load();

        var market = new MarketPlanSource();
        market.enabled = true;
        market.zoneName = "AUSNET";
        market.cacheDir = CACHE;

        var plans = new PlanStore();
        plans.plansDir = plansDir;
        plans.market = market;
        plans.reload();

        var service = new ShortlistService();
        service.picks = picks;
        service.planStore = plans;
        service.market = market;
        return service;
    }

    private static ShortlistService service(Path directory) {
        return serviceOn(directory, "src/test/resources/test-plans");
    }

    @Test
    void holdsTheHouseholdsOwnPlanFilesWithNothingPicked(@TempDir Path directory) {
        var shortlist = service(directory);
        assertThat(shortlist.entries()).extracting(ShortlistService.Entry::planId)
                .containsExactlyInAnyOrder("flat", "tou");
        assertThat(shortlist.entries()).allSatisfy(e -> {
            assertThat(e.local()).isTrue();
            assertThat(e.withdrawn()).isFalse();
        });
    }

    /**
     * The acceptance case: two plans ticked out of the market join the working set and are
     * costable there, alongside the household's own files.
     */
    @Test
    void picksJoinTheWorkingSetBesideTheLocalPlans(@TempDir Path directory) {
        var shortlist = service(directory);
        shortlist.picks.add(List.of("CAP001@VEC", "TOU-FIXTURE"));

        assertThat(shortlist.entries()).extracting(ShortlistService.Entry::planId)
                .contains("flat", "tou", "CAP001@VEC", "TOU-FIXTURE");
        assertThat(shortlist.plans()).hasSize(4);
        // Every one of them is a real tariff with charges to cost.
        assertThat(shortlist.plans()).allSatisfy(
                plan -> assertThat(plan.charges()).isNotEmpty());
    }

    /**
     * A plan the register has stopped listing stays, marked, rather than disappearing.
     *
     * <p>These fixtures were never harvested, so they are exactly the case: an identifier the
     * household picked that the live plan list does not contain. It resolves from the cache,
     * which is the last thing the retailer published.
     */
    @Test
    void aWithdrawnPlanStaysMarkedAndStillPrices(@TempDir Path directory) {
        var shortlist = service(directory);
        shortlist.picks.add(List.of("CAP001@VEC"));

        var entry = shortlist.entryFor("CAP001@VEC").orElseThrow();
        assertThat(entry.withdrawn()).isTrue();
        assertThat(entry.local()).isFalse();
        assertThat(entry.picked()).isTrue();
        assertThat(entry.plan().charges()).isNotEmpty();
        assertThat(shortlist.withdrawnIds()).containsExactly("CAP001@VEC");
    }

    @Test
    void anIdentifierNothingCanResolveIsReportedRatherThanIgnored(@TempDir Path directory) {
        var shortlist = service(directory);
        shortlist.picks.add(List.of("NOT-A-PLAN@VEC"));

        assertThat(shortlist.entries()).extracting(ShortlistService.Entry::planId)
                .doesNotContain("NOT-A-PLAN@VEC");
        assertThat(shortlist.unresolved()).containsExactly("NOT-A-PLAN@VEC");
    }

    /** Picking a plan that is already a file changes nothing: it was always on the list. */
    @Test
    void pickingALocalPlanDoesNotDuplicateIt(@TempDir Path directory) {
        var shortlist = service(directory);
        shortlist.picks.add(List.of("flat"));

        assertThat(shortlist.entries()).extracting(ShortlistService.Entry::planId)
                .containsExactlyInAnyOrder("flat", "tou");
        assertThat(shortlist.entryFor("flat").orElseThrow().picked()).isTrue();
    }
}
