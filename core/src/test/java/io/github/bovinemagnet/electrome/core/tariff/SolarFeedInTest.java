package io.github.bovinemagnet.electrome.core.tariff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A feed-in credit that changes across the day.
 *
 * <p>Retailers increasingly pay for exports the way they charge for imports: a few cents in the
 * evening peak when the grid wants the energy, and next to nothing at midday when it does not.
 * Sixty-six of the plans published for AusNet do exactly this, and the model previously had no
 * way to hold it, so the credit was dropped and those plans priced as though they paid nothing
 * for exports at all.
 *
 * <p>The flat case is one band covering the whole day, for the same reason a single-rate
 * {@link Band} is one unbounded tier: one code path that cannot disagree with itself.
 */
class SolarFeedInTest {

    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    private static IntervalReading at(int hour, int minute) {
        return on(4, hour, minute);
    }

    private static IntervalReading on(int dayOfMarch, int hour, int minute) {
        return new IntervalReading(LocalDateTime.of(2026, 3, dayOfMarch, hour, minute),
                HALF_HOUR, new BigDecimal("1.0"), Quality.ACTUAL);
    }

    /** GloBird pays three cents in the evening peak and a rounding error the rest of the day. */
    private static SolarFeedIn eveningOnly() {
        return new SolarFeedIn(List.of(
                Band.parse("00:00", "16:00", DaySelector.ALL, new BigDecimal("0.11")),
                Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("3.30")),
                Band.parse("21:00", "24:00", DaySelector.ALL, new BigDecimal("0.11"))));
    }

    @Test
    void aFlatCreditIsOneBandCoveringTheWholeDay() {
        var flat = new SolarFeedIn(new BigDecimal("3.30"));

        assertThat(flat.flat()).isTrue();
        assertThat(flat.varies()).isFalse();
        assertThat(flat.bands()).hasSize(1);
        assertThat(flat.bands().get(0).fromMinuteOfDay()).isZero();
        assertThat(flat.bands().get(0).toMinuteOfDay()).isEqualTo(Band.MINUTES_PER_DAY);
        assertThat(flat.bestRate()).isEqualByComparingTo("3.30");
        assertThat(flat.lowestRate()).isEqualByComparingTo("3.30");
    }

    @Test
    void readsTheRateApplyingAtEachHalfHour() {
        var feedIn = eveningOnly();
        var holidays = HolidayCalendar.none();

        assertThat(feedIn.rateAt(at(12, 0), holidays)).isEqualByComparingTo("0.11");
        assertThat(feedIn.rateAt(at(16, 0), holidays)).isEqualByComparingTo("3.30");
        assertThat(feedIn.rateAt(at(20, 30), holidays)).isEqualByComparingTo("3.30");
        assertThat(feedIn.rateAt(at(21, 0), holidays)).isEqualByComparingTo("0.11");
    }

    /** The number a retailer advertises is the best one, and it is not the first band. */
    @Test
    void reportsTheBestAndWorstRateItPays() {
        var feedIn = eveningOnly();

        assertThat(feedIn.bestRate()).isEqualByComparingTo("3.30");
        assertThat(feedIn.lowestRate()).isEqualByComparingTo("0.11");
        assertThat(feedIn.varies()).isTrue();
        assertThat(feedIn.flat()).isFalse();
    }

    @Test
    void rejectsAFeedInWithNoBands() {
        assertThatThrownBy(() -> new SolarFeedIn(List.<Band>of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one band");
    }

    @Test
    void rejectsANegativeCredit() {
        assertThatThrownBy(() -> new SolarFeedIn(new BigDecimal("-1.0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A capped export credit, which published plans do offer.
     *
     * <p>Flow Power's 4Free pays seventeen cents for the first fifteen kilowatt hours exported
     * between half past five and half past nine and two cents after, and CovaU's SolarMax does
     * the same at thirty. The best rate is the one that runs out, so reading only the first
     * band or only the last block would report the wrong headline either way.
     */
    @Test
    void holdsACappedCredit() {
        var feedIn = new SolarFeedIn(List.of(
                Band.parse("00:00", "17:30", DaySelector.ALL, BigDecimal.ZERO),
                Band.parseTiered("17:30", "21:30", DaySelector.ALL, ResetPeriod.DAILY,
                        List.of(new Tier(new BigDecimal("15"), new BigDecimal("18.70")),
                                new Tier(null, new BigDecimal("2.20")))),
                Band.parse("21:30", "24:00", DaySelector.ALL, BigDecimal.ZERO)));

        assertThat(feedIn.capped()).isTrue();
        assertThat(feedIn.varies()).isTrue();
        assertThat(feedIn.bestRate()).isEqualByComparingTo("18.70");
        assertThat(feedIn.lowestRate()).isEqualByComparingTo("0.00");
    }

    @Test
    void keepsWorkingForWeekdayAndWeekendSplits() {
        var feedIn = new SolarFeedIn(List.of(
                Band.parse("00:00", "16:00", DaySelector.ALL, new BigDecimal("1.00")),
                Band.parse("16:00", "24:00", DaySelector.WEEKDAYS, new BigDecimal("8.00")),
                Band.parse("16:00", "24:00", DaySelector.WEEKENDS, new BigDecimal("2.00"))));
        var holidays = HolidayCalendar.none();

        // 4 March 2026 is a Wednesday; the 7th is a Saturday.
        assertThat(feedIn.rateAt(at(18, 0), holidays)).isEqualByComparingTo("8.00");
        assertThat(feedIn.rateAt(on(7, 18, 0), holidays)).isEqualByComparingTo("2.00");
    }

    @Test
    void staysTheSameCharge() {
        assertThat(new SolarFeedIn(new BigDecimal("3.30")))
                .isEqualTo(new SolarFeedIn(new BigDecimal("3.30")));
        assertThat(new SolarFeedIn(new BigDecimal("3.30")).label()).isNotBlank();
    }
}
