package io.github.bovinemagnet.electrome.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Every tariff file the household actually keeps loads, validates and prices a full year.
 *
 * <p>Deliberately asserts nothing about how many plans there are or what they are called. That
 * directory is the user's to edit, and a test that pins its contents fails the moment they add
 * a plan — which teaches them to distrust the suite rather than the plan.
 *
 * <p>What it does assert is the property that matters: a file in that directory either prices
 * a household's whole year or fails loudly here, rather than quietly leaving intervals
 * unpriced and making itself look cheap.
 */
class LivePlanFilesTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 31);

    /** The repository's plan directory, from either the module or the root working directory. */
    private static Path plansDirectory() {
        var fromModule = Path.of("..", "plans");
        return Files.isDirectory(fromModule) ? fromModule : Path.of("plans");
    }

    /** A year of half-hourly consumption, weighted towards the evening as a household is. */
    private static UsageData syntheticYear() {
        var readings = new ArrayList<IntervalReading>();
        for (var date = FROM; !date.isAfter(TO); date = date.plusDays(1)) {
            for (int minute = 0; minute < 1440; minute += 30) {
                var kWh = minute >= 960 && minute < 1260
                        ? new BigDecimal("0.90")
                        : new BigDecimal("0.35");
                readings.add(new IntervalReading(date.atStartOfDay().plusMinutes(minute),
                        Duration.ofMinutes(30), kWh, Quality.ACTUAL));
            }
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    @Test
    void everyPlanFilePricesAWholeYearWithNothingLeftOver() {
        var directory = plansDirectory();
        assumeTrue(Files.isDirectory(directory), "no plans directory to check");

        var plans = PlanYamlLoader.loadDirectory(directory);
        assertThat(plans).isNotEmpty();

        var engine = new CostingEngine();
        var usage = syntheticYear();
        var range = new DateRange(FROM, TO);

        for (var plan : plans) {
            var bill = engine.cost(usage, plan, range);
            assertThat(bill.lines())
                    .describedAs("charge lines for %s", plan.id())
                    .isNotEmpty();
            assertThat(bill.uncoveredIntervals())
                    .describedAs("intervals no charge on %s prices", plan.id())
                    .isEmpty();
            assertThat(bill.totalRounded())
                    .describedAs("total for %s", plan.id())
                    .isGreaterThan(BigDecimal.ZERO);
        }
    }
}
