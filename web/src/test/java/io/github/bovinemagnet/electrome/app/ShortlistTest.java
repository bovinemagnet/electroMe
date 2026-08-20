package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A selection that survives a restart.
 *
 * <p>Rebuilding a shortlist from a three-hundred-row table every time the application starts
 * is not a selection, it is a chore. It lives beside the plan files for the same reason the
 * date window lives in the query string: state the reader created should outlive the request
 * that created it.
 */
class ShortlistTest {

    private static Shortlist backedBy(Path directory) {
        var shortlist = new Shortlist();
        shortlist.fileName = ".shortlist";
        shortlist.plansDir = directory.toAbsolutePath().toString();
        shortlist.load();
        return shortlist;
    }

    @Test
    void startsEmptyWhenNothingWasEverPicked(@TempDir Path directory) {
        var shortlist = backedBy(directory);
        assertThat(shortlist.isEmpty()).isTrue();
        assertThat(shortlist.picked()).isEmpty();
    }

    @Test
    void remembersPicksAcrossARestart(@TempDir Path directory) {
        var before = backedBy(directory);
        before.add(List.of("GLO785381MR@VEC", "agl-flat-current"));

        // A second instance is what a restart looks like from here.
        var after = backedBy(directory);
        assertThat(after.picked()).containsExactly("GLO785381MR@VEC", "agl-flat-current");
        assertThat(after.contains("GLO785381MR@VEC")).isTrue();
        assertThat(after.size()).isEqualTo(2);
    }

    @Test
    void keepsThePickedOrder(@TempDir Path directory) {
        var shortlist = backedBy(directory);
        shortlist.add(List.of("c"));
        shortlist.add(List.of("a"));
        shortlist.add(List.of("b"));
        assertThat(shortlist.picked()).containsExactly("c", "a", "b");
        assertThat(backedBy(directory).picked()).containsExactly("c", "a", "b");
    }

    @Test
    void pickingTheSamePlanTwiceChangesNothing(@TempDir Path directory) {
        var shortlist = backedBy(directory);
        shortlist.add(List.of("a", "b"));
        shortlist.add(List.of("a"));
        assertThat(shortlist.picked()).containsExactly("a", "b");
    }

    @Test
    void dropsWhatTheReaderRemoves(@TempDir Path directory) {
        var shortlist = backedBy(directory);
        shortlist.add(List.of("a", "b", "c"));
        shortlist.remove("b");
        assertThat(shortlist.picked()).containsExactly("a", "c");
        assertThat(backedBy(directory).picked()).containsExactly("a", "c");

        shortlist.clear();
        assertThat(backedBy(directory).isEmpty()).isTrue();
    }

    @Test
    void ignoresBlanksAndCommentsInTheFile(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve(".shortlist"),
                "# written by hand\n\n  a  \n\nb\n", StandardCharsets.UTF_8);
        assertThat(backedBy(directory).picked()).containsExactly("a", "b");
    }

    /** The file is the household's to delete, and deleting it must not break the page. */
    @Test
    void anUnreadableSelectionIsAnEmptySelectionRatherThanAFailure(@TempDir Path directory)
            throws IOException {
        Files.createDirectory(directory.resolve(".shortlist"));
        assertThat(backedBy(directory).isEmpty()).isTrue();
    }
}
