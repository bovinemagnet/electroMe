package io.github.bovinemagnet.electrome.core.solar;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Solar position and clear-sky irradiance.
 *
 * <p>This is the one place in the codebase that resolves a local time against a zone. Solar
 * position depends on solar time, which differs from clock time by the daylight-saving offset,
 * the site's displacement from its standard meridian, and the equation of time. Together those
 * reach about ninety minutes in Melbourne, which is more than enough to put the generation
 * peak in the wrong tariff band and invert a battery decision.
 *
 * <p>It is also the one place {@code double} is used. Solar geometry is trigonometry; callers
 * receive {@code BigDecimal} at the boundary in {@link GenerationModel}.
 *
 * <p>Never call this from tariff code.
 */
public final class SolarGeometry {

    /** Solar constant, kW per square metre. */
    private static final double SOLAR_CONSTANT = 1.353;

    /** Atmospheric transmittance for a clear sky. */
    private static final double TRANSMITTANCE = 0.7;

    private SolarGeometry() {}

    /** Cooper's equation. Positive in the northern summer. */
    public static double declinationDegrees(LocalDate date) {
        int dayOfYear = date.getDayOfYear();
        return 23.45 * Math.sin(Math.toRadians(360.0 * (284 + dayOfYear) / 365.0));
    }

    /** The equation of time, in minutes, from the site's mean solar time. */
    public static double equationOfTimeMinutes(LocalDate date) {
        double b = Math.toRadians(360.0 * (date.getDayOfYear() - 81) / 364.0);
        return 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);
    }

    /**
     * Apparent solar time as a fractional hour.
     *
     * <p>Corrects the clock reading for daylight saving, the site's offset from its standard
     * meridian, and the equation of time.
     */
    public static double solarHour(LocalDateTime localTime, SitePosition site) {
        double clockMinutes = localTime.getHour() * 60.0 + localTime.getMinute();

        // The actual offset for this instant, which is where daylight saving enters.
        int offsetMinutes = site.zone().getRules().getOffset(localTime).getTotalSeconds() / 60;
        double standardOffsetMinutes = 60.0 * site.standardMeridianDegrees() / 15.0;

        double correction =
                -(offsetMinutes - standardOffsetMinutes)
                        + 4.0 * (site.longitudeDegrees() - site.standardMeridianDegrees())
                        + equationOfTimeMinutes(localTime.toLocalDate());

        return (clockMinutes + correction) / 60.0;
    }

    public static double elevationDegrees(LocalDateTime localTime, SitePosition site) {
        double latitude = Math.toRadians(site.latitudeDegrees());
        double declination = Math.toRadians(declinationDegrees(localTime.toLocalDate()));
        double hourAngle = Math.toRadians(15.0 * (solarHour(localTime, site) - 12.0));

        double sinElevation =
                Math.sin(latitude) * Math.sin(declination)
                        + Math.cos(latitude) * Math.cos(declination) * Math.cos(hourAngle);
        return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinElevation))));
    }

    /** Azimuth measured clockwise from true north, 0 to 360. */
    public static double azimuthDegrees(LocalDateTime localTime, SitePosition site) {
        double latitude = Math.toRadians(site.latitudeDegrees());
        double declination = Math.toRadians(declinationDegrees(localTime.toLocalDate()));
        double hourAngle = Math.toRadians(15.0 * (solarHour(localTime, site) - 12.0));

        double y = Math.sin(hourAngle);
        double x = Math.cos(hourAngle) * Math.sin(latitude)
                - Math.tan(declination) * Math.cos(latitude);
        double azimuth = Math.toDegrees(Math.atan2(y, x)) + 180.0;
        return (azimuth % 360.0 + 360.0) % 360.0;
    }

    /**
     * Clear-sky direct normal irradiance in kW per square metre.
     *
     * <p>A simple air-mass attenuation model. It is an idealisation: real output on a given
     * day varies with cloud, which this deliberately does not model. Over a year the error
     * largely averages out, and a scenario comparison only needs the shape and rough scale.
     */
    public static double clearSkyDirectNormalKW(double elevationDegrees) {
        if (elevationDegrees <= 0) {
            return 0.0;
        }
        double airMass = 1.0 / Math.sin(Math.toRadians(elevationDegrees));
        return SOLAR_CONSTANT * Math.pow(TRANSMITTANCE, Math.pow(airMass, 0.678));
    }
}
