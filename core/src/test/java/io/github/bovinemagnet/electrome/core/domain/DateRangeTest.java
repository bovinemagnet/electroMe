package io.github.bovinemagnet.electrome.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateRangeTest {

    @Test
    void countsDaysInclusively() {
        var range = new DateRange(LocalDate.of(2025, 8, 19), LocalDate.of(2026, 8, 18));
        assertThat(range.days()).isEqualTo(365);
    }

    @Test
    void singleDayRangeIsOneDay() {
        var day = LocalDate.of(2025, 1, 1);
        assertThat(new DateRange(day, day).days()).isEqualTo(1);
    }

    @Test
    void containsIsInclusiveOfBothEnds() {
        var range = new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31));
        assertThat(range.contains(LocalDate.of(2025, 1, 1))).isTrue();
        assertThat(range.contains(LocalDate.of(2025, 1, 31))).isTrue();
        assertThat(range.contains(LocalDate.of(2024, 12, 31))).isFalse();
        assertThat(range.contains(LocalDate.of(2025, 2, 1))).isFalse();
    }

    @Test
    void rejectsInvertedRange() {
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2025-02-01");
    }
}
