package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.SortedMap;

/**
 * Everything the usage-insight charts need for one window.
 *
 * @param heatmap one row per day, 48 columns of kWh
 * @param heatmapDates the dates those rows correspond to, in the same order
 */
public record UsageAnalysis(
        DateRange range,
        BigDecimal totalKWh,
        List<LoadCurve> curves,
        SortedMap<YearMonth, BigDecimal> monthlyKWh,
        List<List<BigDecimal>> heatmap,
        List<LocalDate> heatmapDates,
        SortedMap<LocalDate, BigDecimal> dailyTotals) {

    /** The overall curve, which is always the first one produced. */
    public LoadCurve overall() {
        return curves.get(0);
    }

    /** Mean daily consumption across the window. */
    public BigDecimal averageDailyKWh() {
        if (dailyTotals.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return totalKWh.divide(
                BigDecimal.valueOf(dailyTotals.size()), java.math.MathContext.DECIMAL64);
    }

    /** The heaviest day and its consumption, for the data-quality note. */
    public LocalDate heaviestDay() {
        return dailyTotals.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

    public BigDecimal medianDailyKWh() {
        if (dailyTotals.isEmpty()) {
            return BigDecimal.ZERO;
        }
        var sorted = new java.util.ArrayList<>(dailyTotals.values());
        sorted.sort(BigDecimal::compareTo);
        return sorted.get(sorted.size() / 2);
    }
}
