package io.github.bovinemagnet.electrome.core.tariff;

import java.time.LocalDate;

/**
 * Source of public holiday dates.
 *
 * <p>No published Victorian tariff distinguishes public holidays, so {@link #none()} is the
 * expected implementation here. The abstraction exists because other jurisdictions do.
 */
@FunctionalInterface
public interface HolidayCalendar {

    boolean isHoliday(LocalDate date);

    /** A calendar in which no date is a holiday. */
    static HolidayCalendar none() {
        return date -> false;
    }
}
