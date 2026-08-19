package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkspaceTest {

    @Test
    void findsAFileRelativeToTheWorkingDirectory() {
        assertThat(Workspace.resolveFile("src/test/resources/test-usage.csv")).exists();
    }

    @Test
    void findsAFileByClimbingOutOfTheModule() {
        // "../plans" is how the shipped configuration names the plan directory; it must
        // resolve whether the process started in the module or deep inside build output.
        assertThat(Workspace.resolveDirectory("../plans")).isDirectory();
    }

    @Test
    void leavesAbsolutePathsAlone() {
        var absolute = Path.of("/definitely/not/here.csv");
        assertThat(Workspace.resolveFile(absolute.toString())).isEqualTo(absolute);
    }

    @Test
    void returnsANamedPathWhenNothingMatches() {
        var resolved = Workspace.resolveFile("no-such-file-anywhere.csv");
        assertThat(resolved.isAbsolute()).isTrue();
        assertThat(resolved.toString()).endsWith("no-such-file-anywhere.csv");
    }
}
