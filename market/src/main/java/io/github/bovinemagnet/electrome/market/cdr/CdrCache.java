package io.github.bovinemagnet.electrome.market.cdr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * On-disk JSON cache for plan responses.
 *
 * <p>The API publishes no ETag or Cache-Control header, and asking for future-effective plans
 * returns nothing, so {@code lastUpdated} from the cheap list response is the only freshness
 * signal available. Detail responses are refetched only when it changes.
 */
public final class CdrCache {

    private static final String STAMPS_FILE = "last-updated.properties";

    private final Path root;

    public CdrCache(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create cache directory " + root, e);
        }
    }

    public Optional<String> read(String key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void write(String key, String json) {
        try {
            Files.writeString(fileFor(key), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write cache entry " + key, e);
        }
    }

    public synchronized void recordLastUpdated(String key, String lastUpdated) {
        var stamps = loadStamps();
        stamps.setProperty(safeName(key), lastUpdated == null ? "" : lastUpdated);
        saveStamps(stamps);
    }

    public boolean isFresh(String key, String lastUpdated) {
        if (lastUpdated == null || read(key).isEmpty()) {
            return false;
        }
        return lastUpdated.equals(loadStamps().getProperty(safeName(key)));
    }

    public void clear() {
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot clear cache " + root, e);
        }
    }

    public int size() {
        try (var entries = Files.list(root)) {
            return (int) entries.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Path fileFor(String key) {
        return root.resolve(safeName(key) + ".json");
    }

    /**
     * Flattens a plan id into a safe file name.
     *
     * <p>Plan ids contain an @ and could in principle contain a separator. A cache key must
     * never be able to write outside the cache directory.
     */
    private static String safeName(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._@-]", "_").replace("..", "__");
    }

    private Properties loadStamps() {
        var stamps = new Properties();
        Path file = root.resolve(STAMPS_FILE);
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                stamps.load(in);
            } catch (IOException e) {
                return new Properties();
            }
        }
        return stamps;
    }

    private void saveStamps(Properties stamps) {
        try (var out = Files.newOutputStream(root.resolve(STAMPS_FILE))) {
            stamps.store(out, "CDR plan lastUpdated stamps");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write cache stamps", e);
        }
    }
}
