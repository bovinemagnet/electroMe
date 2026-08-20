package io.github.bovinemagnet.electrome.ingest;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Reads a retailer half-hourly interval export into a {@link UsageImport}. */
public final class UsageCsvReader {

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd/MM/yyyy hh:mm:ss a")
            .toFormatter(Locale.ENGLISH);

    /**
     * Register descriptions that mean a separate controlled circuit.
     *
     * <p>Retailers label it inconsistently — "Controlled load", "Off peak", "Dedicated circuit"
     * — so the match is on wording rather than on an agreed code.
     */
    private static final java.util.regex.Pattern CONTROLLED_REGISTER =
            java.util.regex.Pattern.compile(
                    "controlled|dedicated circuit|off.?peak (?:hot ?water|circuit)|hot ?water",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "RateTypeDescription", "StartDate", "EndDate", "ProfileReadValue", "QualityFlag");

    private UsageCsvReader() {}

    public static UsageImport read(Path csv) throws IOException {
        try (var reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    public static UsageImport read(Reader source) throws IOException {
        var buffered = source instanceof BufferedReader b ? b : new BufferedReader(source);

        String headerLine = buffered.readLine();
        if (headerLine == null) {
            throw new IOException("The file is empty");
        }
        var columns = indexColumns(headerLine);

        var consumption = new ArrayList<IntervalReading>();
        var export = new ArrayList<IntervalReading>();
        var controlled = new ArrayList<IntervalReading>();
        var qualityCounts = new EnumMap<Quality, Integer>(Quality.class);
        var intervalsPerDay = new TreeMap<LocalDate, Integer>();
        var dailyTotals = new TreeMap<LocalDate, BigDecimal>();
        var seenStarts = new HashSet<LocalDateTime>();
        var duplicates = new ArrayList<LocalDateTime>();
        var rateTypes = new LinkedHashSet<String>();

        int rowsRead = 0;
        int lineNumber = 1;
        String line;
        while ((line = buffered.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            rowsRead++;
            var fields = line.split(",", -1);

            String rateType = field(fields, columns, "RateTypeDescription", lineNumber).trim();
            rateTypes.add(rateType);

            LocalDateTime start = parseTimestamp(
                    field(fields, columns, "StartDate", lineNumber), lineNumber);
            LocalDateTime rawEnd = parseTimestamp(
                    field(fields, columns, "EndDate", lineNumber), lineNumber);
            BigDecimal kWh = parseDecimal(
                    field(fields, columns, "ProfileReadValue", lineNumber), lineNumber);
            Quality quality = Quality.fromFlag(field(fields, columns, "QualityFlag", lineNumber));

            var reading = new IntervalReading(start, normaliseLength(start, rawEnd), kWh, quality);

            String register = rateType.toLowerCase(Locale.ROOT);
            if (register.contains("export")) {
                export.add(reading);
            } else if (CONTROLLED_REGISTER.matcher(register).find()) {
                // A separate circuit with its own, cheaper tariff. Counting it as ordinary
                // consumption would misprice it by the gap between two tariffs.
                controlled.add(reading);
                qualityCounts.merge(quality, 1, Integer::sum);
                continue;
            } else {
                consumption.add(reading);
                intervalsPerDay.merge(reading.date(), 1, Integer::sum);
                dailyTotals.merge(reading.date(), kWh, BigDecimal::add);
                if (!seenStarts.add(start)) {
                    duplicates.add(start);
                }
            }
            qualityCounts.merge(quality, 1, Integer::sum);
        }

        var report = new DataQualityReport(
                rowsRead,
                consumption.size() + export.size() + controlled.size(),
                qualityCounts,
                intervalsPerDay,
                daysWithCountOtherThanUsual(intervalsPerDay, true),
                daysWithCountOtherThanUsual(intervalsPerDay, false),
                duplicates,
                outlierDays(dailyTotals),
                rateTypes);

        return new UsageImport(
                new UsageData(UsageSeries.of(consumption), UsageSeries.of(export),
                        UsageSeries.of(controlled)),
                report);
    }

    /**
     * Interval length by rounding rather than subtraction.
     *
     * <p>Exports write the end one second short of the boundary, so a raw difference is 1799
     * seconds. Adding a second and rounding to the nearest minute handles both that convention
     * and an exact boundary.
     */
    private static Duration normaliseLength(LocalDateTime start, LocalDateTime rawEnd) {
        long seconds = Duration.between(start, rawEnd).toSeconds();
        long minutes = Math.round((seconds + 1) / 60.0);
        if (minutes <= 0) {
            throw new IllegalArgumentException(
                    "Interval starting " + start + " has non-positive length");
        }
        return Duration.ofMinutes(minutes);
    }

    private static Map<String, Integer> indexColumns(String headerLine) throws IOException {
        var header = headerLine.split(",", -1);
        var index = new HashMap<String, Integer>();
        for (int i = 0; i < header.length; i++) {
            index.put(header[i].trim(), i);
        }
        // Report every missing column, not just the first. Someone repairing a header wants
        // the whole list, not one failed run per column.
        var missing = new ArrayList<String>();
        for (var required : REQUIRED_COLUMNS) {
            if (!index.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Missing required columns: " + String.join(", ", missing));
        }
        return index;
    }

    private static String field(
            String[] fields, Map<String, Integer> columns, String name, int lineNumber)
            throws IOException {
        int position = columns.get(name);
        if (position >= fields.length) {
            throw new IOException("Line " + lineNumber + " has no value for " + name);
        }
        return fields[position];
    }

    private static LocalDateTime parseTimestamp(String value, int lineNumber) throws IOException {
        try {
            return LocalDateTime.parse(value.trim(), TIMESTAMP);
        } catch (DateTimeParseException e) {
            throw new IOException("Unparseable timestamp on line " + lineNumber + ": " + value, e);
        }
    }

    private static BigDecimal parseDecimal(String value, int lineNumber) throws IOException {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Unparseable number on line " + lineNumber + ": " + value, e);
        }
    }

    /** Days whose interval count differs from the most common count in the file. */
    private static List<LocalDate> daysWithCountOtherThanUsual(
            TreeMap<LocalDate, Integer> intervalsPerDay, boolean wantShort) {
        if (intervalsPerDay.size() < 2) {
            return List.of();
        }
        var frequency = new HashMap<Integer, Integer>();
        for (var count : intervalsPerDay.values()) {
            frequency.merge(count, 1, Integer::sum);
        }
        int usual = frequency.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();

        var out = new ArrayList<LocalDate>();
        for (var entry : intervalsPerDay.entrySet()) {
            boolean isShort = entry.getValue() < usual;
            boolean isLong = entry.getValue() > usual;
            if ((wantShort && isShort) || (!wantShort && isLong)) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /** Tukey fence: daily totals above the third quartile plus 1.5 interquartile ranges. */
    private static List<LocalDate> outlierDays(TreeMap<LocalDate, BigDecimal> dailyTotals) {
        if (dailyTotals.size() < 8) {
            return List.of();
        }
        var sorted = new ArrayList<>(dailyTotals.values());
        sorted.sort(BigDecimal::compareTo);
        BigDecimal q1 = sorted.get(sorted.size() / 4);
        BigDecimal q3 = sorted.get(sorted.size() * 3 / 4);
        BigDecimal fence = q3.add(q3.subtract(q1).multiply(new BigDecimal("1.5")));

        var out = new ArrayList<LocalDate>();
        for (var entry : dailyTotals.entrySet()) {
            if (entry.getValue().compareTo(fence) > 0) {
                out.add(entry.getKey());
            }
        }
        return out;
    }
}
