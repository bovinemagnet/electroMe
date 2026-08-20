package io.github.bovinemagnet.electrome.core.cost;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
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
 * Hot water on a separate, cheaper circuit.
 *
 * <p>Victorian households commonly have one. The tariff model had no such concept, and pricing
 * that energy as ordinary consumption misprices it by the whole gap between two tariffs —
 * precisely the error this software exists to avoid.
 */
class ControlledLoadTest {

    private static final LocalDate DAY = LocalDate.of(2025, 1, 1);
    private static final DateRange ONE_DAY = new DateRange(DAY, DAY);
    private static final CostingEngine ENGINE = new CostingEngine();

    /** 0.5 kWh in every half hour: 24 kWh across the day. */
    private static UsageSeries flatDay(String perSlot) {
        var readings = new ArrayList<IntervalReading>();
        for (int slot = 0; slot < 48; slot++) {
            readings.add(new IntervalReading(
                    LocalDateTime.of(DAY, java.time.LocalTime.MIDNIGHT).plusMinutes(slot * 30L),
                    Duration.ofMinutes(30), new BigDecimal(perSlot), Quality.ACTUAL));
        }
        return UsageSeries.of(readings);
    }

    /** Energy on the controlled circuit, overnight only. */
    private static UsageSeries overnightControlled(String perSlot) {
        var readings = new ArrayList<IntervalReading>();
        for (int slot = 0; slot < 12; slot++) {
            readings.add(new IntervalReading(
                    LocalDateTime.of(DAY, java.time.LocalTime.MIDNIGHT).plusMinutes(slot * 30L),
                    Duration.ofMinutes(30), new BigDecimal(perSlot), Quality.ACTUAL));
        }
        return UsageSeries.of(readings);
    }

    private static Plan plan(Charge... charges) {
        var all = new ArrayList<Charge>();
        all.add(new DailySupply(new BigDecimal("100.00")));
        all.addAll(List.of(charges));
        return new Plan("p", "Plan", "Retailer", DistributionZone.AUSNET, all, true, null, null);
    }

    private static BigDecimal lineCost(BillBreakdown bill, ChargeKind kind) {
        return bill.subtotal(kind);
    }

    // -----------------------------------------------------------------

    @Test
    void aThirdSeriesDefaultsToEmptySoExistingCallSitesAreUnchanged() {
        var usage = new UsageData(flatDay("0.5"), UsageSeries.empty());

        assertThat(usage.controlled().isEmpty()).isTrue();
        assertThat(UsageData.consumptionOnly(flatDay("0.5")).controlled().isEmpty()).isTrue();
    }

    @Test
    void controlledEnergyIsPricedAtTheControlledRate() {
        var usage = new UsageData(flatDay("0.5"), UsageSeries.empty(),
                overnightControlled("1.0"));
        // 12 slots x 1.0 kWh = 12 kWh at 12c
        var bill = ENGINE.cost(usage, plan(
                new FlatRate(new BigDecimal("30.00")),
                new ControlledLoad(new BigDecimal("12.00"), null, null)), ONE_DAY);

        assertThat(lineCost(bill, ChargeKind.CONTROLLED)).isEqualByComparingTo("1.44");
    }

    @Test
    void controlledEnergyIsExcludedFromTheOrdinaryUsageBands() {
        var withControlled = new UsageData(flatDay("0.5"), UsageSeries.empty(),
                overnightControlled("1.0"));
        var withoutControlled = UsageData.consumptionOnly(flatDay("0.5"));
        var tariff = plan(
                new FlatRate(new BigDecimal("30.00")),
                new ControlledLoad(new BigDecimal("12.00"), null, null));

        // The ordinary usage line must be identical either way: controlled energy is not
        // consumption, and counting it twice would overstate every bill.
        assertThat(lineCost(ENGINE.cost(withControlled, tariff, ONE_DAY), ChargeKind.USAGE))
                .isEqualByComparingTo(
                        lineCost(ENGINE.cost(withoutControlled, tariff, ONE_DAY),
                                ChargeKind.USAGE));
    }

    @Test
    void theControlledRateIsCheaperThanPricingTheSameEnergyAsConsumption() {
        // The whole reason the concept exists.
        var usage = new UsageData(flatDay("0.5"), UsageSeries.empty(),
                overnightControlled("1.0"));

        var withControlledTariff = ENGINE.cost(usage, plan(
                new FlatRate(new BigDecimal("30.00")),
                new ControlledLoad(new BigDecimal("12.00"), null, null)), ONE_DAY);
        var withoutControlledTariff = ENGINE.cost(usage,
                plan(new FlatRate(new BigDecimal("30.00"))), ONE_DAY);

        assertThat(withControlledTariff.totalRounded())
                .isLessThan(withoutControlledTariff.totalRounded());
    }

