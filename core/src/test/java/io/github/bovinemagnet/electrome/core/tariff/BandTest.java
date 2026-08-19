package io.github.bovinemagnet.electrome.core.tariff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.Quality;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BandTest {

    private static final BigDecimal PEAK = new BigDecimal("49.54");

    private static IntervalReading at(LocalDate date, int hour, int minute) {
        return new IntervalReading(
                date.atTime(hour, minute), Duration.ofMinutes(30), BigDecimal.ONE, Quality.ACTUAL);
    }

    @Test
    void parsesClockTimesToMinuteOfDay() {
        assertThat(Band.parseMinuteOfDay("00:00")).isZero();
        assertThat(Band.parseMinuteOfDay("06:00")).isEqualTo(360);
        assertThat(Band.parseMinuteOfDay("16:00")).isEqualTo(960);
        assertThat(Band.parseMinuteOfDay("23:30")).isEqualTo(1410);
    }

    @Test
    void acceptsTwentyFourHundredAsEndOfDaySentinel() {
        // LocalTime cannot represent 24:00, but tariffs are published using it.
        assertThat(Band.parseMinuteOfDay("24:00")).isEqualTo(1440);
    }

    @Test
    void rejectsTimesBeyondEndOfDay() {
        assertThatThrownBy(() -> Band.parseMinuteOfDay("24:30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24:30");
        assertThatThrownBy(() -> Band.parseMinuteOfDay("25:00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Band.parseMinuteOfDay("noon"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandsAreHalfOpen() {
        var peak = Band.parse("16:00", "21:00", DaySelector.ALL, PEAK);
        assertThat(peak.matchesTime(959)).isFalse();
        assertThat(peak.matchesTime(960)).isTrue(); // 16:00 inclusive
        assertThat(peak.matchesTime(1259)).isTrue();
        assertThat(peak.matchesTime(1260)).isFalse(); // 21:00 exclusive
    }

    @Test
    void endOfDayBandCoversFinalInterval() {
        var evening = Band.parse("21:00", "24:00", DaySelector.ALL, PEAK);
        assertThat(evening.matchesTime(1260)).isTrue();
        assertThat(evening.matchesTime(1439)).isTrue(); // the 23:30 interval
        assertThat(evening.wrapsMidnight()).isFalse();
    }

    @Test
    void bandWrappingMidnightMatchesBothSides() {
        // The CDR feed also encodes this as two bands meeting at 00:00; both must work.
        var overnight = Band.parse("21:00", "11:00", DaySelector.ALL, PEAK);
        assertThat(overnight.wrapsMidnight()).isTrue();
        assertThat(overnight.matchesTime(1260)).isTrue(); // 21:00
        assertThat(overnight.matchesTime(0)).isTrue(); // midnight
        assertThat(overnight.matchesTime(659)).isTrue(); // 10:59
        assertThat(overnight.matchesTime(660)).isFalse(); // 11:00
        assertThat(overnight.matchesTime(720)).isFalse(); // noon
    }

    @Test
    void daySelectorAllMatchesEveryDay() {
        var saturday = LocalDate.of(2025, 8, 23);
        var monday = LocalDate.of(2025, 8, 25);
        assertThat(DaySelector.ALL.matches(saturday, HolidayCalendar.none())).isTrue();
        assertThat(DaySelector.ALL.matches(monday, HolidayCalendar.none())).isTrue();
    }

    @Test
    void daySelectorSplitsWeekdaysFromWeekends() {
        var saturday = LocalDate.of(2025, 8, 23);
        var sunday = LocalDate.of(2025, 8, 24);
        var monday = LocalDate.of(2025, 8, 25);
        var none = HolidayCalendar.none();
        assertThat(DaySelector.WEEKDAYS.matches(monday, none)).isTrue();
        assertThat(DaySelector.WEEKDAYS.matches(saturday, none)).isFalse();
        assertThat(DaySelector.WEEKENDS.matches(saturday, none)).isTrue();
        assertThat(DaySelector.WEEKENDS.matches(sunday, none)).isTrue();
        assertThat(DaySelector.WEEKENDS.matches(monday, none)).isFalse();
    }

    @Test
    void businessDaysExcludeHolidays() {
        var anzacDay = LocalDate.of(2025, 4, 25); // a Friday
        HolidayCalendar vic = date -> date.equals(anzacDay);
        assertThat(DaySelector.WEEKDAYS.matches(anzacDay, vic)).isTrue();
        assertThat(DaySelector.BUSINESS_DAYS.matches(anzacDay, vic)).isFalse();
        assertThat(DaySelector.BUSINESS_DAYS.matches(LocalDate.of(2025, 4, 24), vic)).isTrue();
    }

    @Test
    void matchesCombinesTimeAndDay() {
        var weekdayPeak = Band.parse("16:00", "21:00", DaySelector.WEEKDAYS, PEAK);
        var monday = LocalDate.of(2025, 8, 25);
        var saturday = LocalDate.of(2025, 8, 23);
        assertThat(weekdayPeak.matches(at(monday, 17, 0), HolidayCalendar.none())).isTrue();
        assertThat(weekdayPeak.matches(at(saturday, 17, 0), HolidayCalendar.none())).isFalse();
        assertThat(weekdayPeak.matches(at(monday, 10, 0), HolidayCalendar.none())).isFalse();
    }

    @Test
    void rejectsNegativeRate() {
        assertThatThrownBy(
                        () -> Band.parse("16:00", "21:00", DaySelector.ALL, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetPeriodKeysGroupCorrectly() {
        var jan = LocalDate.of(2025, 1, 15);
        var feb = LocalDate.of(2025, 2, 15);
        var mar = LocalDate.of(2025, 3, 15);
        assertThat(ResetPeriod.DAILY.keyFor(jan)).isNotEqualTo(ResetPeriod.DAILY.keyFor(feb));
        assertThat(ResetPeriod.MONTHLY.keyFor(jan)).isNotEqualTo(ResetPeriod.MONTHLY.keyFor(feb));
        assertThat(ResetPeriod.QUARTERLY.keyFor(jan)).isEqualTo(ResetPeriod.QUARTERLY.keyFor(mar));
        assertThat(ResetPeriod.QUARTERLY.keyFor(jan))
                .isNotEqualTo(ResetPeriod.QUARTERLY.keyFor(LocalDate.of(2025, 4, 1)));
        assertThat(ResetPeriod.ANNUAL.keyFor(jan)).isEqualTo(ResetPeriod.ANNUAL.keyFor(mar));
    }
}
