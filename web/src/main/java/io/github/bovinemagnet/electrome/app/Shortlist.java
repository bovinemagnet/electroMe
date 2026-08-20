package io.github.bovinemagnet.electrome.app;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The plans the household has picked out of the market.
 *
 * <p>A harvest returns several hundred tariffs and the reader is interested in four of them.
 * Ticking those turns a browsing exercise into a working set: what the dashboard compares, what
 * the appliance modelling schedules against, what a scenario is priced on.
 *
 * <p>Kept on disk beside the plan files rather than in a session, for the same reason the date
 * window travels in the query string: a selection that vanished when the application restarted
 * would have to be rebuilt from a three-hundred-row table every time.
 *
 * <p>Only identifiers are stored. The tariff behind one comes from the harvest, or failing that
 * from the harvest cache — writing the rates here would be writing a plan file, and a plan file
 * is something the household edits deliberately.
 */
@ApplicationScoped
public class Shortlist {

    /**
     * A dotfile in the plans directory.
     *
     * <p>That directory is the household's own, always exists, and resolves correctly from
     * every working directory this application runs in. The name begins with a dot and does not
     * end in {@code .yaml}, so the plan loader passes over it.
     */
    @ConfigProperty(name = "electrome.shortlist.name", defaultValue = ".shortlist")
    String fileName;

    @ConfigProperty(name = "electrome.plans.dir")
    String plansDir;

    private volatile Set<String> picked = Set.of();

    @PostConstruct
    void load() {
        var file = file();
        if (!Files.isRegularFile(file)) {
            picked = Set.of();
            return;
        }
        try {
            var ids = new LinkedHashSet<String>();
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    ids.add(trimmed);
                }
            }
            picked = ordered(ids);
        } catch (IOException e) {
            // An unreadable selection is an empty selection, never a failed boot: the rest of
            // the application is perfectly usable without one.
            picked = Set.of();
        }
    }

    /** The identifiers picked, in the order they were picked. */
    public Set<String> picked() {
        return picked;
    }

    public boolean contains(String planId) {
        return picked.contains(planId);
    }

    public boolean isEmpty() {
        return picked.isEmpty();
    }

    public int size() {
        return picked.size();
    }

    /** Adds every identifier given, keeping any already there. */
    public void add(Collection<String> planIds) {
        var updated = new LinkedHashSet<>(picked);
        for (var id : planIds) {
            if (id != null && !id.isBlank()) {
                updated.add(id.trim());
            }
        }
        replace(updated);
    }

    public void remove(String planId) {
        var updated = new LinkedHashSet<>(picked);
        updated.remove(planId);
        replace(updated);
    }

    public void clear() {
        replace(new LinkedHashSet<>());
    }

    // -----------------------------------------------------------------

    private void replace(LinkedHashSet<String> updated) {
        picked = ordered(updated);
        write(new ArrayList<>(updated));
    }

    /** Insertion order kept: the file reads as the household built it. */
    private static Set<String> ordered(LinkedHashSet<String> ids) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    private void write(List<String> ids) {
        var lines = new ArrayList<String>();
        lines.add("# Plans picked from the market, one identifier per line.");
        lines.add("# Written by electroMe; edit or delete freely.");
        lines.addAll(ids);
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The selection still holds for this run. Losing it on restart is a smaller harm
            // than refusing the tick that the reader just made.
            picked = ordered(new LinkedHashSet<>(ids));
        }
    }

    private Path file() {
        Path name = Path.of(fileName);
        return name.isAbsolute() ? name : Workspace.resolveDirectory(plansDir).resolve(name);
    }
}
