package io.github.bovinemagnet.electrome.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageSeriesTest {

    private static IntervalReading at(LocalDateTime start, String kWh) {
        return new IntervalReading(start, Duration.ofMinutes(30), new BigDecimal(kWh), Quality.ACTUAL);
    }

    /** A full day of half-hourly readings, each of the given size. */
    private static List<IntervalReading> fullDay(LocalDate date, String eachKWh) {
        var out = new ArrayList<IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            out.add(at(date.atStartOfDay().plusMinutes(minute), eachKWh));
        }
        return out;
    }

    @Test
    void emptySeriesHasNoRange() {
        assertThat(UsageSeries.empty().isEmpty()).isTrue();
        assertThat(UsageSeries.empty().range()).isEmpty();
        assertThat(UsageSeries.empty().totalKWh()).isEqualByComparingTo("0");
    }

    @Test
    void sortsReadingsByStart() {
        var later = at(LocalDateTime.of(2025, 8, 19, 12, 0), "1");
        var earlier = at(LocalDateTime.of(2025, 8, 19, 6, 0), "2");
        var series = UsageSeries.of(List.of(later, earlier));
        assertThat(series.readings()).containsExactly(earlier, later);
    }

    @Test
    void sumsTotalEnergyExactly() {
        var series = UsageSeries.of(List.of(
                at(LocalDateTime.of(2025, 8, 19, 0, 0), "0.424"),
                at(LocalDateTime.of(2025, 8, 19, 0, 30), "0.871"),
                at(LocalDateTime.of(2025, 8, 19, 1, 0), "0.701")));
        assertThat(series.totalKWh()).isEqualByComparingTo("1.996");
    }

    @Test
    void rangeSpansFirstAndLastReadingDates() {
        var series = UsageSeries.of(List.of(
                at(LocalDateTime.of(2025, 8, 19, 0, 0), "1"),
                at(LocalDateTime.of(2026, 8, 18, 23, 30), "1")));
        assertThat(series.range()).contains(
                new DateRange(LocalDate.of(2025, 8, 19), LocalDate.of(2026, 8, 18)));
    }

    @Test
    void sliceKeepsOnlyReadingsInsideRange() {
        var readings = new ArrayList<IntervalReading>();
        readings.addAll(fullDay(LocalDate.of(2025, 1, 1), "1"));
        readings.addAll(fullDay(LocalDate.of(2025, 1, 2), "1"));
        readings.addAll(fullDay(LocalDate.of(2025, 1, 3), "1"));
        var sliced = UsageSeries.of(readings)
                .slice(new DateRange(LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 2)));
        assertThat(sliced.readings()).hasSize(48);
        assertThat(sliced.billingDays()).containsExactly(LocalDate.of(2025, 1, 2));
    }

    @Test
    void billingDaysCountsDistinctDatesNotIntervals() {
        // A spring-forward day carries 46 intervals; it is still one billing day.
        var readings = new ArrayList<IntervalReading>();
        var springForward = LocalDate.of(2025, 10, 5);
        for (int minute = 0; minute < 1440; minute += 30) {
            if (minute >= 120 && minute < 180) {
                continue; // 02:00-03:00 never occurred
            }
            readings.add(at(springForward.atStartOfDay().plusMinutes(minute), "1"));
        }
        var series = UsageSeries.of(readings);
        assertThat(series.readings()).hasSize(46);
        assertThat(series.billingDays()).hasSize(1);
    }

    @Test
    void dailyTotalsAreGroupedByDate() {
        var readings = new ArrayList<IntervalReading>();
        readings.addAll(fullDay(LocalDate.of(2025, 1, 1), "0.5"));
        readings.addAll(fullDay(LocalDate.of(2025, 1, 2), "0.25"));
        var totals = UsageSeries.of(readings).dailyTotals();
        assertThat(totals.get(LocalDate.of(2025, 1, 1))).isEqualByComparingTo("24.0");
        assertThat(totals.get(LocalDate.of(2025, 1, 2))).isEqualByComparingTo("12.0");
    }

    @Test
    void consumptionOnlyHasEmptyExport() {
        var data = UsageData.consumptionOnly(
                UsageSeries.of(List.of(at(LocalDateTime.of(2025, 1, 1, 0, 0), "1"))));
        assertThat(data.export().isEmpty()).isTrue();
    }
}
