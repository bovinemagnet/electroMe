package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Month;
import org.junit.jupiter.api.Test;

class SeasonSplitTest {

    @Test
    void theDefaultIsTheSouthernPoolSeason() {
        assertThat(SeasonSplit.DEFAULT.months()).containsExactlyInAnyOrder(
                Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH);
        assertThat(SeasonSplit.DEFAULT.otherMonths()).hasSize(7);
        assertThat(SeasonSplit.DEFAULT.label()).isEqualTo("Nov-Mar");
        assertThat(SeasonSplit.DEFAULT.otherLabel()).isEqualTo("Apr-Oct");
        assertThat(SeasonSplit.DEFAULT.isDefault()).isTrue();
    }

    @Test
    void theTwoSeasonsPartitionTheYear() {
        // Every month belongs to exactly one season, or a plan's year would not add up.
        for (var split : new SeasonSplit[] {
                SeasonSplit.DEFAULT,
                new SeasonSplit(Month.JANUARY, Month.JUNE),
                new SeasonSplit(Month.DECEMBER, Month.DECEMBER)}) {
            for (var month : Month.values()) {
                assertThat(split.months().contains(month))
                        .as("%s in %s", month, split.label())
                        .isNotEqualTo(split.otherMonths().contains(month));
            }
            assertThat(split.months().size() + split.otherMonths().size()).isEqualTo(12);
        }
    }

    @Test
    void aSingleMonthSeasonIsAllowed() {
        var december = new SeasonSplit(Month.DECEMBER, Month.DECEMBER);
        assertThat(december.months()).containsExactly(Month.DECEMBER);
        assertThat(december.label()).isEqualTo("Dec-Dec");
        assertThat(december.otherLabel()).isEqualTo("Jan-Nov");
    }

    @Test
    void rejectsASplitThatSwallowsTheWholeYear() {
        assertThatThrownBy(() -> new SeasonSplit(Month.JANUARY, Month.DECEMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no second season");
    }

    @Test
    void readsMonthsByNameAbbreviationOrNumber() {
        assertThat(SeasonSplit.of("november", "march")).isEqualTo(SeasonSplit.DEFAULT);
        assertThat(SeasonSplit.of("Nov", "MAR")).isEqualTo(SeasonSplit.DEFAULT);
        assertThat(SeasonSplit.of("11", "3")).isEqualTo(SeasonSplit.DEFAULT);
    }

    /** A mistyped URL should show the default report, not an error page. */
    @Test
    void fallsBackToTheDefaultRatherThanFailing() {
        assertThat(SeasonSplit.of(null, null)).isEqualTo(SeasonSplit.DEFAULT);
        assertThat(SeasonSplit.of("Smarch", "March")).isEqualTo(SeasonSplit.DEFAULT);
        assertThat(SeasonSplit.of("13", "3")).isEqualTo(SeasonSplit.DEFAULT);
        assertThat(SeasonSplit.of("January", "December")).isEqualTo(SeasonSplit.DEFAULT);
    }
}
