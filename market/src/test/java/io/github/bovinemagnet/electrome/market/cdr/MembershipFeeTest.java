package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Membership;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The one fee that belongs in the total.
 *
 * <p>Every other fee the register publishes is a fact about the household rather than about the
 * tariff: a paper bill fee, a card processing fee, a dishonour fee, a disconnection fee. Costing
 * those would charge a reader for behaviour they have not got. A membership is different — it is
 * the price of being on the plan at all — so leaving it out understates the plan by its full
 * amount for everybody, every year.
 *
 * <p>Across the whole AusNet register exactly one retailer charges one: Amber, three hundred
 * dollars a year, on a plan whose published unit price is illustrative anyway.
 */
class MembershipFeeTest {

    private static Optional<Membership> membershipOf(String fixture) throws IOException {
        var json = Files.readString(Path.of("src/test/resources", fixture));
        return CdrPlanMapper.map(json, DistributionZone.AUSNET).charges().stream()
                .filter(Membership.class::isInstance)
                .map(Membership.class::cast)
                .findFirst();
    }

    @Test
    void costsAMandatoryMembershipAsAChargePerDay() throws IOException {
        var membership = membershipOf("plan-detail-market-linked.json").orElseThrow();

        assertThat(membership.dollarsPerYear()).isEqualByComparingTo("300.00");
        assertThat(membership.name()).containsIgnoringCase("membership");
    }

    /**
     * Published fee amounts already include GST, unlike unit prices, so this one must not be
     * grossed up. Multiplying by 1.1 would bill the household $330 for a $300 membership.
     */
    @Test
    void doesNotGrossUpAFeeThatAlreadyIncludesGst() throws IOException {
        var membership = membershipOf("plan-detail-market-linked.json").orElseThrow();

        assertThat(membership.dollarsPerYear()).isEqualByComparingTo("300.00");
        assertThat(membership.centsPerDay())
                .isCloseTo(new BigDecimal("82.19"), org.assertj.core.data.Offset.offset(
                        new BigDecimal("0.01")));
    }

    /** A plan with no membership gains no charge, rather than one worth nothing. */
    @Test
    void addsNothingToAPlanWithoutOne() throws IOException {
        assertThat(membershipOf("plan-detail-tou.json")).isEmpty();
        assertThat(membershipOf("plan-detail-standing.json")).isEmpty();
    }

    /**
     * The published {@code term} is unreliable here: Amber record a yearly total under the term
     * FIXED and state the monthly figure only in the description. So the period is read from the
     * description and checked against the amount, and a pair that disagrees is not costed.
     */
    @Test
    void readsThePeriodFromTheDescriptionAndChecksItAgainstTheAmount() {
        assertThat(CdrPlanMapper.membershipPerYear("300.00",
                "Membership of $25 per month (inc. GST) to access these wholesale rates."))
                .isEqualByComparingTo("300.00");
    }

    @Test
    void takesAnExplicitYearlyFigureAtFaceValue() {
        assertThat(CdrPlanMapper.membershipPerYear("199.00", "Annual membership of $199."))
                .isEqualByComparingTo("199.00");
    }

    /**
     * A monthly figure that does not multiply up to the published amount means one of the two is
     * wrong, and there is no way to tell which. Not costing it leaves the plan understated and
     * visibly flagged, which beats overstating it by a number nobody published.
     */
    @Test
    void refusesToCostAFeeWhoseDescriptionContradictsItsAmount() {
        assertThat(CdrPlanMapper.membershipPerYear("300.00", "Membership of $99 per month."))
                .isNull();
    }

    @Test
    void refusesToCostAFeeThatStatesNoPeriodAtAll() {
        assertThat(CdrPlanMapper.membershipPerYear("300.00", "Membership fee.")).isNull();
        assertThat(CdrPlanMapper.membershipPerYear("300.00", "")).isNull();
    }

    @Test
    void ignoresAFeeWithNoAmount() {
        assertThat(CdrPlanMapper.membershipPerYear(null, "$25 per month")).isNull();
    }
}
