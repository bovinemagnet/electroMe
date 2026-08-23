package io.github.bovinemagnet.electrome.market.cdr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Feed-in credits that change across the day.
 *
 * <p>Sixty-six plans published for the AusNet network pay for exports by time of day and carry
 * no {@code singleTariff} block at all. The mapper read only that block, so those plans were
 * mapped with no feed-in charge — not refused, which would have been visible, but silently
 * priced as though they paid nothing for exports. That is the failure this fixture pins.
 */
class TimeVaryingFeedInTest {

    private static SolarFeedIn feedInOf(String fixture) throws IOException {
        var json = Files.readString(Path.of("src/test/resources", fixture));
        return CdrPlanMapper.map(json, DistributionZone.AUSNET).charges().stream()
                .filter(SolarFeedIn.class::isInstance)
                .map(SolarFeedIn.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no feed-in mapped from " + fixture));
    }

    @Test
    void mapsEveryPublishedWindowIntoItsOwnBand() throws IOException {
        var feedIn = feedInOf("plan-detail-time-varying-feed-in.json");

        // Five published windows: three at the shoulder rate, one off peak, one peak.
        assertThat(feedIn.bands()).hasSize(5);
        assertThat(feedIn.varies()).isTrue();
        assertThat(feedIn.flat()).isFalse();
    }

    /** Published dollars exclusive of GST become cents inclusive, as everywhere else. */
    @Test
    void grossesUpEachRate() throws IOException {
        var feedIn = feedInOf("plan-detail-time-varying-feed-in.json");

        assertThat(feedIn.bestRate()).isEqualByComparingTo("11.00");
        assertThat(feedIn.lowestRate()).isEqualByComparingTo("1.65");
    }

    @Test
    void putsEachRateInTheWindowThatPublishedIt() throws IOException {
        var feedIn = feedInOf("plan-detail-time-varying-feed-in.json");

        assertThat(rateBetween(feedIn, 16 * 60, 21 * 60)).isEqualByComparingTo("11.00");
        assertThat(rateBetween(feedIn, 10 * 60, 14 * 60)).isEqualByComparingTo("1.65");
        assertThat(rateBetween(feedIn, 0, 10 * 60)).isEqualByComparingTo("3.85");
        assertThat(rateBetween(feedIn, 21 * 60, Band.MINUTES_PER_DAY))
                .isEqualByComparingTo("3.85");
    }

    /**
     * The register writes the end of the day as "00:00", which is not the same as the start.
     * Read literally it produces a zero-length band and leaves the evening uncredited.
     */
    @Test
    void readsMidnightAsTheEndOfTheDayRatherThanTheStart() throws IOException {
        var feedIn = feedInOf("plan-detail-time-varying-feed-in.json");

        assertThat(feedIn.bands())
                .anySatisfy(band -> {
                    assertThat(band.fromMinuteOfDay()).isEqualTo(21 * 60);
                    assertThat(band.toMinuteOfDay()).isEqualTo(Band.MINUTES_PER_DAY);
                });
    }

    /** The plan has to survive validation, which requires the bands to cover the whole week. */
    @Test
    void producesAPlanThatValidates() throws IOException {
        var json = Files.readString(
                Path.of("src/test/resources", "plan-detail-time-varying-feed-in.json"));

        // map() validates before returning, so reaching here at all is the assertion.
        var plan = CdrPlanMapper.map(json, DistributionZone.AUSNET);

        assertThat(plan.charges()).hasAtLeastOneElementOfType(SolarFeedIn.class);
    }

    /** A plan publishing one flat credit still maps to the single-band form. */
    @Test
    void leavesAFlatCreditAsOneBand() throws IOException {
        var feedIn = feedInOf("plan-detail-standing.json");

        assertThat(feedIn.flat()).isTrue();
        assertThat(feedIn.varies()).isFalse();
    }

    private static java.math.BigDecimal rateBetween(SolarFeedIn feedIn, int from, int to) {
        return feedIn.bands().stream()
                .filter(band -> band.fromMinuteOfDay() == from && band.toMinuteOfDay() == to)
                .map(Band::centsPerKWh)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no band " + from + "-" + to));
    }
}
