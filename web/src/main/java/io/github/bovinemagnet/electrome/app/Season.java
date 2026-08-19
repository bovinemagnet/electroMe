package io.github.bovinemagnet.electrome.app;

import java.time.LocalDate;

/** Australian meteorological seasons. */
public enum Season {
    SUMMER("Summer"),
    AUTUMN("Autumn"),
    WINTER("Winter"),
    SPRING("Spring");

    private final String label;

    Season(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Season of(LocalDate date) {
        return switch (date.getMonth()) {
            case DECEMBER, JANUARY, FEBRUARY -> SUMMER;
            case MARCH, APRIL, MAY -> AUTUMN;
            case JUNE, JULY, AUGUST -> WINTER;
            case SEPTEMBER, OCTOBER, NOVEMBER -> SPRING;
        };
    }
}
