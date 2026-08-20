package io.github.bovinemagnet.electrome.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shortlist priced against the household's own year, checked against figures worked out
 * independently of this code.
 *
 * <p>Every test above proves the engine does what the engine was told to do. This one is the
 * only check that what it was told to do is right, because its expected values were not
 * produced by it. It skips without the household export, which is not in the repository.
 *
 * <p>Agreement is asked to within 2%: the reference used simplified window arithmetic and
 * ignored the daily caps, so the cent-for-cent answer is expected to differ slightly. A gap
 * wider than that is a disagreement about tariffs, not about rounding.
 */
class ShortlistVerificationTest {

    /** Twelve months to the day before the export was taken. */
    private static final DateRange YEAR =
            new DateRange(LocalDate.of(2025, 8, 19), LocalDate.of(2026, 8, 18));

    private static final BigDecimal TOLERANCE = new BigDecimal("0.02");

    /** Worked out by hand from the household's window totals, not by this application. */
    private static final Map<String, String> REFERENCE = new LinkedHashMap<>(Map.of(
            "agl-flat-current", "3177",
            "agl-night-saver-ev", "2812",
            "powershop-tou", "2853",
            "ovo-free-window", "2815",
            "globird-4hr-free", "2743"));

    private static Path householdData() {
        return Path.of(System.getProperty("electrome.usage.csv", "../MyUsageData_19-08-2026.csv"));
    }

    private static Path plansDirectory() {
        var fromModule = Path.of("..", "plans");
        return Files.isDirectory(fromModule) ? fromModule : Path.of("plans");
    }

    @Test
    void shortlistAgreesWithTheIndependentlyWorkedFigures() throws IOException {
        var csv = householdData();
        assumeTrue(Files.isReadable(csv), "no household export at " + csv.toAbsolutePath());

        var usage = UsageCsvReader.read(csv).usage();
        var engine = new CostingEngine();

        var byId = new LinkedHashMap<String, Plan>();
        for (var plan : PlanYamlLoader.loadDirectory(plansDirectory())) {
            byId.put(plan.id(), plan);
        }

        for (var entry : REFERENCE.entrySet()) {
            var plan = byId.get(entry.getKey());
            // The plan directory belongs to the user; a plan they have renamed or removed is
            // their business, not a failure.
            if (plan == null) {
                continue;
            }
            var expected = new BigDecimal(entry.getValue());
            var actual = engine.cost(usage, plan, YEAR).totalRounded();
            var drift = actual.subtract(expected).abs()
                    .divide(expected, MathContext.DECIMAL64);

            assertThat(drift)
                    .describedAs("%s costed %s against a reference of $%s, a drift of %s%%",
                            plan.id(), actual, expected,
                            drift.multiply(new BigDecimal("100"))
                                    .setScale(2, RoundingMode.HALF_UP))
                    .isLessThanOrEqualTo(TOLERANCE);
        }
    }
}
