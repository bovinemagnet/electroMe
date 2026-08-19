package io.github.bovinemagnet.electrome.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** An immutable, start-ordered collection of interval readings for a single register. */
public final class UsageSeries {

    private static final UsageSeries EMPTY = new UsageSeries(List.of());

    private final List<IntervalReading> readings;

    private UsageSeries(List<IntervalReading> readings) {
        this.readings = readings;
    }

    public static UsageSeries of(List<IntervalReading> readings) {
        Objects.requireNonNull(readings, "readings");
        if (readings.isEmpty()) {
            return EMPTY;
        }
        var sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.comparing(IntervalReading::start));
        return new UsageSeries(Collections.unmodifiableList(sorted));
    }

    public static UsageSeries empty() {
        return EMPTY;
    }

    public List<IntervalReading> readings() {
        return readings;
    }

    public boolean isEmpty() {
        return readings.isEmpty();
    }

    /** The span of calendar dates covered, or empty when there are no readings. */
    public Optional<DateRange> range() {
        if (readings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DateRange(
                readings.get(0).date(), readings.get(readings.size() - 1).date()));
    }

    public UsageSeries slice(DateRange range) {
        Objects.requireNonNull(range, "range");
        return of(readings.stream().filter(r -> range.contains(r.date())).toList());
    }

    /**
     * Distinct calendar dates present in the data.
     *
     * <p>This is the basis for daily supply charges. Deriving a day count from the interval
     * count would be wrong on daylight-saving days, which carry 46 or 50 intervals.
     */
    public SortedSet<LocalDate> billingDays() {
        var days = new TreeSet<LocalDate>();
        for (var reading : readings) {
            days.add(reading.date());
        }
        return Collections.unmodifiableSortedSet(days);
    }

    public BigDecimal totalKWh() {
        var total = BigDecimal.ZERO;
        for (var reading : readings) {
            total = total.add(reading.kWh());
        }
        return total;
    }

    public SortedMap<LocalDate, BigDecimal> dailyTotals() {
        var totals = new TreeMap<LocalDate, BigDecimal>();
        for (var reading : readings) {
            totals.merge(reading.date(), reading.kWh(), BigDecimal::add);
        }
        return Collections.unmodifiableSortedMap(totals);
    }
}
