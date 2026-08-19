package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class CdrPlanMapperTest {

    private static String fixture(String name) throws IOException {
        return Files.readString(
                Path.of("src/test/resources/" + name), StandardCharsets.UTF_8);
    }

    private static TimeOfUse tou(Plan plan) {
        return (TimeOfUse) plan.charges().stream()
                .filter(TimeOfUse.class::isInstance).findFirst().orElseThrow();
    }

    private static DailySupply supply(Plan plan) {
        return (DailySupply) plan.charges().stream()
                .filter(DailySupply.class::isInstance).findFirst().orElseThrow();
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------- the GST conversion, pinned against the regulator ----------

    @Test
    void aStandingOfferGrossesUpToTheVictorianDefaultOfferExactly() throws IOException {
        // The single most important assertion here. Published rates exclude GST; the domain
        // holds cents inclusive. If this factor is wrong, every harvested plan is uniformly
        // ten percent out and still looks entirely plausible.
        var plan = CdrPlanMapper.map(fixture("plan-detail-standing.json"), DistributionZone.AUSNET);

        assertThat(round(supply(plan).centsPerDay())).isEqualByComparingTo("128.24");

        var rates = tou(plan).bands().stream().map(Band::centsPerKWh).map(CdrPlanMapperTest::round)
                .distinct().sorted().toList();
        // VDO 2026-27, AusNet, three-period time of use.
        assertThat(rates).containsExactly(
                new BigDecimal("17.59"),   // 11am-4pm solar soak
                new BigDecimal("22.60"),   // off-peak
                new BigDecimal("47.64"));  // 4-9pm peak
    }

    // ---------- a real market plan ----------

    @Test
    void mapsIdentityFieldsFromTheLiveFixture() throws IOException {
        var plan = CdrPlanMapper.map(fixture("plan-detail-tou.json"), DistributionZone.AUSNET);
        assertThat(plan.id()).endsWith("@VEC");
        assertThat(plan.retailer()).isEqualTo("Origin Energy");
        assertThat(plan.zone()).isEqualTo(DistributionZone.AUSNET);
        assertThat(plan.gstInclusive()).isTrue();
        assertThat(plan.name()).isNotBlank();
    }

    @Test
    void expandsTheSplitAtMidnightEncodingIntoSeparateBands() throws IOException {
        // The fixture publishes its off-peak rate as 21:00-00:00 plus 00:00-17:00 on one rate
        // entry. Two windows, one price, and together with the peak they tile the day.
        var bands = tou(CdrPlanMapper.map(
                fixture("plan-detail-tou.json"), DistributionZone.AUSNET)).bands();
        assertThat(bands).hasSize(3);
        assertThat(bands).extracting(Band::describe)
                .contains("21:00-24:00", "00:00-17:00", "17:00-21:00");
    }

    @Test
    void theTypeFieldIsNeverConsulted() throws IOException {
        // Both bands in the fixture are labelled SHOULDER, including the dearest. If the
        // mapper trusted the label it could not tell them apart at all.
        var raw = fixture("plan-detail-tou.json");
        assertThat(raw).contains("SHOULDER").doesNotContain("\"type\":\"PEAK\"");

        var bands = tou(CdrPlanMapper.map(raw, DistributionZone.AUSNET)).bands();
        var dearest = bands.stream().max(Comparator.comparing(Band::centsPerKWh)).orElseThrow();
        assertThat(dearest.describe()).isEqualTo("17:00-21:00");
        assertThat(round(dearest.centsPerKWh())).isEqualByComparingTo("51.70");
    }

    @Test
    void everyBandAppliesOnAllSevenDays() throws IOException {
        assertThat(tou(CdrPlanMapper.map(
                        fixture("plan-detail-tou.json"), DistributionZone.AUSNET)).bands())
                .allSatisfy(b -> assertThat(b.days()).isEqualTo(DaySelector.ALL));
    }

    @Test
    void aNullFeedInBlockIsNormalRatherThanAFault() throws IOException {
        // The live fixture publishes solarFeedInTariff as null.
        assertThat(CdrPlanMapper.map(fixture("plan-detail-tou.json"), DistributionZone.AUSNET)
                        .charges())
                .noneMatch(SolarFeedIn.class::isInstance);
    }

    // ---------- synthetic shapes ----------

    @Test
    void mapsSolarFeedInWhenPresent() {
        var json = """
                {"data": {"planId": "X", "displayName": "P", "brandName": "B",
                  "electricityContract": {"pricingModel": "SINGLE_RATE",
                    "tariffPeriod": [{"rateBlockUType": "singleRate",
                      "dailySupplyCharge": "1.0",
                      "singleRate": {"rates": [{"unitPrice": "0.25"}]}}],
                    "solarFeedInTariff": [{"scheme": "CURRENT", "tariffUType": "singleTariff",
                      "singleTariff": {"rates": [{"unitPrice": "0.01"}]}}]}}}
                """;
        var feedIn = (SolarFeedIn) CdrPlanMapper.map(json, DistributionZone.AUSNET).charges()
                .stream().filter(SolarFeedIn.class::isInstance).findFirst().orElseThrow();
        assertThat(feedIn.centsPerKWh()).isEqualByComparingTo("1.10");
    }

    @Test
    void mapsASingleRateContractToFlatRate() {
        var json = """
                {"data": {"planId": "X", "displayName": "Flat", "brandName": "B",
                  "electricityContract": {"pricingModel": "SINGLE_RATE",
                    "tariffPeriod": [{"rateBlockUType": "singleRate",
                      "dailySupplyCharge": "1.16581818",
                      "singleRate": {"rates": [{"unitPrice": "0.29072727"}]}}]}}}
                """;
        var plan = CdrPlanMapper.map(json, DistributionZone.AUSNET);
        var flat = (FlatRate) plan.charges().stream()
                .filter(FlatRate.class::isInstance).findFirst().orElseThrow();
        // The VDO flat schedule: 31.98c and 128.24c inclusive.
        assertThat(round(flat.centsPerKWh())).isEqualByComparingTo("31.98");
        assertThat(round(supply(plan).centsPerDay())).isEqualByComparingTo("128.24");
    }

    @Test
    void mapsAQuarterlyBlockTariff() {
        var json = """
                {"data": {"planId": "X", "displayName": "Block", "brandName": "B",
                  "electricityContract": {"pricingModel": "SINGLE_RATE",
                    "tariffPeriod": [{"rateBlockUType": "singleRate",
                      "dailySupplyCharge": "1.0",
                      "singleRate": {"rates": [
                        {"unitPrice": "0.29", "volume": 1020, "period": "P3M"},
                        {"unitPrice": "0.31"}]}}]}}}
                """;
        var tiered = (Tiered) CdrPlanMapper.map(json, DistributionZone.AUSNET).charges().stream()
                .filter(Tiered.class::isInstance).findFirst().orElseThrow();
        assertThat(tiered.reset()).isEqualTo(ResetPeriod.QUARTERLY);
        assertThat(tiered.tiers()).hasSize(2);
        assertThat(tiered.tiers().get(0).thresholdKWh()).isEqualByComparingTo("1020");
        assertThat(tiered.tiers().get(1).unbounded()).isTrue();
    }

    @Test
    void mapsADailyBlockTariff() {
        var json = """
                {"data": {"planId": "X", "displayName": "Block", "brandName": "B",
                  "electricityContract": {"pricingModel": "SINGLE_RATE",
                    "tariffPeriod": [{"rateBlockUType": "singleRate",
                      "dailySupplyCharge": "1.0",
                      "singleRate": {"rates": [
                        {"unitPrice": "0.29", "volume": 11.178, "period": "P1D"},
                        {"unitPrice": "0.31"}]}}]}}}
                """;
        var tiered = (Tiered) CdrPlanMapper.map(json, DistributionZone.AUSNET).charges().stream()
                .filter(Tiered.class::isInstance).findFirst().orElseThrow();
        assertThat(tiered.reset()).isEqualTo(ResetPeriod.DAILY);
    }

    // ---------- rejection ----------

    @Test
    void rejectsAPlanWithNoElectricityContract() {
        var json = "{\"data\": {\"planId\": \"X\", \"displayName\": \"Gas\", \"brandName\": \"B\"}}";
        assertThatThrownBy(() -> CdrPlanMapper.map(json, DistributionZone.AUSNET))
                .isInstanceOf(UnmappablePlanException.class)
                .hasMessageContaining("electricityContract");
    }

    @Test
    void rejectsAPlanWhoseBandsDoNotCoverTheDay() {
        var json = """
                {"data": {"planId": "Gappy", "displayName": "Gappy", "brandName": "B",
                  "electricityContract": {"pricingModel": "TIME_OF_USE",
                    "tariffPeriod": [{"rateBlockUType": "timeOfUseRates",
                      "dailySupplyCharge": "1.0",
                      "timeOfUseRates": [{"type": "PEAK", "rates": [{"unitPrice": "0.5"}],
                        "timeOfUse": [{"days": ["MON","TUE","WED","THU","FRI","SAT","SUN"],
                                       "startTime": "16:00", "endTime": "21:00"}]}]}]}}}
                """;
        assertThatThrownBy(() -> CdrPlanMapper.map(json, DistributionZone.AUSNET))
                .isInstanceOf(UnmappablePlanException.class)
                .hasMessageContaining("Gappy");
    }

    @Test
    void rejectsAnUnrepresentableDaySelection() {
        var json = """
                {"data": {"planId": "Odd", "displayName": "Odd", "brandName": "B",
                  "electricityContract": {"pricingModel": "TIME_OF_USE",
                    "tariffPeriod": [{"rateBlockUType": "timeOfUseRates",
                      "dailySupplyCharge": "1.0",
                      "timeOfUseRates": [{"rates": [{"unitPrice": "0.5"}],
                        "timeOfUse": [{"days": ["WED"], "startTime": "00:00",
                                       "endTime": "24:00"}]}]}]}}}
                """;
        assertThatThrownBy(() -> CdrPlanMapper.map(json, DistributionZone.AUSNET))
                .isInstanceOf(UnmappablePlanException.class)
                .hasMessageContaining("day");
    }

    @Test
    void rejectsAnUnsupportedRateBlock() {
        var json = """
                {"data": {"planId": "Quota", "displayName": "Q", "brandName": "B",
                  "electricityContract": {"pricingModel": "QUOTA",
                    "tariffPeriod": [{"rateBlockUType": "somethingElse",
                      "dailySupplyCharge": "1.0"}]}}}
                """;
        assertThatThrownBy(() -> CdrPlanMapper.map(json, DistributionZone.AUSNET))
                .isInstanceOf(UnmappablePlanException.class)
                .hasMessageContaining("rateBlockUType");
    }

    @Test
    void rejectsMalformedJsonWithoutLeakingAStackTrace() {
        assertThatThrownBy(() -> CdrPlanMapper.map("not json at all", DistributionZone.AUSNET))
                .isInstanceOf(UnmappablePlanException.class);
    }
}
