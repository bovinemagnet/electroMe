package io.github.bovinemagnet.electrome.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing harvested plans into a directory the user also edits by hand.
 *
 * <p>The rule the whole class exists to keep: a file electroMe wrote may be replaced, and a file
 * a person wrote never is. Getting that wrong destroys work that cannot be recovered from the
 * register, because the whole reason to write a plan by hand is that it says something the
 * register does not.
 */
class PlanLibraryTest {

    @TempDir Path plans;

    private static final PlanOrigin ORIGIN =
            new PlanOrigin("GLO785330MR@VEC", LocalDate.of(2026, 8, 21));

    private static Plan globird(String supplyCents) {
        return plan("GLO785330MR@VEC", "GloBird BOOST Residential", "GloBird Energy",
                new DailySupply(new BigDecimal(supplyCents)),
                new FlatRate(new BigDecimal("31.98")));
    }

    private static Plan plan(String id, String name, String retailer, Charge... charges) {
        return new Plan(id, name, retailer, DistributionZone.AUSNET, List.of(charges), true,
                null, null);
    }

    @Test
    void writesANewPlanUnderANameDrawnFromTheRetailerAndThePlan() {
        var saved = new PlanLibrary(plans).save(globird("106.70"), ORIGIN);

        assertThat(saved.outcome()).isEqualTo(PlanLibrary.Outcome.NEW);
        assertThat(saved.file().getFileName().toString())
                .isEqualTo("globird-energy-boost-residential.yaml");
        assertThat(PlanYamlLoader.load(saved.file()).id()).isEqualTo("GLO785330MR@VEC");
    }

    /** Saving the same plan twice updates the file it already wrote rather than adding one. */
    @Test
    void replacesItsOwnFileWhenThePublishedRatesHaveMoved() {
        var library = new PlanLibrary(plans);
        library.save(globird("106.70"), ORIGIN);

        var again = library.save(globird("112.40"), ORIGIN);

        assertThat(again.outcome()).isEqualTo(PlanLibrary.Outcome.REPLACED);
        assertThat(listing()).containsExactly("globird-energy-boost-residential.yaml");
        assertThat(PlanYamlLoader.load(again.file()).charges())
                .contains(new DailySupply(new BigDecimal("112.40")));
    }

    @Test
    void leavesItsOwnFileAloneWhenNothingHasMoved() {
        var library = new PlanLibrary(plans);
        library.save(globird("106.70"), ORIGIN);

        assertThat(library.save(globird("106.70"), ORIGIN).outcome())
                .isEqualTo(PlanLibrary.Outcome.UNCHANGED);
    }

    /** A file that has been renamed by hand is still ours, and is still the one to update. */
    @Test
    void findsItsOwnFileByPublishedPlanIdRatherThanByName() throws IOException {
        var library = new PlanLibrary(plans);
        var first = library.save(globird("106.70"), ORIGIN);
        var renamed = plans.resolve("my-favourite.yaml");
        Files.move(first.file(), renamed);

        var again = library.save(globird("112.40"), ORIGIN);

        assertThat(again.file()).isEqualTo(renamed);
        assertThat(again.outcome()).isEqualTo(PlanLibrary.Outcome.REPLACED);
        assertThat(listing()).containsExactly("my-favourite.yaml");
    }

    /**
     * The rule that makes this safe to point at a directory somebody edits.
     *
     * <p>A hand-written file carries no source block, so it is never the target of a save even
     * when its name is exactly the one a harvested plan would choose.
     */
    @Test
    void neverOverwritesAFileItDidNotWrite() throws IOException {
        var handWritten = plans.resolve("globird-energy-boost-residential.yaml");
        Files.writeString(handWritten, """
                id: my-own-globird
                name: What GloBird quoted me on the phone
                retailer: GloBird Energy
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: flatRate
                    cents: 28.00
                """);

        var saved = new PlanLibrary(plans).save(globird("106.70"), ORIGIN);

        assertThat(saved.outcome()).isEqualTo(PlanLibrary.Outcome.RENAMED);
        assertThat(saved.file()).isNotEqualTo(handWritten);
        assertThat(PlanYamlLoader.load(handWritten).id()).isEqualTo("my-own-globird");
        assertThat(listing()).containsExactlyInAnyOrder(
                "globird-energy-boost-residential.yaml",
                "globird-energy-boost-residential-2.yaml");
    }

    /** What would happen, without doing it: the screen asks before it writes. */
    @Test
    void previewsAnOutcomeWithoutTouchingTheDirectory() {
        var library = new PlanLibrary(plans);

        assertThat(library.preview(globird("106.70")).outcome())
                .isEqualTo(PlanLibrary.Outcome.NEW);
        assertThat(listing()).isEmpty();
    }

    @Test
    void previewsAReplacementOnceThePlanIsHeld() {
        var library = new PlanLibrary(plans);
        library.save(globird("106.70"), ORIGIN);

        assertThat(library.preview(globird("112.40")).outcome())
                .isEqualTo(PlanLibrary.Outcome.REPLACED);
        assertThat(library.preview(globird("106.70")).outcome())
                .isEqualTo(PlanLibrary.Outcome.UNCHANGED);
    }

    /** A directory that is not there yet is made, not reported as a failure. */
    @Test
    void createsTheDirectoryOnFirstSave() {
        var fresh = plans.resolve("not-yet");

        var saved = new PlanLibrary(fresh).save(globird("106.70"), ORIGIN);

        assertThat(saved.file()).exists();
        assertThat(saved.file().getParent()).isEqualTo(fresh);
    }

    /** Published names run to eighty characters and carry punctuation that is not a filename. */
    @Test
    void makesAUsableFilenameFromAnAwkwardPublishedName() {
        var awkward = plan("ORG123@VEC",
                "Origin Go Variable - New and Moving Customers Only August'26 (Time of Use)",
                "Origin Energy", new FlatRate(new BigDecimal("31.98")));

        var name = new PlanLibrary(plans).save(awkward, ORIGIN).file().getFileName().toString();

        assertThat(name).endsWith(".yaml").doesNotContain(" ").doesNotContain("'")
                .doesNotContain("(").matches("[a-z0-9-]+\\.yaml");
        assertThat(name.length()).isLessThanOrEqualTo(64);
    }

    /** Two saves must not race a half-written file into the directory the loader reads. */
    @Test
    void leavesNoPartialFileBehind() {
        new PlanLibrary(plans).save(globird("106.70"), ORIGIN);

        assertThat(listing()).allMatch(n -> n.endsWith(".yaml"));
    }

    private List<String> listing() {
        try (var files = Files.list(plans)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
