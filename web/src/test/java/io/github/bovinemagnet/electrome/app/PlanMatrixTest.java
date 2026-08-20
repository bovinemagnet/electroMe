package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.app.PlanMatrix.Component;
import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.CostingEngine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plans as columns, charge components as rows.
 *
 * <p>Rows are components rather than plans because the useful reading is horizontal: this plan
 * wins on supply, that one on the evening peak. A ranking cannot show that.
 */
class PlanMatrixTest {

    private static final DateRange RANGE =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31));

    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        var day = LocalDate.of(2025, 1, 1);
        while (!day.isAfter(LocalDate.of(2025, 1, 31))) {
            for (int slot = 0; slot < 48; slot++) {
                readings.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT)
                                .plusMinutes(slot * 30L),
                        Duration.ofMinutes(30), new BigDecimal("0.4"), Quality.ACTUAL));
            }
            day = day.plusDays(1);
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(String id, String supply, Charge... usage) {
        var charges = new ArrayList<Charge>();
        charges.add(new DailySupply(new BigDecimal(supply)));
        charges.addAll(List.of(usage));
        return new Plan(id, "Plan " + id, "Retailer " + id, DistributionZone.AUSNET, charges,
                true, null, null);
    }

    /**
     * Overnight cheapest, middle of day next, evening dearest.
     *
     * <p>The overnight rate has to be the cheapest on the tariff for it to read as off-peak.
     * Where a middle-of-day solar rate undercuts it, the overnight rate is a shoulder rate and
     * is classified as one — the same rule the dashboard's colours already follow.
     */
    private static TimeOfUse tou(String night, String midday, String peak) {
        return new TimeOfUse(List.of(
                new Band(0, 10 * 60, DaySelector.ALL, new BigDecimal(night)),
                new Band(10 * 60, 16 * 60, DaySelector.ALL, new BigDecimal(midday)),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal(peak)),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal(night))));
    }

    private static PlanMatrix matrixOf(Plan... plans) {
        var engine = new CostingEngine();
        var bills = new ArrayList<BillBreakdown>();
        for (var plan : plans) {
            bills.add(engine.cost(usage(), plan, RANGE));
        }
        return PlanMatrix.of(bills, RANGE);
    }

    private static PlanMatrix.Row row(PlanMatrix matrix, Component component) {
        return matrix.rows().stream()
                .filter(r -> r.component() == component)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + component));
    }

    // -----------------------------------------------------------------
    // The component union.
    // -----------------------------------------------------------------

    @Test
    void usesTheUnionOfComponentsAcrossEveryPlan() {
        var matrix = matrixOf(
                plan("tou", "100.00", tou("15.00", "18.00", "50.00")),
                plan("flat", "120.00", new FlatRate(new BigDecimal("28.00"))));

        assertThat(matrix.rows()).extracting(PlanMatrix.Row::component)
                .contains(Component.DAILY_SUPPLY, Component.PEAK, Component.MIDDAY,
                        Component.OFFPEAK, Component.FLAT);
    }

    @Test
    void aPlanLackingAComponentShowsAnAbsenceRatherThanBeingOmitted() {
        // The rows have to align, and a missing charge is information: this plan simply does
        // not have an evening rate.
        var matrix = matrixOf(
                plan("tou", "100.00", tou("15.00", "18.00", "50.00")),
                plan("flat", "120.00", new FlatRate(new BigDecimal("28.00"))));

        var peak = row(matrix, Component.PEAK);
        assertThat(peak.cells()).hasSize(2);
        assertThat(peak.cells().get(0).present()).isTrue();
        assertThat(peak.cells().get(1).present()).isFalse();
        assertThat(peak.cells().get(1).cost()).isNull();
    }

    @Test
    void everyRowHasOneCellPerPlanInColumnOrder() {
        var matrix = matrixOf(
                plan("a", "100.00", new FlatRate(new BigDecimal("28.00"))),
                plan("b", "110.00", new FlatRate(new BigDecimal("26.00"))),
                plan("c", "120.00", new FlatRate(new BigDecimal("24.00"))));

        assertThat(matrix.plans()).extracting(Plan::id).containsExactly("a", "b", "c");
        assertThat(matrix.rows()).allSatisfy(r -> assertThat(r.cells()).hasSize(3));
    }

    @Test
    void rowsAreOrderedTheSameWayWhicheverPlansAreChosen() {
        // Supply first, then the day in order, so a reader's eye learns the layout once.
        var matrix = matrixOf(
                plan("tou", "100.00", tou("15.00", "18.00", "50.00")),
                plan("feed", "120.00", tou("16.00", "19.00", "48.00"),
                        new SolarFeedIn(new BigDecimal("3.30"))));

        assertThat(matrix.rows()).extracting(PlanMatrix.Row::component)
                .containsSubsequence(Component.DAILY_SUPPLY, Component.OFFPEAK,
                        Component.MIDDAY, Component.PEAK);
    }

    // -----------------------------------------------------------------
    // Reading horizontally.
    // -----------------------------------------------------------------

    @Test
    void marksTheCheapestCellInEachRow() {
        var matrix = matrixOf(
                plan("dear-supply", "150.00", new FlatRate(new BigDecimal("20.00"))),
                plan("cheap-supply", "90.00", new FlatRate(new BigDecimal("30.00"))));

        // The point of the matrix: one plan wins on supply, the other on usage.
        assertThat(row(matrix, Component.DAILY_SUPPLY).cells().get(1).cheapest()).isTrue();
        assertThat(row(matrix, Component.DAILY_SUPPLY).cells().get(0).cheapest()).isFalse();
        assertThat(row(matrix, Component.FLAT).cells().get(0).cheapest()).isTrue();
    }

    @Test
    void aRowOnlyOnePlanHasMarksNothingAsCheapest() {
        // "Cheapest" among one is not a comparison, and a tick there would read as a win.
        var matrix = matrixOf(
                plan("tou", "100.00", tou("15.00", "18.00", "50.00")),
                plan("flat", "100.00", new FlatRate(new BigDecimal("28.00"))));

        assertThat(row(matrix, Component.PEAK).cells()).noneSatisfy(
                cell -> assertThat(cell.cheapest()).isTrue());
    }

    @Test
    void cellsCarryTheQuantityAsWellAsTheCost() {
        var matrix = matrixOf(
                plan("a", "100.00", new FlatRate(new BigDecimal("28.00"))),
                plan("b", "110.00", new FlatRate(new BigDecimal("26.00"))));

        var flat = row(matrix, Component.FLAT).cells().get(0);
        // 31 days x 48 slots x 0.4 kWh
        assertThat(flat.quantity()).isEqualByComparingTo("595.2");
        assertThat(flat.cost()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void sumsSeveralBandsThatMeanTheSameThingIntoOneRow() {
        // The overnight rate is published as two windows either side of midnight; a reader
        // comparing plans wants one overnight figure, not two rows that must be added.
        var matrix = matrixOf(
                plan("tou", "100.00", tou("15.00", "18.00", "50.00")),
                plan("tou2", "110.00", tou("14.00", "17.00", "52.00")));

        // 00:00-10:00 and 21:00-24:00 at 0.4 kWh per half hour, over 31 days.
        assertThat(row(matrix, Component.OFFPEAK).cells().get(0).quantity())
                .isEqualByComparingTo("322.4");
    }

    // -----------------------------------------------------------------
    // Shapes the closed vocabulary has to cope with.
    // -----------------------------------------------------------------

    @Test
    void blockRatesGetTheirOwnRowRatherThanBeingCalledAShoulder() {
        var matrix = matrixOf(
                plan("blocks", "100.00", new Tiered(ResetPeriod.QUARTERLY, List.of(
                        new Tier(new BigDecimal("200"), new BigDecimal("22.00")),
                        new Tier(null, new BigDecimal("33.00"))))),
                plan("flat", "110.00", new FlatRate(new BigDecimal("28.00"))));

        assertThat(matrix.rows()).extracting(PlanMatrix.Row::component)
                .contains(Component.BLOCK)
                .doesNotContain(Component.SHOULDER);
    }

    @Test
    void feedInAndSupplyAreDistinctRows() {
        var matrix = matrixOf(
                plan("solar", "100.00", new FlatRate(new BigDecimal("28.00")),
                        new SolarFeedIn(new BigDecimal("3.30"))),
                plan("plain", "110.00", new FlatRate(new BigDecimal("26.00"))));

        assertThat(matrix.rows()).extracting(PlanMatrix.Row::component)
                .contains(Component.DAILY_SUPPLY, Component.FEED_IN);
    }

    // -----------------------------------------------------------------
    // The difference summary: the answer the matrix is evidence for.
    // -----------------------------------------------------------------

    @Test
    void attributesTheWholeDifferenceBetweenTwoPlansToNamedComponents() {
        var matrix = matrixOf(
                plan("a", "150.00", tou("15.00", "18.00", "60.00")),
                plan("b", "90.00", tou("19.00", "22.00", "40.00")));

        var differences = matrix.differences(0, 1);

        // Every dollar of the gap is accounted for, or the sentence built from it is a lie.
        var attributed = differences.stream()
                .map(PlanMatrix.Difference::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var gap = matrix.total(0).subtract(matrix.total(1));

        assertThat(attributed).isCloseTo(gap,
                org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
    }

    @Test
    void ordersDifferencesBySizeSoTheSentenceLeadsWithWhatMatters() {
        var matrix = matrixOf(
                plan("a", "150.00", tou("15.00", "18.00", "60.00")),
                plan("b", "90.00", tou("19.00", "22.00", "40.00")));

        var differences = matrix.differences(0, 1);

        assertThat(differences).isNotEmpty();
        assertThat(differences).isSortedAccordingTo(
                java.util.Comparator.comparing(
                        (PlanMatrix.Difference d) -> d.amount().abs()).reversed());
        // The evening rate is the biggest lever here, and must lead.
        assertThat(differences.get(0).component()).isEqualTo(Component.PEAK);
    }

    @Test
    void distinguishesWhereAPlanLosesGroundFromWhereItGains() {
        var matrix = matrixOf(
                plan("a", "150.00", tou("15.00", "18.00", "60.00")),
                plan("b", "90.00", tou("19.00", "22.00", "40.00")));

        // "cheaper on the evening rate, offset by more overnight" needs both directions.
        var differences = matrix.differences(1, 0);
        assertThat(differences).anySatisfy(d -> assertThat(d.favoursFirst()).isTrue());
        assertThat(differences).anySatisfy(d -> assertThat(d.favoursFirst()).isFalse());
    }

    @Test
    void aComponentOnlyOnePlanHasIsStillAttributed() {
        // The feed-in credit is the whole reason one plan is cheaper; dropping it because the
        // other plan has no such row would leave the gap unexplained.
        var engine = new CostingEngine();
        var withExport = new UsageData(usage().consumption(), exportSeries());
        var bills = List.of(
                engine.cost(withExport, plan("solar", "100.00",
                        new FlatRate(new BigDecimal("28.00")),
                        new SolarFeedIn(new BigDecimal("3.30"))), RANGE),
                engine.cost(withExport, plan("plain", "100.00",
                        new FlatRate(new BigDecimal("28.00"))), RANGE));
        var matrix = PlanMatrix.of(bills, RANGE);

        assertThat(matrix.differences(0, 1)).extracting(PlanMatrix.Difference::component)
                .contains(Component.FEED_IN);
    }

    /** Midday export, so a feed-in tariff has something to credit. */
    private static UsageSeries exportSeries() {
        var readings = new ArrayList<IntervalReading>();
        var day = LocalDate.of(2025, 1, 1);
        while (!day.isAfter(LocalDate.of(2025, 1, 31))) {
            for (int slot = 20; slot < 32; slot++) {
                readings.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT)
                                .plusMinutes(slot * 30L),
                        Duration.ofMinutes(30), new BigDecimal("0.6"), Quality.ACTUAL));
            }
            day = day.plusDays(1);
        }
        return UsageSeries.of(readings);
    }

    @Test
    void totalsMatchTheBillsTheMatrixWasBuiltFrom() {
        var matrix = matrixOf(
                plan("a", "100.00", new FlatRate(new BigDecimal("28.00"))),
                plan("b", "110.00", new FlatRate(new BigDecimal("26.00"))));

        // A matrix whose columns do not add up to the bill would be evidence for nothing.
        for (int column = 0; column < matrix.plans().size(); column++) {
            var summed = BigDecimal.ZERO;
            for (var r : matrix.rows()) {
                var cell = r.cells().get(column);
                if (cell.present()) {
                    summed = summed.add(cell.cost());
                }
            }
            assertThat(summed).isCloseTo(matrix.total(column),
                    org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        }
    }
}
