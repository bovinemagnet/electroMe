package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.ingest.PlanLibrary;
import io.github.bovinemagnet.electrome.ingest.PlanOrigin;
import io.github.bovinemagnet.electrome.ingest.PlanYamlLoader;
import io.github.bovinemagnet.electrome.market.cdr.CdrPlanMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A published plan, saved and read back, against responses the register actually returned.
 *
 * <p>The synthetic round-trip tests use rates a person would type. These do not: published unit
 * prices are grossed up by 1.1 as they are mapped, which turns a tidy 0.239 into a figure with a
 * long tail of decimal places. A writer that rounded, or that let YAML read a rate back as a
 * float, would still produce a file that loaded and a bill that looked plausible — and every
 * saved plan would report itself as having moved the moment it was written.
 */
class HarvestedPlanRoundTripTest {

    private static final Path CACHE = Path.of("src/test/resources/market-cache");

    @TempDir Path plans;

    private static Plan mapped(String fixture) throws IOException {
        return CdrPlanMapper.map(
                Files.readString(CACHE.resolve(fixture)), DistributionZone.AUSNET);
    }

    private Plan savedAndReloaded(Plan plan) {
        var saved = new PlanLibrary(plans)
                .save(plan, new PlanOrigin(plan.id(), LocalDate.of(2026, 8, 21)));
        return PlanYamlLoader.load(saved.file());
    }

    /** A capped window: the shape most likely to lose a tier on the way to disk. */
    @Test
    void keepsACappedPublishedPlanExactly() throws IOException {
        var published = mapped("cap001@vec.json");

        var reloaded = savedAndReloaded(published);

        assertThat(reloaded.charges()).isEqualTo(published.charges());
        assertThat(reloaded.id()).isEqualTo(published.id());
        assertThat(reloaded.name()).isEqualTo(published.name());
        assertThat(reloaded.retailer()).isEqualTo(published.retailer());
        assertThat(reloaded.zone()).isEqualTo(published.zone());
    }

    @Test
    void keepsAPublishedTimeOfUsePlanExactly() throws IOException {
        var published = mapped("tou-fixture.json");

        assertThat(savedAndReloaded(published).charges()).isEqualTo(published.charges());
    }

    /**
     * The property the status column rests on: saving a plan and immediately looking again must
     * report it as held, not as having moved.
     */
    @Test
    void reportsAJustSavedPublishedPlanAsUnchanged() throws IOException {
        var published = mapped("cap001@vec.json");
        var library = new PlanLibrary(plans);
        library.save(published, PlanOrigin.readToday(published.id()));

        assertThat(library.preview(published).outcome())
                .isEqualTo(PlanLibrary.Outcome.UNCHANGED);
    }

    /** Both fixtures at once, so neither can be quietly dropped from the check. */
    @Test
    void handlesEveryCachedFixture() throws IOException {
        for (var fixture : List.of("cap001@vec.json", "tou-fixture.json")) {
            var published = mapped(fixture);
            assertThat(savedAndReloaded(published).charges())
                    .as("charges of %s", fixture)
                    .isEqualTo(published.charges());
        }
    }
}
