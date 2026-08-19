package io.github.bovinemagnet.electrome.core.domain;

import java.util.Locale;

/** Meter read quality as published in retailer interval exports. */
public enum Quality {
    /** An actual metered read. */
    ACTUAL,
    /** Estimated by the meter data provider. */
    ESTIMATED,
    /** Substituted for a missing read. */
    SUBSTITUTED;

    public static Quality fromFlag(String flag) {
        if (flag == null || flag.isBlank()) {
            throw new IllegalArgumentException("Quality flag must not be blank");
        }
        return switch (flag.trim().toUpperCase(Locale.ROOT)) {
            case "A" -> ACTUAL;
            case "E" -> ESTIMATED;
            case "S" -> SUBSTITUTED;
            default -> throw new IllegalArgumentException("Unknown quality flag: " + flag);
        };
    }
}
