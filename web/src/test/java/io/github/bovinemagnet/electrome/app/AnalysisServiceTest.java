package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AnalysisServiceTest.TestData.class)
class AnalysisServiceTest {

    public static class TestData implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "electrome.usage.csv", "src/test/resources/test-usage.csv",
                    "electrome.plans.dir", "src/test/resources/test-plans");
        }
    }

    @Inject AnalysisService service;

    private static final DateRange BOTH_DAYS =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));

    @Test
    void usesAustralianSeasons() {
        assertThat(Season.of(LocalDate.of(2025, 1, 15))).isEqualTo(Season.SUMMER);
        assertThat(Season.of(LocalDate.of(2025, 12, 15))).isEqualTo(Season.SUMMER);
        assertThat(Season.of(LocalDate.of(2025, 2, 28))).isEqualTo(Season.SUMMER);
        assertThat(Season.of(LocalDate.of(2025, 3, 1))).isEqualTo(Season.AUTUMN);
        assertThat(Season.of(LocalDate.of(2025, 6, 1))).isEqualTo(Season.WINTER);
        assertThat(Season.of(LocalDate.of(2025, 8, 31))).isEqualTo(Season.WINTER);
        assertThat(Season.of(LocalDate.of(2025, 9, 1))).isEqualTo(Season.SPRING);
        assertThat(Season.of(LocalDate.of(2025, 11, 30))).isEqualTo(Season.SPRING);
    }

    @Test
    void loadCurveHasFortyEightSlots() {
        assertThat(service.analyse(BOTH_DAYS).curves())
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.averageKWByHalfHour()).hasSize(48));
    }

    @Test
    void loadCurveIsExpressedInKilowatts() {
        // Every interval is 0.5 kWh over half an hour, so the average draw is 1 kW.
        var overall = service.analyse(BOTH_DAYS).overall();
        assertThat(overall.label()).isEqualTo("Overall");
        assertThat(overall.averageKWByHalfHour())
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("1"));
        assertThat(overall.peakKW()).isEqualByComparingTo("1");
    }

    @Test
    void producesACurvePerSeasonPresentPlusWeekdaySplit() {
        var labels = service.analyse(BOTH_DAYS).curves().stream().map(LoadCurve::label).toList();
        // 1 and 2 January 2025 are a Wednesday and a Thursday, both summer.
        assertThat(labels).contains("Overall", "Summer", "Weekdays");
        assertThat(labels).doesNotContain("Winter", "Weekends");
    }

    @Test
    void totalsByMonth() {
        assertThat(service.analyse(BOTH_DAYS).monthlyKWh())
                .containsEntry(YearMonth.of(2025, 1), new BigDecimal("48.000"));
    }

    @Test
    void heatmapIsOneRowPerDayAndFortyEightColumns() {
        var analysis = service.analyse(BOTH_DAYS);
        assertThat(analysis.heatmapDates())
                .containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2));
        assertThat(analysis.heatmap()).hasSize(2);
        assertThat(analysis.heatmap()).allSatisfy(row -> assertThat(row).hasSize(48));
    }

    @Test
    void reportsDailyAndMedianTotals() {
        var analysis = service.analyse(BOTH_DAYS);
        assertThat(analysis.dailyTotals().get(LocalDate.of(2025, 1, 1)))
                .isEqualByComparingTo("24.000");
        assertThat(analysis.totalKWh()).isEqualByComparingTo("48.000");
        assertThat(analysis.averageDailyKWh()).isEqualByComparingTo("24");
        assertThat(analysis.medianDailyKWh()).isEqualByComparingTo("24.000");
    }
}
