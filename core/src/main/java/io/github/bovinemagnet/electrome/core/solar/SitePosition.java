package io.github.bovinemagnet.electrome.core.solar;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Where the premises is.
 *
 * @param standardMeridianDegrees the meridian the site's standard time is based on; 150 for
 *     Australian Eastern Standard Time
 */
public record SitePosition(
        double latitudeDegrees,
        double longitudeDegrees,
        double standardMeridianDegrees,
        ZoneId zone) {

    public SitePosition {
        Objects.requireNonNull(zone, "zone");
        if (latitudeDegrees < -90 || latitudeDegrees > 90) {
            throw new IllegalArgumentException("Latitude out of range: " + latitudeDegrees);
        }
        if (longitudeDegrees < -180 || longitudeDegrees > 180) {
            throw new IllegalArgumentException("Longitude out of range: " + longitudeDegrees);
        }
    }

    public static SitePosition melbourne() {
        return new SitePosition(-37.81, 144.96, 150.0, ZoneId.of("Australia/Melbourne"));
    }
}
