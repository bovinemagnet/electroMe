package io.github.bovinemagnet.electrome.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.DiscountScope;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Membership;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A plan written out and read back must be the same plan.
 *
 * <p>This is the property that matters. A harvested plan is written to disk and then loaded
 * again on the next read, so any charge kind the writer cannot express is a plan that changes
 * shape the moment it is saved — and it would change shape silently, because both halves would
 * still produce a perfectly plausible bill.
 */
class PlanYamlWriterTest {

    /** Every plan needs exactly one usage charge, so the ones under test carry this. */
    private static final FlatRate USAGE = new FlatRate(new BigDecimal("31.98"));

    private static Plan planWith(Charge... charges) {
        return new Plan("GLO785330MR@VEC", "GloBird BOOST Residential", "GloBird Energy",
                DistributionZone.AUSNET, List.of(charges), true, null, null);
    }

    private static Plan roundTrip(Plan plan) {
        return PlanYamlLoader.load(PlanYamlWriter.write(plan, origin()));
    }

    /** The charges of a plan built from a usage charge plus the one actually under test. */
    private static List<Charge> roundTripped(Charge charge) {
        return roundTrip(planWith(USAGE, charge)).charges();
    }

    private static PlanOrigin origin() {
        return new PlanOrigin("GLO785330MR@VEC", LocalDate.of(2026, 8, 21));
    }

    @Test
    void keepsIdentityAndZone() {
        var plan = roundTrip(planWith(new DailySupply(new BigDecimal("106.70")), USAGE));

        assertThat(plan.id()).isEqualTo("GLO785330MR@VEC");
        assertThat(plan.name()).isEqualTo("GloBird BOOST Residential");
        assertThat(plan.retailer()).isEqualTo("GloBird Energy");
        assertThat(plan.zone()).isEqualTo(DistributionZone.AUSNET);
    }

    /**
     * Harvested rates are already grossed up, so they are written as inclusive and not divided
     * back down. Dividing by 1.1 to write an exclusive figure would introduce a repeating
     * decimal on the way out and a different number on the way back in.
     */
    @Test
    void writesRatesAsAlreadyInclusiveOfGst() {
        var written = PlanYamlWriter.write(
                planWith(new DailySupply(new BigDecimal("106.70")), USAGE), origin());

        assertThat(written).contains("gstInclusive: true");
        assertThat(PlanYamlLoader.load(written).charges())
                .containsExactly(new DailySupply(new BigDecimal("106.70")), USAGE);
    }

    @Test
    void roundTripsDailySupplyAndFlatRate() {
        var charges = List.<Charge>of(new DailySupply(new BigDecimal("128.24")), USAGE);

        assertThat(roundTrip(planWith(charges.toArray(new Charge[0]))).charges())
                .isEqualTo(charges);
    }

