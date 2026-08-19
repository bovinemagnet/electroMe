package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Derives the usage-insight datasets for a window. */
@ApplicationScoped
public class AnalysisService {

    private static final int SLOTS = 48;

    @Inject UsageStore usageStore;

    public UsageAnalysis analyse(DateRange range) {
        UsageSeries series = usageStore.usage().consumption().slice(range);

        var curves = new ArrayList<LoadCurve>();
        curves.add(curve("Overall", series, r -> true));

        var seasonsPresent = new EnumMap<Season, Boolean>(Season.class);
        for (var reading : series.readings()) {
            seasonsPresent.put(Season.of(reading.date()), Boolean.TRUE);
        }
        for (var season : Season.values()) {
            if (seasonsPresent.containsKey(season)) {
                curves.add(curve(season.label(), series, r -> Season.of(r.date()) == season));
            }
        }

        if (series.readings().stream().anyMatch(AnalysisService::isWeekday)) {
            curves.add(curve("Weekdays", series, AnalysisService::isWeekday));
        }
        if (series.readings().stream().anyMatch(r -> !isWeekday(r))) {
            curves.add(curve("Weekends", series, r -> !isWeekday(r)));
        }

        var monthly = new TreeMap<YearMonth, BigDecimal>();
        for (var reading : series.readings()) {
            monthly.merge(YearMonth.from(reading.date()), reading.kWh(), BigDecimal::add);
        }

        var byDay = new LinkedHashMap<LocalDate, BigDecimal[]>();
        for (var reading : series.readings()) {
            var row = byDay.computeIfAbsent(reading.date(), d -> zeroRow());
            int slot = reading.minuteOfDay() / 30;
            row[slot] = row[slot].add(reading.kWh());
        }
        var dates = new ArrayList<>(byDay.keySet());
        Collections.sort(dates);
        var heatmap = new ArrayList<List<BigDecimal>>();
        for (var date : dates) {
            heatmap.add(List.of(byDay.get(date)));
        }

        return new UsageAnalysis(
                range,
                series.totalKWh(),
                List.copyOf(curves),
                Collections.unmodifiableSortedMap(monthly),
                List.copyOf(heatmap),
                List.copyOf(dates),
                new TreeMap<>(series.dailyTotals()));
    }

    /** Mean power draw in each half-hour slot across every matching day. */
    private static LoadCurve curve(
            String label, UsageSeries series, Predicate<IntervalReading> include) {
        var totals = zeroRow();
        var counts = new int[SLOTS];
        for (var reading : series.readings()) {
            if (!include.test(reading)) {
                continue;
            }
            int slot = reading.minuteOfDay() / 30;
            totals[slot] = totals[slot].add(reading.averageKW());
            counts[slot]++;
        }
        var averages = new ArrayList<BigDecimal>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) {
            averages.add(counts[slot] == 0
                    ? BigDecimal.ZERO
                    : totals[slot].divide(BigDecimal.valueOf(counts[slot]), MathContext.DECIMAL64));
        }
        return new LoadCurve(label, averages);
    }

    private static boolean isWeekday(IntervalReading reading) {
        var day = reading.date().getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private static BigDecimal[] zeroRow() {
        var row = new BigDecimal[SLOTS];
        Arrays.fill(row, BigDecimal.ZERO);
        return row;
    }
}
