package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import java.time.LocalDate;
import java.util.List;

/**
 * The state every screen shares: the masthead, the navigation and the date window.
 *
 * <p>The window belongs here because it is shared across screens. A reader who narrows to last
 * summer on the dashboard and then opens the plan browser should still be looking at last summer.
 *
 * <p>It travels in the query string rather than in a session for three reasons: it survives a
 * reload, it makes a URL a complete description of what you are looking at, and it keeps the
 * server stateless, which the rest of the site already assumes.
 *
 * @param active which screen is showing, so navigation can mark it
 * @param loadError why usage data is missing, or null when it loaded
 */
public record Shell(
        String active,
        DateRange window,
        DateRange available,
        int planCount,
        List<String> planErrors,
        boolean loaded,
        String loadError) {

    /** One entry in the navigation. */
    public record Screen(String path, String label, String key) {}

    /** Every screen the application has. */
    public static final List<Screen> SCREENS = List.of(
            new Screen("/", "Dashboard", "dashboard"),
            new Screen("/plans", "Plans", "plans"),
            new Screen("/market", "Market", "market"),
            new Screen("/rates", "Rates", "rates"),
            new Screen("/compare", "Compare", "compare"),
            new Screen("/seasons", "Seasons", "seasons"),
            new Screen("/what-if", "What if", "what-if"));

    public Shell {
        planErrors = planErrors == null ? List.of() : List.copyOf(planErrors);
    }

    public static Shell of(String active, DateRange window, DateRange available, int planCount,
            List<String> planErrors) {
        return new Shell(active, window, available, planCount, planErrors, true, null);
    }

    /** No usage data, which is a page to render rather than a boot failure. */
    public static Shell unloaded(String loadError) {
        var today = LocalDate.now();
        var empty = new DateRange(today, today);
        return new Shell("dashboard", empty, empty, 0, List.of(), false, loadError);
    }

    public boolean on(String key) {
        return key.equals(active);
    }

    public List<Screen> screens() {
        return SCREENS;
    }

    /** The window alone, for a fragment request that carries no other criteria. */
    public String windowQuery() {
        return "from=" + window.from() + "&to=" + window.to();
    }

    /** A path with the window attached, which is what every navigation link needs. */
    public String link(String path) {
        return path + (path.contains("?") ? "&" : "?") + windowQuery();
    }
}
