package io.github.bovinemagnet.electrome.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Resolves configured file locations against the project, not the working directory.
 *
 * <p>The working directory is not stable across the ways this application runs: tests start in
 * the module directory, {@code quarkusDev} starts in {@code build/classes/java/main}, and a
 * packaged jar starts wherever the user launched it. A relative path in configuration
 * therefore resolves differently each time, which presents as "no data found" rather than as a
 * path problem.
 *
 * <p>So a relative path is tried against the working directory first and then against each
 * ancestor in turn, which finds the file from anywhere inside the project tree.
 */
public final class Workspace {

    /** Deep enough to climb out of build/classes/java/main, with room to spare. */
    private static final int MAX_ASCENT = 8;

    private Workspace() {}

    public static Path resolveFile(String configured) {
        return resolve(configured, Files::isRegularFile);
    }

    public static Path resolveDirectory(String configured) {
        return resolve(configured, Files::isDirectory);
    }

    private static Path resolve(String configured, Predicate<Path> acceptable) {
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path;
        }

        Path base = Path.of("").toAbsolutePath();
        for (int i = 0; i <= MAX_ASCENT && base != null; i++) {
            Path candidate = base.resolve(path).normalize();
            if (acceptable.test(candidate)) {
                return candidate;
            }
            base = base.getParent();
        }
        // Nothing matched; hand back the naive resolution so the error names a real path.
        return Path.of("").toAbsolutePath().resolve(path).normalize();
    }
}