    @Test
    void aPlanWithNoControlledRateStillChargesForThatEnergy() {
        // A household with a controlled circuit still uses the energy. Leaving it unpriced
        // would make every plan without a controlled tariff look artificially cheap, which is
        // the same mispricing in mirror image.
        var usage = new UsageData(flatDay("0.5"), UsageSeries.empty(),
                overnightControlled("1.0"));
        var plain = plan(new FlatRate(new BigDecimal("30.00")));

        var withControlledEnergy = ENGINE.cost(usage, plain, ONE_DAY);
        var withoutControlledEnergy = ENGINE.cost(
                UsageData.consumptionOnly(flatDay("0.5")), plain, ONE_DAY);

        // 12 kWh at the ordinary 30c is $3.60 more.
        assertThat(withControlledEnergy.totalRounded()
                        .subtract(withoutControlledEnergy.totalRounded()))
                .isEqualByComparingTo("3.60");
    }

    @Test
    void aControlledWindowPricesOnlyTheEnergyInsideIt() {
        // Retailers energise the circuit for a stated window. Energy outside it is not on the
        // controlled tariff and must not be priced as though it were.
        var usage = new UsageData(flatDay("0.5"), UsageSeries.empty(), flatDay("1.0"));
        var bill = ENGINE.cost(usage, plan(
                new FlatRate(new BigDecimal("30.00")),
                new ControlledLoad(new BigDecimal("12.00"), 0, 6 * 60)), ONE_DAY);

        // 12 slots inside 00:00-06:00 at 1.0 kWh, priced at 12c.
        assertThat(lineCost(bill, ChargeKind.CONTROLLED)).isEqualByComparingTo("1.44");
        // The other 36 slots fall back to the ordinary rate rather than vanishing.
        assertThat(bill.totalKWh()).isEqualByComparingTo("60.0");
    }

    @Test
    void controlledEnergyDoesNotDisturbTimeOfUseBands() {
        var usage = new UsageData(flatDay("0.5"), UsageSeries.empty(),
                overnightControlled("1.0"));
        var tou = plan(
                new TimeOfUse(List.of(
                        new Band(0, 16 * 60, DaySelector.ALL, new BigDecimal("20.00")),
                        new Band(16 * 60, 24 * 60, DaySelector.ALL, new BigDecimal("50.00")))),
                new ControlledLoad(new BigDecimal("12.00"), null, null));

        var bill = ENGINE.cost(usage, tou, ONE_DAY);

        // Every band still prices only ordinary consumption: 0.5 kWh x 32 and x 16 slots.
        assertThat(bill.lines()).filteredOn(l -> l.label().equals("Usage 00:00-16:00"))
                .singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("16.0"));
        assertThat(bill.complete()).isTrue();
    }

    @Test
    void theBillLineIsLabelledAsControlledLoad() {
        var usage = new UsageData(flatDay("0.5"), UsageSeries.empty(),
                overnightControlled("1.0"));
        var bill = ENGINE.cost(usage, plan(
                new FlatRate(new BigDecimal("30.00")),
                new ControlledLoad(new BigDecimal("12.00"), null, null)), ONE_DAY);

        assertThat(bill.lines()).anySatisfy(line -> {
            assertThat(line.kind()).isEqualTo(ChargeKind.CONTROLLED);
            assertThat(line.label()).containsIgnoringCase("controlled");
            assertThat(line.unit()).isEqualTo(Unit.KWH);
        });
    }

    @Test
    void aWindowThatWrapsMidnightIsHandledLikeAnyOtherBand() {
        var usage = new UsageData(flatDay("0.5"), UsageSeries.empty(), flatDay("1.0"));
        // 22:00 to 06:00: four slots at the end of the day and twelve at the start.
        var bill = ENGINE.cost(usage, plan(
                new FlatRate(new BigDecimal("30.00")),
                new ControlledLoad(new BigDecimal("12.00"), 22 * 60, 6 * 60)), ONE_DAY);

        assertThat(bill.lines()).filteredOn(l -> l.kind() == ChargeKind.CONTROLLED)
                .singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("16.0"));
    }
}
