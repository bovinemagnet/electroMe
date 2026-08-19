package io.github.bovinemagnet.electrome.ingest;

import io.github.bovinemagnet.electrome.core.domain.Quality;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

/**
 * What the importer noticed about the source data.
 *
 * <p>Surfaced to the user rather than logged. Silent data problems are how a plan comparison
 * becomes confidently wrong.
 */
public record DataQualityReport(
        int rowsRead,
        int readingsParsed,
        Map<Quality, Integer> qualityCounts,
        SortedMap<LocalDate, Integer> intervalsPerDay,
        List<LocalDate> shortDays,
        List<LocalDate> longDays,
        List<LocalDateTime> duplicateStarts,
        List<LocalDate> outlierDays,
        Set<String> rateTypes) {

    public DataQualityReport {
        qualityCounts = Map.copyOf(qualityCounts);
        shortDays = List.copyOf(shortDays);
        longDays = List.copyOf(longDays);
        duplicateStarts = List.copyOf(duplicateStarts);
        outlierDays = List.copyOf(outlierDays);
        rateTypes = Set.copyOf(rateTypes);
    }

    /** True when nothing needs explaining to the user. */
    public boolean clean() {
        return duplicateStarts.isEmpty()
                && shortDays.isEmpty()
                && longDays.isEmpty()
                && qualityCounts.getOrDefault(Quality.ESTIMATED, 0) == 0
                && qualityCounts.getOrDefault(Quality.SUBSTITUTED, 0) == 0;
    }

    /** Plain-language findings, one per line, empty when the data is clean. */
    public List<String> summary() {
        var out = new ArrayList<String>();
        int estimated = qualityCounts.getOrDefault(Quality.ESTIMATED, 0);
        int substituted = qualityCounts.getOrDefault(Quality.SUBSTITUTED, 0);
        if (estimated > 0) {
            out.add(estimated + " intervals are estimated rather than metered");
        }
        if (substituted > 0) {
            out.add(substituted + " intervals are substituted rather than metered");
        }
        if (!duplicateStarts.isEmpty()) {
            out.add(duplicateStarts.size() + " duplicate interval timestamps, first at "
                    + duplicateStarts.get(0));
        }
        if (!shortDays.isEmpty()) {
            out.add(shortDays.size() + " days carry fewer intervals than usual, including "
                    + shortDays.get(0)
                    + ". Spring-forward days legitimately carry 46 rather than 48");
        }
        if (!longDays.isEmpty()) {
            out.add(longDays.size() + " days carry more intervals than usual, including "
                    + longDays.get(0));
        }
        if (!outlierDays.isEmpty()) {
            out.add(outlierDays.size() + " days consumed unusually heavily, including "
                    + outlierDays.get(0) + ". These dominate any demand-based tariff");
        }
        if (rateTypes.size() > 1) {
            out.add("Multiple register types present: " + String.join(", ", rateTypes));
        }
        return List.copyOf(out);
    }
}
