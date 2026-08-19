package io.github.bovinemagnet.electrome.core.tariff;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Which calendar days a tariff band applies to. */
public enum DaySelector {
    /** Every day, including weekends and public holidays. The Victorian norm. */
    ALL,
    /** Monday to Friday, public holidays included. */
    WEEKDAYS,
    /** Saturday and Sunday. */
    WEEKENDS,
    /** Monday to Friday, public holidays excluded. */
    BUSINESS_DAYS;

    public boolean matches(LocalDate date, HolidayCalendar holidays) {
        boolean weekend =
                date.getDayOfWeek() == DayOfWeek.SATURDAY
                        || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        return switch (this) {
            case ALL -> true;
            case WEEKDAYS -> !weekend;
            case WEEKENDS -> weekend;
            case BUSINESS_DAYS -> !weekend && !holidays.isHoliday(date);
        };
    }
}
