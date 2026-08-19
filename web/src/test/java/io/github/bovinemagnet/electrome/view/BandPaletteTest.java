package io.github.bovinemagnet.electrome.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.cost.ChargeKind;
import io.github.bovinemagnet.electrome.core.cost.ChargeLine;
import io.github.bovinemagnet.electrome.core.cost.Unit;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BandPaletteTest {

    private static Band band(String from, String to, String cents) {
        return Band.parse(from, to, DaySelector.ALL, new BigDecimal(cents));
    }

    private static Plan plan(Band... bands) {
        return new Plan("p", "P", "R", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal("100")), new TimeOfUse(List.of(bands))),
                true, null, null);
    }

    private static ChargeLine usage(Band b) {
        return new ChargeLine("Usage " + b.describe(), ChargeKind.USAGE, BigDecimal.TEN,
                Unit.KWH, b.centsPerKWh(), BigDecimal.ONE);
    }

    @Test
    void coloursTheCurrentTariffAsDesigned() {
        var overnight = band("00:00", "06:00", "4.99");
        var morning = band("06:00", "11:00", "24.77");
        var midday = band("11:00", "16:00", "24.77");
        var peak = band("16:00", "21:00", "49.54");
        var evening = band("21:00", "24:00", "24.77");
        var p = plan(overnight, morning, midday, peak, evening);

        assertThat(BandPalette.tokenFor(p, usage(overnight))).isEqualTo(BandPalette.OFFPEAK);
        assertThat(BandPalette.tokenFor(p, usage(morning))).isEqualTo(BandPalette.SHOULDER);
        assertThat(BandPalette.tokenFor(p, usage(midday))).isEqualTo(BandPalette.MIDDAY);
        assertThat(BandPalette.tokenFor(p, usage(peak))).isEqualTo(BandPalette.PEAK);
        assertThat(BandPalette.tokenFor(p, usage(evening))).isEqualTo(BandPalette.SHOULDER);
    }

    @Test
    void theDaytimeWindowStaysAmberEvenWhenItIsTheCheapestRate() {
        // Under the Victorian Default Offer the 11am-4pm solar window IS the cheapest rate.
        // Colouring it green would break the correspondence with the same window elsewhere.
        var peak = band("16:00", "21:00", "47.64");
        var solar = band("11:00", "16:00", "17.59");
        var rest = band("21:00", "11:00", "22.60");
        var p = plan(peak, solar, rest);

        assertThat(BandPalette.tokenFor(p, usage(solar))).isEqualTo(BandPalette.MIDDAY);
        assertThat(BandPalette.tokenFor(p, usage(peak))).isEqualTo(BandPalette.PEAK);
        assertThat(BandPalette.tokenFor(p, usage(rest))).isEqualTo(BandPalette.SHOULDER);
    }

    @Test
    void supplyAndCreditsHaveTheirOwnColours() {
        var p = plan(band("00:00", "24:00", "25"));
        var supply = new ChargeLine("Daily supply charge", ChargeKind.SUPPLY, BigDecimal.TEN,
                Unit.DAY, new BigDecimal("100"), BigDecimal.ONE);
        var credit = new ChargeLine("Solar feed-in credit", ChargeKind.FEED_IN, BigDecimal.TEN,
                Unit.KWH, new BigDecimal("3.3"), BigDecimal.ONE.negate());
        assertThat(BandPalette.tokenFor(p, supply)).isEqualTo(BandPalette.SUPPLY);
        assertThat(BandPalette.tokenFor(p, credit)).isEqualTo(BandPalette.CREDIT);
    }

    @Test
    void aFlatPlanHasNoBandsAndNoDearestBand() {
        var flat = new Plan("f", "F", "R", DistributionZone.AUSNET,
                List.of(new DailySupply(new BigDecimal("100")),
                        new io.github.bovinemagnet.electrome.core.tariff.FlatRate(
                                new BigDecimal("25"))),
                true, null, null);
        assertThat(BandPalette.timeOfUseBands(flat)).isEmpty();
        assertThat(BandPalette.dearestBand(flat)).isEmpty();
    }

    @Test
    void aNullPlanYieldsNoBandsRatherThanThrowing() {
        assertThat(BandPalette.timeOfUseBands(null)).isEmpty();
    }

    @Test
    void referencesAreEmittedInTheRightFormForEachConsumer() {
        assertThat(BandPalette.cssVar(BandPalette.PEAK)).isEqualTo("var(--peak)");
        assertThat(BandPalette.chartRef(BandPalette.PEAK)).isEqualTo("@peak");
    }
}
