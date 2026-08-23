package io.github.bovinemagnet.electrome.ingest;

import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The directory of plan files, as somewhere harvested plans can be kept.
 *
 * <p>This directory belongs to the user. They write plans in it by hand, and a hand-written plan
 * is usually the one thing in the comparison the register cannot supply — the tariff actually in
 * force at the premises, transcribed from a bill. So the single rule here is that a file this
 * class wrote may be replaced and a file it did not write never is, whatever it happens to be
 * called. {@link PlanOrigin} is how the two are told apart.
 *
 * <p>Its own files are found by published plan identifier rather than by name, so a file the user
 * has renamed is still recognised as the one to update instead of being written again beside
 * itself under two names and loaded twice.
 */
public final class PlanLibrary {

    /** Long enough to stay recognisable, short enough to read in a directory listing. */
    private static final int MAX_STEM = 59;

    /** What a save did, or what it would do. */
    public enum Outcome {
        /** No file held this plan; one was written. */
        NEW,
        /** A file we wrote held this plan, and what it publishes has moved since. */
        REPLACED,
        /** A file we wrote already holds exactly this; nothing was written. */
        UNCHANGED,
        /** The natural name was taken by a file we did not write, so a free one was used. */
        RENAMED
    }

    public record Saved(Path file, Outcome outcome) {}

    private final Path directory;

    public PlanLibrary(Path directory) {
        this.directory = directory;
    }

    /** What {@link #save} would do, without touching anything. */
    public Saved preview(Plan plan) {
        return preview(plan, held());
    }

    /**
     * The same answer for a whole list, reading the directory once.
     *
     * <p>The market screen previews every harvested plan on every render — several hundred of
     * them — and asking each one separately would re-read every file in the directory each
     * time.
     */
    public Map<String, Saved> previewAll(Iterable<Plan> plans) {
        var held = held();
        var previews = new LinkedHashMap<String, Saved>();
        for (var plan : plans) {
            previews.put(plan.id(), preview(plan, held));
        }
        return Map.copyOf(previews);
    }

    private Saved preview(Plan plan, Map<String, Path> held) {
        var ours = held.get(plan.id());
        if (ours != null) {
            return new Saved(ours, unchanged(ours, plan) ? Outcome.UNCHANGED : Outcome.REPLACED);
        }
        var natural = directory.resolve(stem(plan) + ".yaml");
        return Files.exists(natural)
                ? new Saved(free(stem(plan)), Outcome.RENAMED)
                : new Saved(natural, Outcome.NEW);
    }

    public Saved save(Plan plan, PlanOrigin origin) {
        var target = preview(plan);
        if (target.outcome() == Outcome.UNCHANGED) {
            return target;
        }
        try {
            Files.createDirectories(directory);
            // preview() ran before the directory existed, so the name is settled again now
            // that a listing is possible.
            var settled = Files.exists(directory) ? preview(plan) : target;
            write(settled.file(), PlanYamlWriter.write(plan, origin));
            return settled;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot save plan " + plan.id() + " into " + directory.toAbsolutePath(), e);
        }
    }

    /**
     * Every plan this class has written into the directory, by published plan identifier.
     *
     * <p>Read from the source block alone. Parsing each file as a tariff would be slower and
     * would lose a plan whose file has since been edited into something that no longer loads —
     * exactly the file most in need of being replaced.
     */
    public Map<String, Path> held() {
        var byPlanId = new LinkedHashMap<String, Path>();
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
        try (var files = Files.list(directory)) {
            files.filter(PlanLibrary::isYaml)
                    .sorted()
                    .forEach(file -> PlanOrigin.readFrom(file)
                            .ifPresent(origin ->
                                    byPlanId.putIfAbsent(origin.cdrPlanId(), file)));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot list plan directory " + directory.toAbsolutePath(), e);
        }
        return Map.copyOf(byPlanId);
    }

    /**
     * Whether the file already says exactly what the register now publishes.
     *
     * <p>A file that will not load counts as changed: it is ours, it is broken, and rewriting it
     * is the repair.
     */
    private static boolean unchanged(Path file, Plan plan) {
        try {
            var existing = PlanYamlLoader.load(file);
            return existing.charges().equals(plan.charges())
                    && existing.name().equals(plan.name())
                    && existing.retailer().equals(plan.retailer())
                    && existing.zone() == plan.zone();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The first name of this shape nothing occupies. */
    private Path free(String stem) {
        for (int suffix = 2; suffix < 1000; suffix++) {
            var candidate = directory.resolve(stem + "-" + suffix + ".yaml");
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free file name for " + stem + " in " + directory);
    }

    /**
     * Written beside the target and moved into place, so a reader listing the directory sees
     * either the old file or the new one and never half of either.
     */
    private static void write(Path target, String yaml) throws IOException {
        var temporary = Files.createTempFile(target.getParent(), ".plan-", ".tmp");
        try {
            Files.writeString(temporary, yaml);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isYaml(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    /**
     * A file name built from the retailer and the plan, in the shape the hand-written files
     * already use.
     *
     * <p>Retailers put their own name at the front of the plan name about half the time, so a
     * leading word already carried by the retailer is dropped rather than said twice.
     */
    static String stem(Plan plan) {
        var retailer = slug(plan.retailer());
        var name = slug(plan.name());

        var retailerWords = java.util.Set.of(retailer.split("-"));
        while (!name.isEmpty()) {
            int cut = name.indexOf('-');
            var first = cut < 0 ? name : name.substring(0, cut);
            if (!retailerWords.contains(first)) {
                break;
            }
            name = cut < 0 ? "" : name.substring(cut + 1);
        }

        var stem = name.isEmpty() ? retailer : retailer + "-" + name;
        return truncate(stem.isEmpty() ? "plan" : stem);
    }

    /** Cut at a word boundary: a name severed mid-word is harder to recognise than a short one. */
    private static String truncate(String stem) {
        if (stem.length() <= MAX_STEM) {
            return stem;
        }
        var cut = stem.substring(0, MAX_STEM);
        int lastBreak = cut.lastIndexOf('-');
        return trim(lastBreak > 0 ? cut.substring(0, lastBreak) : cut);
    }

    private static String slug(String text) {
        var out = new StringBuilder(text.length());
        for (var character : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (character >= 'a' && character <= 'z' || character >= '0' && character <= '9') {
                out.append(character);
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        return trim(out.toString());
    }

    private static String trim(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '-') {
            end--;
        }
        return text.substring(0, end);
    }
}
