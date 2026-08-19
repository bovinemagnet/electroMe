package io.github.bovinemagnet.electrome.core.tariff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChargeTest {

    @Test
    void everyChargeCarriesALabel() {
        List<Charge> charges = List.of(
                new DailySupply(new BigDecimal("123.20")),
                new FlatRate(new BigDecimal("31.98")),
                new TimeOfUse(List.of(Band.parse("00:00", "24:00", DaySelector.ALL, BigDecimal.ONE))),
                new Tiered(ResetPeriod.QUARTERLY, List.of(new Tier(null, BigDecimal.ONE))),
                new Demand(960, 1260, DaySelector.ALL, ResetPeriod.MONTHLY, new BigDecimal("20")),
                new SolarFeedIn(new BigDecimal("3.3")),
                new Discount("Pay on time", DiscountBasis.PERCENTAGE, DiscountScope.USAGE,
                        new BigDecimal("5"), true));
        assertThat(charges).allSatisfy(c -> assertThat(c.label()).isNotBlank());
    }

    @Test
    void timeOfUseRejectsEmptyBandList() {
        assertThatThrownBy(() -> new TimeOfUse(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one band");
    }

    @Test
    void timeOfUseBandListIsDefensivelyCopied() {
        var mutable = new java.util.ArrayList<Band>();
        mutable.add(Band.parse("00:00", "24:00", DaySelector.ALL, BigDecimal.ONE));
        var tou = new TimeOfUse(mutable);
        mutable.clear();
        assertThat(tou.bands()).hasSize(1);
    }

    @Test
    void tieredRequiresExactlyOneUnboundedTierAndItMustBeLast() {
        var ok = new Tiered(ResetPeriod.QUARTERLY, List.of(
                new Tier(new BigDecimal("1020"), new BigDecimal("31.98")),
                new Tier(null, new BigDecimal("31.98"))));
        assertThat(ok.tiers()).hasSize(2);

        assertThatThrownBy(() -> new Tiered(ResetPeriod.QUARTERLY, List.of(
                        new Tier(null, BigDecimal.ONE),
                        new Tier(new BigDecimal("100"), BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last");
    }

    @Test
    void tieredRejectsNonAscendingThresholds() {
        assertThatThrownBy(() -> new Tiered(ResetPeriod.QUARTERLY, List.of(
                        new Tier(new BigDecimal("500"), BigDecimal.ONE),
                        new Tier(new BigDecimal("100"), BigDecimal.ONE),
                        new Tier(null, BigDecimal.ONE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ascending");
    }

    @Test
    void percentageDiscountMustNotExceedOneHundred() {
        assertThatThrownBy(() -> new Discount("Silly", DiscountBasis.PERCENTAGE,
                        DiscountScope.TOTAL, new BigDecimal("101"), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void distributionZoneExposesTheExactCdrDistributorString() {
        assertThat(DistributionZone.AUSNET.cdrDistributorName())
                .isEqualTo("AusNet Services (electricity)");
        assertThat(DistributionZone.UNITED_ENERGY.cdrDistributorName()).isEqualTo("United Energy");
        assertThat(DistributionZone.CITIPOWER.cdrDistributorName()).isEqualTo("Citipower");
    }

    @Test
    void planRejectsEmptyCharges() {
        assertThatThrownBy(() -> new Plan("p", "P", "R", DistributionZone.AUSNET, List.of(),
                        true, LocalDate.of(2025, 1, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planAppliesOnDateRespectsValidityWindow() {
        var plan = new Plan("p", "P", "R", DistributionZone.AUSNET,
                List.of(new DailySupply(BigDecimal.TEN)), true,
                LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30));
        assertThat(plan.appliesOn(LocalDate.of(2026, 7, 1))).isTrue();
        assertThat(plan.appliesOn(LocalDate.of(2027, 6, 30))).isTrue();
        assertThat(plan.appliesOn(LocalDate.of(2026, 6, 30))).isFalse();
        assertThat(plan.appliesOn(LocalDate.of(2027, 7, 1))).isFalse();
    }

    @Test
    void planWithNoValidityBoundsAppliesAlways() {
        var plan = new Plan("p", "P", "R", DistributionZone.AUSNET,
                List.of(new DailySupply(BigDecimal.TEN)), true, null, null);
        assertThat(plan.appliesOn(LocalDate.of(1999, 1, 1))).isTrue();
        assertThat(plan.appliesOn(LocalDate.of(2099, 1, 1))).isTrue();
    }
}
