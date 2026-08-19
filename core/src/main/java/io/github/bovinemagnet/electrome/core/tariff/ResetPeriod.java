package io.github.bovinemagnet.electrome.core.tariff;

import java.time.LocalDate;
import java.time.YearMonth;

/** How often an accumulating charge resets its running total. */
public enum ResetPeriod {
    DAILY,
    MONTHLY,
    QUARTERLY,
    ANNUAL;

    /**
     * An opaque grouping key. Two dates in the same accumulation window return equal keys.
     */
    public Object keyFor(LocalDate date) {
        return switch (this) {
            case DAILY -> date;
            case MONTHLY -> YearMonth.from(date);
            case QUARTERLY -> date.getYear() * 4 + (date.getMonthValue() - 1) / 3;
            case ANNUAL -> date.getYear();
        };
    }
}
