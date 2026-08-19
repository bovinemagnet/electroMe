package io.github.bovinemagnet.electrome.core.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** An inclusive range of calendar dates. */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Range end " + to + " precedes start " + from);
        }
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** Inclusive day count, so a single-day range is 1. */
    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}
