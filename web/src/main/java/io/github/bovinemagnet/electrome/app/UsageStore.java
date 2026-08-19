package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.ingest.DataQualityReport;
import io.github.bovinemagnet.electrome.ingest.UsageCsvReader;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Holds the household interval export.
 *
 * <p>Loaded once at startup. A missing or malformed file is recorded rather than thrown: a
 * user who has not yet placed their export should see an explanation, not a failed boot.
 */
@ApplicationScoped
public class UsageStore {

    @ConfigProperty(name = "electrome.usage.csv")
    String csvPath;

    @ConfigProperty(name = "electrome.default-window-days", defaultValue = "365")
    int defaultWindowDays;

    private UsageData usage = UsageData.consumptionOnly(UsageSeries.empty());
    private DataQualityReport report = emptyReport();
    private String loadError;

    @PostConstruct
    void load() {
        Path path = Path.of(csvPath);
        if (!Files.exists(path)) {
            loadError = "No interval data found at " + path.toAbsolutePath()
                    + ". Set electrome.usage.csv to point at your export.";
            return;
        }
        try {
            var imported = UsageCsvReader.read(path);
            usage = imported.usage();
            report = imported.report();
            loadError = null;
        } catch (Exception e) {
            loadError = "Could not read " + path.toAbsolutePath() + ": " + e.getMessage();
        }
    }

    public boolean loaded() {
        return loadError == null && !usage.consumption().isEmpty();
    }

    public String loadError() {
        return loadError;
    }

    public UsageData usage() {
        return usage;
    }

    public DataQualityReport report() {
        return report;
    }

    /** The full span present in the data. */
    public DateRange available() {
        return usage.consumption()
                .range()
                .orElseGet(() -> new DateRange(LocalDate.now(), LocalDate.now()));
    }

    /** The most recent whole window, clamped to what the data actually covers. */
    public DateRange defaultWindow() {
        var available = available();
        var from = available.to().minusDays(defaultWindowDays - 1L);
        return new DateRange(
                from.isBefore(available.from()) ? available.from() : from, available.to());
    }

    private static DataQualityReport emptyReport() {
        return new DataQualityReport(0, 0, Map.of(), new TreeMap<>(), List.of(), List.of(),
                List.of(), List.of(), Set.of());
    }
}
