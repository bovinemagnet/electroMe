package io.github.bovinemagnet.electrome.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The state every screen shares.
 *
 * <p>The date window lives here rather than in a session because it is shared across screens: a
 * reader who narrows to last summer on the dashboard and then opens the browser should still be
 * looking at last summer. Carrying it in the query string means a reload keeps it, a URL is a
 * complete description of what you are looking at, and the server stays stateless.
 */
class ShellTest {

    private static final DateRange AVAILABLE =
            new DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 6, 30));
    private static final DateRange WINDOW =
            new DateRange(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 2, 28));

    private static Shell shell() {
        return Shell.of("plans", WINDOW, AVAILABLE, 184, List.of());
    }

    @Test
    void carriesTheWindowInEveryLink() {
        assertThat(shell().link("/plans"))
                .isEqualTo("/plans?from=2025-12-01&to=2026-02-28");
    }

    @Test
    void appendsToALinkThatAlreadyHasParameters() {
        assertThat(shell().link("/plans?sort=NAME"))
                .isEqualTo("/plans?sort=NAME&from=2025-12-01&to=2026-02-28");
    }

    @Test
    void exposesTheWindowAloneForFragmentRequests() {
        assertThat(shell().windowQuery()).isEqualTo("from=2025-12-01&to=2026-02-28");
    }

    @Test
    void knowsWhichScreenIsShowingSoNavigationCanMarkIt() {
        assertThat(shell().active()).isEqualTo("plans");
        assertThat(shell().on("plans")).isTrue();
        assertThat(shell().on("dashboard")).isFalse();
    }

    @Test
    void offersEveryScreenThatExists() {
        // A link to a screen that has not been built yet is worse than no link at all.
        assertThat(Shell.SCREENS).extracting(Shell.Screen::path)
                .containsExactly("/", "/plans", "/rates", "/compare", "/seasons", "/what-if");
    }

    @Test
    void reportsWhetherUsageWasLoadedAtAll() {
        var missing = Shell.unloaded("Nothing at /somewhere/usage.csv");

        assertThat(missing.loaded()).isFalse();
        assertThat(missing.loadError()).isEqualTo("Nothing at /somewhere/usage.csv");
        // Still linkable: the window falls back to the available range rather than to null.
        assertThat(missing.link("/plans")).startsWith("/plans");
    }

    @Test
    void aLoadedShellHasNoError() {
        assertThat(shell().loaded()).isTrue();
        assertThat(shell().loadError()).isNull();
    }
}