    @Test
    void roundTripsTimeOfUseBands() {
        var tou = new TimeOfUse(List.of(
                Band.parse("00:00", "11:00", DaySelector.ALL, new BigDecimal("17.70")),
                Band.parse("11:00", "16:00", DaySelector.ALL, new BigDecimal("14.90")),
                Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("36.20")),
                Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("17.70"))));

        assertThat(roundTrip(planWith(tou)).charges()).containsExactly(tou);
    }

    /** A day selector only survives if it is written; ALL everywhere would hide the bug. */
    @Test
    void roundTripsBandsThatDifferBetweenWeekdaysAndWeekends() {
        var tou = new TimeOfUse(List.of(
                Band.parse("00:00", "16:00", DaySelector.ALL, new BigDecimal("17.70")),
                Band.parse("16:00", "21:00", DaySelector.WEEKDAYS, new BigDecimal("36.20")),
                Band.parse("16:00", "21:00", DaySelector.WEEKENDS, new BigDecimal("22.10")),
                Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("17.70"))));

        assertThat(roundTrip(planWith(tou)).charges()).containsExactly(tou);
    }

    /** The free-window plans: a band whose cheap rate holds only up to a daily allowance. */
    @Test
    void roundTripsACappedBand() {
        var tou = new TimeOfUse(List.of(
                Band.parse("00:00", "11:00", DaySelector.ALL, new BigDecimal("22.60")),
                Band.parseTiered("11:00", "15:00", DaySelector.ALL, ResetPeriod.DAILY,
                        List.of(new Tier(new BigDecimal("50"), BigDecimal.ZERO),
                                new Tier(null, new BigDecimal("22.60")))),
                Band.parse("15:00", "24:00", DaySelector.ALL, new BigDecimal("22.60"))));

        assertThat(roundTrip(planWith(tou)).charges()).containsExactly(tou);
    }

    @Test
    void roundTripsTieredBlocks() {
        var tiered = new Tiered(ResetPeriod.QUARTERLY, List.of(
                new Tier(new BigDecimal("1020"), new BigDecimal("28.60")),
                new Tier(null, new BigDecimal("31.90"))));

        assertThat(roundTrip(planWith(tiered)).charges()).containsExactly(tiered);
    }

    @Test
    void roundTripsDemand() {
        var demand = new Demand(900, 1260, DaySelector.WEEKDAYS, ResetPeriod.MONTHLY,
                new BigDecimal("18.55"));

        assertThat(roundTripped(demand)).containsExactly(USAGE, demand);
    }

    @Test
    void roundTripsControlledLoadWithAndWithoutAWindow() {
        var windowed = new ControlledLoad(new BigDecimal("19.80"), 1380, 360);
        assertThat(roundTripped(windowed)).containsExactly(USAGE, windowed);

        var allDay = ControlledLoad.anyTime(new BigDecimal("19.80"));
        assertThat(roundTripped(allDay)).containsExactly(USAGE, allDay);
    }

    @Test
    void roundTripsSolarFeedIn() {
        var feedIn = new SolarFeedIn(new BigDecimal("3.30"));
        assertThat(roundTripped(feedIn)).containsExactly(USAGE, feedIn);
    }

    /** A flat credit stays one rate on the page, not a band nobody wrote. */
    @Test
    void writesAFlatCreditAsASingleRate() {
        var written = PlanYamlWriter.write(
                planWith(USAGE, new SolarFeedIn(new BigDecimal("3.30"))), origin());

        assertThat(written).contains("type: solarFeedIn").doesNotContain("bands");
    }

    /**
     * Sixty-six published plans pay for exports by time of day. A writer that flattened them
     * would save a plan that earns a household several times what the file then says.
     */
    @Test
    void roundTripsAFeedInThatChangesAcrossTheDay() {
        var feedIn = new SolarFeedIn(List.of(
                Band.parse("00:00", "16:00", DaySelector.ALL, new BigDecimal("0.11")),
                Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("3.30")),
                Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("0.11"))));

        assertThat(roundTripped(feedIn)).containsExactly(USAGE, feedIn);
    }

    @Test
    void roundTripsAFeedInThatDiffersBetweenWeekdaysAndWeekends() {
        var feedIn = new SolarFeedIn(List.of(
                Band.parse("00:00", "16:00", DaySelector.ALL, new BigDecimal("1.00")),
                Band.parse("16:00", "24:00", DaySelector.WEEKDAYS, new BigDecimal("8.00")),
                Band.parse("16:00", "24:00", DaySelector.WEEKENDS, new BigDecimal("2.00"))));

        assertThat(roundTripped(feedIn)).containsExactly(USAGE, feedIn);
    }

    /** A discount is a proportion, so GST never applies to it however the plan is written. */
    @Test
    void roundTripsAConditionalDiscount() {
        var discount = new Discount("Pay on time", DiscountBasis.PERCENTAGE,
                DiscountScope.USAGE, new BigDecimal("15"), "pay every bill by its due date");

        assertThat(roundTripped(discount)).containsExactly(USAGE, discount);
    }

    @Test
    void roundTripsAnUnconditionalDiscount() {
        var discount = new Discount("Welcome credit", DiscountBasis.FIXED,
                DiscountScope.TOTAL, new BigDecimal("10000"), null);

        assertThat(roundTripped(discount)).containsExactly(USAGE, discount);
    }

    /** A mandatory fee is part of the tariff, so it has to survive being saved. */
    @Test
    void roundTripsAMembershipFee() {
        var membership = Membership.perYear("Membership fee", new BigDecimal("300.00"));

        assertThat(roundTripped(membership)).containsExactly(USAGE, membership);
    }

    @Test
    void roundTripsValidityDates() {
        var plan = new Plan("vdo-ausnet", "Victorian Default Offer", "Powershop",
                DistributionZone.AUSNET, List.of(USAGE), true,
                LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30));

        var back = PlanYamlLoader.load(PlanYamlWriter.write(plan, origin()));

        assertThat(back.validFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(back.validTo()).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    void omitsValidityWhenThePlanIsUnbounded() {
        var written = PlanYamlWriter.write(planWith(USAGE), origin());

        assertThat(written).doesNotContain("validFrom").doesNotContain("validTo");
    }

    /**
     * The marker that makes writing into the user's plans directory safe: a file carrying an
     * origin was written from the register and may be replaced, and a file without one was
     * written by hand and must not be.
     */
    @Test
    void recordsWhereThePlanCameFrom() {
        var written = PlanYamlWriter.write(planWith(USAGE), origin());

        assertThat(PlanOrigin.readFrom(written))
                .contains(new PlanOrigin("GLO785330MR@VEC", LocalDate.of(2026, 8, 21)));
    }

    @Test
    void readsNoOriginFromAHandWrittenFile() {
        assertThat(PlanOrigin.readFrom("""
                id: agl-flat-current
                name: AGL flat rate, anytime
                retailer: AGL
                zone: AUSNET
                gstInclusive: true
                charges:
                  - type: flatRate
                    cents: 31.98
                """)).isEmpty();
    }

    /** Names carry colons and hashes; a quoted scalar keeps them from becoming YAML syntax. */
    @Test
    void quotesNamesThatWouldOtherwiseBeYamlSyntax() {
        var plan = new Plan("odd@VEC", "Go Variable: New customers #2 - 20% off",
                "Origin Energy", DistributionZone.AUSNET, List.of(USAGE), true, null, null);

        assertThat(roundTrip(plan).name()).isEqualTo("Go Variable: New customers #2 - 20% off");
    }
}
