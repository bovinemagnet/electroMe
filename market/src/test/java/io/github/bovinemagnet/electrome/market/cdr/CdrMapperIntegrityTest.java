package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.DiscountScope;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * What the mapper must not do quietly.
 *
 * <p>Every plan in a live AusNet harvest maps. That is not coverage, it is silence: a capped
 * free window read as unlimited free usage produces a plan that validates, ranks and wins,
 * and is cheaper than the tariff it claims to be. These tests pin the shapes that were being
 * read wrongly, and the shapes that must be refused rather than approximated.
 */
class CdrMapperIntegrityTest {

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/" + name), StandardCharsets.UTF_8);
    }

    private static Plan map(String fixture) throws IOException {
        return CdrPlanMapper.map(fixture(fixture), DistributionZone.AUSNET);
    }

    private static <T> T charge(Plan plan, Class<T> kind) {
        return plan.charges().stream().filter(kind::isInstance).map(kind::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no " + kind.getSimpleName()));
    }

    private static Band bandFrom(TimeOfUse tou, int minuteOfDay) {
        return tou.bands().stream().filter(b -> b.fromMinuteOfDay() == minuteOfDay)
                .findFirst().orElseThrow(() -> new AssertionError("no band at " + minuteOfDay));
    }

    // ---------- capped time-of-use windows ----------

    /**
     * The defect this whole exercise exists for.
     *
     * <p>Read only from the first rate row, this window is unlimited energy at a hundredth of a
     * cent. Its real tariff gives fifty kilowatt hours a day and charges 9.9c for the rest.
     */
    @Test
    void readsADailyCappedFreeWindowAsBlocksRatherThanUnlimited() throws IOException {
        var tou = charge(map("plan-detail-capped-window.json"), TimeOfUse.class);
        var free = bandFrom(tou, 11 * 60);

        assertThat(free.capped()).isTrue();
        assertThat(free.reset()).isEqualTo(ResetPeriod.DAILY);
        assertThat(free.tiers()).hasSize(2);
        assertThat(free.tiers().get(0).thresholdKWh()).isEqualByComparingTo("50");
        assertThat(free.tiers().get(0).centsPerKWh()).isEqualByComparingTo("0.00011");
        assertThat(free.tiers().get(1).unbounded()).isTrue();
        assertThat(free.tiers().get(1).centsPerKWh()).isEqualByComparingTo("9.9");
    }

    @Test
    void leavesUncappedBandsExactlyAsTheyWere() throws IOException {
        var plan = map("plan-detail-capped-window.json");
        var tou = charge(plan, TimeOfUse.class);

        assertThat(charge(plan, DailySupply.class).centsPerDay()).isEqualByComparingTo("134.2");
        var peak = bandFrom(tou, 16 * 60);
        assertThat(peak.capped()).isFalse();
        assertThat(peak.reset()).isNull();
        assertThat(peak.centsPerKWh()).isEqualByComparingTo("47.3");
        assertThat(peak.toMinuteOfDay()).isEqualTo(23 * 60);
        // One rate entry, three windows: still three separate single-rate bands.
        assertThat(bandFrom(tou, 15 * 60).centsPerKWh()).isEqualByComparingTo("22.0");
        assertThat(bandFrom(tou, 23 * 60).centsPerKWh()).isEqualByComparingTo("22.0");
        assertThat(bandFrom(tou, 0).centsPerKWh()).isEqualByComparingTo("22.0");
    }

    /**
     * A capped window split at midnight is rejoined, not counted twice.
     *
     * <p>A time of day cannot say "until 11:00 tomorrow", so the register publishes an
     * overnight window as 15:00 to 00:00 and 00:00 to 11:00. For a capped rate the difference
     * matters: fifteen kilowatt hours a day across the pair, not fifteen in each half.
     */
    @Test
    void rejoinsACappedWindowThatTheRegisterSplitAtMidnight() throws IOException {
        var tou = charge(map("plan-detail-midnight-cap.json"), TimeOfUse.class);

        assertThat(tou.bands()).hasSize(2);
        var overnight = bandFrom(tou, 15 * 60);
        assertThat(overnight.wrapsMidnight()).isTrue();
        assertThat(overnight.toMinuteOfDay()).isEqualTo(11 * 60);
        assertThat(overnight.reset()).isEqualTo(ResetPeriod.DAILY);
        assertThat(overnight.tiers().get(0).thresholdKWh()).isEqualByComparingTo("15");
        assertThat(overnight.tiers().get(0).centsPerKWh()).isEqualByComparingTo("33.22");
        assertThat(overnight.tiers().get(1).centsPerKWh()).isEqualByComparingTo("35.75");
    }

    /**
     * One allowance shared across two windows is refused, not halved.
     *
     * <p>GloBird's EasyEV publishes a single twenty kilowatt hour block covering both its
     * overnight and its midday window. A band owns its own allowance, so mapping this would
     * hand the household two twenty kilowatt hour caps and understate the plan — the same
     * error, in miniature, as reading the cap away entirely.
     */
    @Test
    void refusesACapSharedAcrossSeveralWindows() {
        assertThatThrownBy(() -> map("plan-detail-shared-cap.json"))
                .isInstanceOf(UnmappablePlanException.class)
                .hasMessageContaining("2 separate windows");
    }

    /**
     * The mapped plan prices a day that spends its whole allowance.
     *
     * <p>Mapping the cap is only half the claim. This is the other half: energy past the cap
     * has to cost the balance rate, or the plan still ranks as though the window were free.
     */
    @Test
    void pricesADayThatExceedsTheCapAtTheBalanceRate() throws IOException {
        var plan = map("plan-detail-capped-window.json");

        // 60 kWh inside 11:00-15:00, and nothing anywhere else.
        var day = java.time.LocalDate.of(2025, 1, 1);
        var readings = new java.util.ArrayList<
                io.github.bovinemagnet.electrome.core.domain.IntervalReading>();
        for (int minute = 0; minute < 1440; minute += 30) {
            var kWh = minute >= 11 * 60 && minute < 15 * 60
                    ? new java.math.BigDecimal("7.5")
                    : java.math.BigDecimal.ZERO;
            readings.add(new io.github.bovinemagnet.electrome.core.domain.IntervalReading(
                    day.atStartOfDay().plusMinutes(minute), java.time.Duration.ofMinutes(30),
                    kWh, io.github.bovinemagnet.electrome.core.domain.Quality.ACTUAL));
        }
        var usage = io.github.bovinemagnet.electrome.core.domain.UsageData.consumptionOnly(
                io.github.bovinemagnet.electrome.core.domain.UsageSeries.of(readings));

        var bill = new io.github.bovinemagnet.electrome.core.cost.CostingEngine().cost(
                usage, plan,
                new io.github.bovinemagnet.electrome.core.domain.DateRange(day, day));

        var free = bill.lines().stream()
                .filter(line -> line.label().equals("Usage 11:00-15:00 to 50 kWh"))
                .findFirst().orElseThrow();
        var balance = bill.lines().stream()
                .filter(line -> line.label().equals("Usage 11:00-15:00 balance"))
                .findFirst().orElseThrow();

        assertThat(free.quantity()).isEqualByComparingTo("50");
        assertThat(balance.quantity()).isEqualByComparingTo("10");
        // 10 kWh at 9.9c, which the old mapper would have charged at a hundredth of a cent.
        assertThat(balance.cost()).isEqualByComparingTo("0.99");
    }

    // ---------- demand charges ----------

    @Test
    void readsADemandChargeIntoTheDemandModel() throws IOException {
        var plan = map("plan-detail-demand.json");
        var demand = charge(plan, Demand.class);

        assertThat(demand.fromMinuteOfDay()).isEqualTo(15 * 60);
        assertThat(demand.toMinuteOfDay()).isEqualTo(21 * 60);
        assertThat(demand.reset()).isEqualTo(ResetPeriod.DAILY);
        assertThat(demand.centsPerKWPerDay()).isEqualByComparingTo("42.647");
        // The usage charge from the other tariff period is still mapped.
        assertThat(plan.charges()).hasAtLeastOneElementOfType(
                io.github.bovinemagnet.electrome.core.tariff.FlatRate.class);
    }

    /**
     * A demand rate that changes with the season is refused.
     *
     * <p>Every demand plan on the AusNet register publishes a summer rate and a winter one.
     * Charging a household summer demand all year is not an approximation, it is a different
     * tariff, so the plan is named as unmappable instead.
     */
    @Test
    void refusesASeasonalDemandCharge() {
        assertThatThrownBy(() -> map("plan-detail-seasonal-demand.json"))
                .isInstanceOf(UnmappablePlanException.class)
                .hasMessageContaining("seasonal");
    }

    // ---------- discounts and controlled load ----------

    @Test
    void readsPercentageDiscountsAndNamesTheirCondition() throws IOException {
        var plan = map("plan-detail-discounts.json");
        var discounts = plan.charges().stream().filter(Discount.class::isInstance)
                .map(Discount.class::cast).toList();

        assertThat(discounts).hasSize(2);
        var payOnTime = discounts.get(0);
        assertThat(payOnTime.basis()).isEqualTo(DiscountBasis.PERCENTAGE);
        assertThat(payOnTime.scope()).isEqualTo(DiscountScope.TOTAL);
        assertThat(payOnTime.value()).isEqualByComparingTo("5");
        assertThat(payOnTime.conditional()).isTrue();
        assertThat(payOnTime.condition()).contains("pay the bill by the due date");

        var guaranteed = discounts.get(1);
        assertThat(guaranteed.value()).isEqualByComparingTo("10");
        assertThat(guaranteed.conditional()).isFalse();
        assertThat(guaranteed.condition()).isNull();
    }

    /**
     * A one-off sign-up credit is shown, not costed.
     *
     * <p>Costing it as a discount would subtract two hundred dollars from every year the
     * household is on the plan, not the first. That is the same class of error as the capped
     * window, arriving from the other direction.
     */
    @Test
    void doesNotCostAOneOffSignUpCreditAsAnAnnualDiscount() throws IOException {
        var plan = map("plan-detail-discounts.json");
        assertThat(plan.charges().stream().filter(Discount.class::isInstance)
                .map(Discount.class::cast))
                .noneMatch(d -> d.basis() == DiscountBasis.FIXED);

        var extras = CdrPlanMapper.extrasOf(fixture("plan-detail-discounts.json"));
        assertThat(extras.incentives()).anySatisfy(incentive ->
                assertThat(incentive.description()).contains("$200 sign up credit"));
    }

    @Test
    void readsTheControlledLoadRate() throws IOException {
        var controlled = charge(map("plan-detail-discounts.json"), ControlledLoad.class);
        assertThat(controlled.centsPerKWh()).isEqualByComparingTo("22.055");
        assertThat(controlled.windowed()).isFalse();
    }

    // ---------- the tripwire ----------

    /**
     * Rate rows that do not form ascending blocks are refused rather than read partially.
     *
     * <p>The permanent form of the guard. Whatever the register publishes next, a rate entry
     * this mapper cannot account for in full becomes a named gap in the harvest report rather
     * than a plan priced from whichever row happened to be first.
     */
    @Test
    void refusesRateRowsItCannotAccountForInFull() throws IOException {
        var mangled = fixture("plan-detail-capped-window.json")
                .replace("{ \"volume\": 50, \"unitPrice\": \"0.000001\" }",
                        "{ \"unitPrice\": \"0.000001\" }");
        assertThatThrownBy(() -> CdrPlanMapper.map(mangled, DistributionZone.AUSNET))
                .isInstanceOf(UnmappablePlanException.class)
                .hasMessageContaining("do not form");
    }

    @Test
    void aPlanWithNoneOfTheseIsUnchanged() throws IOException {
        var plan = CdrPlanMapper.map(fixture("plan-detail-tou.json"), DistributionZone.AUSNET);
        assertThat(plan.charges()).noneMatch(c -> c instanceof Demand);
        assertThat(plan.charges()).noneMatch(c -> c instanceof Discount);
        assertThat(plan.charges()).noneMatch(c -> c instanceof ControlledLoad);
        assertThat(charge(plan, TimeOfUse.class).bands())
                .allSatisfy(band -> assertThat(band.capped()).isFalse());
    }
}
