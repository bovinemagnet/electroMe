package io.github.bovinemagnet.electrome.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.ChargeKind;
import io.github.bovinemagnet.electrome.core.cost.ChargeLine;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class GoldenBillTest {

    /**
     * The tests own their tariff fixtures.
     *
     * <p>They used to read the application's live {@code plans/} directory, which made the
     * suite depend on a directory the user is meant to edit: adding a third plan broke a test
     * that asserted there were two.
     */
    private static final Path PLANS = Path.of("src/test/resources/plans");
    private static final CostingEngine ENGINE = new CostingEngine();

    private static Path householdData() {
        return Path.of(System.getProperty("electrome.usage.csv", "../MyUsageData_19-08-2026.csv"));
    }

    private static BigDecimal lineKWh(BillBreakdown bill, String label) {
        return bill.lines().stream()
                .filter(l -> l.label().equals(label))
                .map(ChargeLine::quantity)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No line labelled " + label));
    }

    private static BigDecimal lineCost(BillBreakdown bill, String label) {
        return bill.lines().stream()
                .filter(l -> l.label().equals(label))
                .map(ChargeLine::cost)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No line labelled " + label));
    }

    @Test
    void syntheticWeekCostsExactly() throws IOException {
        var plan = PlanYamlLoader.load(PLANS.resolve("reference-tou.yaml"));
        UsageImport imported;
        try (var reader = new InputStreamReader(
                GoldenBillTest.class.getResourceAsStream("/golden-week.csv"),
                StandardCharsets.UTF_8)) {
            imported = UsageCsvReader.read(reader);
        }

        var week = new DateRange(LocalDate.of(2025, 1, 6), LocalDate.of(2025, 1, 12));
        var bill = ENGINE.cost(imported.usage(), plan, week);

        assertThat(imported.report().clean()).isTrue();
        assertThat(bill.billingDays()).isEqualTo(7);
        assertThat(bill.complete()).isTrue();

        assertThat(lineKWh(bill, "Usage 00:00-06:00")).isEqualByComparingTo("42.000");
        assertThat(lineKWh(bill, "Usage 06:00-11:00")).isEqualByComparingTo("35.000");
        assertThat(lineKWh(bill, "Usage 11:00-16:00")).isEqualByComparingTo("35.000");
        assertThat(lineKWh(bill, "Usage 16:00-21:00")).isEqualByComparingTo("35.000");
        assertThat(lineKWh(bill, "Usage 21:00-24:00")).isEqualByComparingTo("21.000");

        assertThat(lineCost(bill, "Usage 16:00-21:00")).isEqualByComparingTo("17.339");
        assertThat(bill.subtotal(ChargeKind.SUPPLY)).isEqualByComparingTo("8.624");
        assertThat(bill.totalRounded()).isEqualByComparingTo("50.60");
    }

    @Test
    void realExportReproducesTheKnownAnnualBill() throws IOException {
        Path csv = householdData();
        assumeTrue(Files.exists(csv),
                "Household interval data not present at " + csv.toAbsolutePath()
                        + "; pass -Delectrome.usage.csv=<path> to run this test");

        var plan = PlanYamlLoader.load(PLANS.resolve("reference-tou.yaml"));
        var imported = UsageCsvReader.read(csv);
        var year = new DateRange(LocalDate.of(2025, 8, 19), LocalDate.of(2026, 8, 18));
        var bill = ENGINE.cost(imported.usage(), plan, year);

        assertThat(bill.billingDays()).isEqualTo(365);
        assertThat(bill.complete()).isTrue();

        assertThat(lineKWh(bill, "Usage 00:00-06:00")).isEqualByComparingTo("1454.135");
        assertThat(lineKWh(bill, "Usage 06:00-11:00")).isEqualByComparingTo("1633.129");
        assertThat(lineKWh(bill, "Usage 11:00-16:00")).isEqualByComparingTo("1753.332");
        assertThat(lineKWh(bill, "Usage 16:00-21:00")).isEqualByComparingTo("2393.158");
        assertThat(lineKWh(bill, "Usage 21:00-24:00")).isEqualByComparingTo("1236.016");

        assertThat(lineCost(bill, "Usage 00:00-06:00").setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("72.56");
        assertThat(lineCost(bill, "Usage 16:00-21:00").setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("1185.57");

        assertThat(bill.subtotal(ChargeKind.SUPPLY)).isEqualByComparingTo("449.68");
        assertThat(bill.totalKWh()).isEqualByComparingTo("8469.770");
        assertThat(bill.totalRounded()).isEqualByComparingTo("2852.80");
    }

    @Test
    void realExportReportsTheDaylightSavingAnomaly() throws IOException {
        Path csv = householdData();
        assumeTrue(Files.exists(csv), "Household interval data not present");

        var report = UsageCsvReader.read(csv).report();
        // Spring-forward days legitimately carry 46 intervals.
        assertThat(report.shortDays()).containsExactly(
                LocalDate.of(2024, 10, 6), LocalDate.of(2025, 10, 5));
        // Autumn fall-back days should carry 50 but the exporter drops the repeated hour.
        assertThat(report.longDays()).isEmpty();
        assertThat(report.duplicateStarts()).isEmpty();
        assertThat(report.outlierDays()).contains(LocalDate.of(2024, 10, 4));
    }

    @Test
    void comparingBothShippedPlansOverTwoYearsIsFast() throws IOException {
        Path csv = householdData();
        assumeTrue(Files.exists(csv), "Household interval data not present");

        var usage = UsageCsvReader.read(csv).usage();
        var plans = PlanYamlLoader.loadDirectory(PLANS);
        var everything = usage.consumption().range().orElseThrow();

        long startedAt = System.nanoTime();
        for (var plan : plans) {
            ENGINE.cost(usage, plan, everything);
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(1000);
    }
}
