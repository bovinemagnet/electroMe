package io.github.bovinemagnet.electrome.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.app.PlanMatrix;
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
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The whole comparison screen, assembled.
 *
 * <p>The matrix is evidence. The sentence is the answer, and a reader should be able to stop
 * after it — so most of what is tested here is whether the sentence is true.
 */
class MatrixViewTest {

    private static final DateRange YEAR =
            new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

    private static UsageData usage() {
        var readings = new ArrayList<IntervalReading>();
        var day = LocalDate.of(2025, 1, 1);
        while (!day.isAfter(LocalDate.of(2025, 12, 31))) {
            for (int slot = 0; slot < 48; slot++) {
                int minute = slot * 30;
                boolean peak = minute >= 16 * 60 && minute < 21 * 60;
                readings.add(new IntervalReading(
                        LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT).plusMinutes(minute),
                        Duration.ofMinutes(30),
                        peak ? new BigDecimal("0.7") : new BigDecimal("0.3"),
                        Quality.ACTUAL));
            }
            day = day.plusDays(1);
        }
        return UsageData.consumptionOnly(UsageSeries.of(readings));
    }

    private static Plan plan(String id, String name, String supply, Charge... usage) {
        var charges = new ArrayList<Charge>();
        charges.add(new DailySupply(new BigDecimal(supply)));
        charges.addAll(List.of(usage));
        return new Plan(id, name, "Retailer", DistributionZone.AUSNET, charges, true, null, null);
    }

    /** Four windows, so a comparison has more components than a sentence can name. */
    private static TimeOfUse touWithMidday(String night, String midday, String peak) {
        return new TimeOfUse(List.of(
                new Band(0, 10 * 60, DaySelector.ALL, new BigDecimal(night)),
                new Band(10 * 60, 16 * 60, DaySelector.ALL, new BigDecimal(midday)),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal(peak)),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal(night))));
    }

    private static TimeOfUse tou(String night, String peak) {
        return new TimeOfUse(List.of(
                new Band(0, 16 * 60, DaySelector.ALL, new BigDecimal(night)),
                new Band(16 * 60, 21 * 60, DaySelector.ALL, new BigDecimal(peak)),
                new Band(21 * 60, 24 * 60, DaySelector.ALL, new BigDecimal(night))));
    }

    private static MatrixView view(List<Plan> plans, List<String> unknown, List<String> surplus) {
        var engine = new CostingEngine();
        var bills = new ArrayList<BillBreakdown>();
        for (var plan : plans) {
            bills.add(engine.cost(usage(), plan, YEAR));
        }
        return MatrixView.of(PlanMatrix.of(bills, YEAR), usage(), YEAR, unknown, surplus);
    }

    private static MatrixView twoPlans() {
        return view(List.of(
                plan("dear", "Dear Evenings", "100.00", tou("20.00", "60.00")),
                plan("kind", "Kind Evenings", "130.00", tou("22.00", "35.00"))),
                List.of(), List.of());
    }

    // -----------------------------------------------------------------
    // The sentence.
    // -----------------------------------------------------------------

    @Test
    void namesTheCheaperPlanAndTheGap() {
        var view = twoPlans();

        assertThat(view.winner().name()).isEqualTo("Kind Evenings");
        assertThat(view.verdicts()).singleElement().satisfies(verdict -> {
            assertThat(verdict.sentence()).contains("Kind Evenings");
            assertThat(verdict.sentence()).contains("cheaper");
        });
    }

    @Test
    void attributesTheGapToNamedComponentsInPlainLanguage() {
        var view = twoPlans();
        var sentence = view.verdicts().get(0).sentence();

        // The evening rate is the reason, and the supply charge works against it. A sentence
        // that gave only the total would send a reader back to the matrix to work out why.
        assertThat(sentence).containsIgnoringCase("evening peak");
        assertThat(sentence).containsIgnoringCase("offset by");
        assertThat(sentence).containsIgnoringCase("daily supply");
    }

    @Test
    void theFiguresInTheSentenceAddUpToTheGapItClaims() {
        var view = twoPlans();
        var verdict = view.verdicts().get(0);

        var attributed = verdict.contributions().stream()
                .map(PlanMatrix.Difference::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(attributed.abs()).isCloseTo(verdict.gap().abs(),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.02")));
    }

    @Test
    void leadsWithTheLargestContributionsRatherThanEveryComponent() {
        var view = twoPlans();

        // Three named reasons is the most a sentence can carry and stay readable; the rest are
        // in the matrix directly above it.
        assertThat(view.verdicts().get(0).named()).hasSizeLessThanOrEqualTo(3);
    }

    // -----------------------------------------------------------------
    // How fragile the answer is.
    // -----------------------------------------------------------------

    @Test
    void reportsWhereTheRankingFlips() {
        var view = view(List.of(
                plan("low-supply", "Low Supply", "50.00", new FlatRate(new BigDecimal("35.00"))),
                plan("low-usage", "Low Usage", "180.00", new FlatRate(new BigDecimal("25.00")))),
                List.of(), List.of());

        var crossover = view.verdicts().get(0).crossover();
        assertThat(crossover.exists()).isTrue();
        assertThat(view.verdicts().get(0).robustness())
                .containsIgnoringCase("kWh");
    }

    @Test
    void saysWhenAPlanWinsAtEveryLevelOfUse() {
        var view = view(List.of(
                plan("better", "Better", "100.00", new FlatRate(new BigDecimal("25.00"))),
                plan("worse", "Worse", "120.00", new FlatRate(new BigDecimal("30.00")))),
                List.of(), List.of());

        // A stronger finding than any number, and the common case.
        assertThat(view.verdicts().get(0).crossover().exists()).isFalse();
        assertThat(view.verdicts().get(0).robustness())
                .containsIgnoringCase("every level of use");
    }

    // -----------------------------------------------------------------
    // Selections that are not quite right.
    // -----------------------------------------------------------------

    @Test
    void namesAnUnknownIdentifierRatherThanDroppingItSilently() {
        // Silently dropping it would let a reader compare three plans believing they compared
        // four.
        var view = view(List.of(
                plan("a", "A", "100.00", new FlatRate(new BigDecimal("28.00"))),
                plan("b", "B", "110.00", new FlatRate(new BigDecimal("26.00")))),
                List.of("no-such-plan"), List.of());

        assertThat(view.unknown()).containsExactly("no-such-plan");
        assertThat(view.hasProblems()).isTrue();
    }

    @Test
    void namesPlansDroppedForExceedingTheColumnLimit() {
        var view = view(List.of(
                plan("a", "A", "100.00", new FlatRate(new BigDecimal("28.00"))),
                plan("b", "B", "110.00", new FlatRate(new BigDecimal("26.00")))),
                List.of(), List.of("e", "f"));

        assertThat(view.surplus()).containsExactly("e", "f");
        assertThat(view.hasProblems()).isTrue();
    }

    @Test
    void aCleanComparisonReportsNoProblems() {
        assertThat(twoPlans().hasProblems()).isFalse();
    }

    // -----------------------------------------------------------------
    // Three and four columns.
    // -----------------------------------------------------------------

    @Test
    void comparesUpToFourPlansWithAVerdictForEachLoser() {
        var view = view(List.of(
                plan("a", "A", "100.00", tou("20.00", "60.00")),
                plan("b", "B", "130.00", tou("22.00", "35.00")),
                plan("c", "C", "110.00", new FlatRate(new BigDecimal("30.00"))),
                plan("d", "D", "90.00", new FlatRate(new BigDecimal("34.00")))),
                List.of(), List.of());

        assertThat(view.matrix().plans()).hasSize(4);
        assertThat(view.verdicts()).hasSize(3);
        assertThat(view.verdicts()).extracting(v -> v.against().id())
                .doesNotContain(view.winner().id());
    }

    @Test
    void embedsAShapeChartComparingTheTariffsAgainstTheLoadCurve() {
        // The insight the dashboard gives for one plan, extended to several: whether a plan's
        // cheap window sits where the household actually uses power.
        assertThat(twoPlans().rateChart()).contains("series");
    }

    @Test
    void theSentenceOwnFiguresReconcileToTheGapItClaims() {
        // A sentence naming only its three largest reasons invites the reader to add them up
        // and get a different number from the gap in its own opening clause. Whatever is left
        // over has to be acknowledged, or the sentence is quietly wrong.
        var view = view(List.of(
                plan("a", "A", "150.00", touWithMidday("20.00", "26.00", "62.00")),
                plan("b", "B", "92.00", touWithMidday("25.00", "33.00", "41.00")),
                plan("c", "C", "121.00", touWithMidday("22.00", "29.00", "48.00"))),
                List.of(), List.of());

        for (var verdict : view.verdicts()) {
            var namedSum = verdict.named().stream()
                    .map(PlanMatrix.Difference::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var stated = namedSum.add(verdict.remainder());

            assertThat(stated.abs())
                    .as("figures stated in: %s", verdict.sentence())
                    .isCloseTo(verdict.gap().abs(),
                            org.assertj.core.data.Offset.offset(new BigDecimal("0.02")));
        }
    }

    @Test
    void acknowledgesWhatTheNamedReasonsDoNotAccountFor() {
        var view = view(List.of(
                plan("a", "A", "150.00", touWithMidday("20.00", "26.00", "62.00")),
                plan("b", "B", "92.00", touWithMidday("25.00", "33.00", "41.00")),
                plan("c", "C", "121.00", touWithMidday("22.00", "29.00", "48.00"))),
                List.of(), List.of());

        var withRemainder = view.verdicts().stream()
                .filter(v -> v.remainder().abs().compareTo(new BigDecimal("1")) > 0)
                .findFirst();

        assertThat(withRemainder).isPresent();
        assertThat(withRemainder.get().sentence()).containsIgnoringCase("smaller");
    }

    @Test
    void saysNothingAboutARemainderWhenTheNamedReasonsCoverTheWholeGap() {
        // Two plans differing only in supply and one rate need no hedge, and adding one would
        // suggest there is more to the story than there is.
        var view = view(List.of(
                plan("a", "A", "100.00", new FlatRate(new BigDecimal("30.00"))),
                plan("b", "B", "130.00", new FlatRate(new BigDecimal("26.00")))),
                List.of(), List.of());

        assertThat(view.verdicts().get(0).remainder()).isEqualByComparingTo("0");
        assertThat(view.verdicts().get(0).sentence()).doesNotContain("smaller");
    }
}
