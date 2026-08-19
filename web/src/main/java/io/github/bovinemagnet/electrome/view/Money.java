package io.github.bovinemagnet.electrome.view;

import io.quarkus.qute.TemplateExtension;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Formatting for templates. The only place a BigDecimal becomes a fixed number of places. */
@TemplateExtension(namespace = "money")
public final class Money {

    private static final DecimalFormatSymbols SYMBOLS =
            DecimalFormatSymbols.getInstance(Locale.forLanguageTag("en-AU"));

    private Money() {}

    private static DecimalFormat format(String pattern) {
        return new DecimalFormat(pattern, SYMBOLS);
    }

    public static String dollars(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        var rounded = amount.setScale(2, RoundingMode.HALF_UP);
        var text = format("#,##0.00").format(rounded.abs());
        return rounded.signum() < 0 ? "-$" + text : "$" + text;
    }

    public static String cents(BigDecimal rate) {
        if (rate == null) {
            return "";
        }
        return format("#,##0.00").format(rate.setScale(2, RoundingMode.HALF_UP)) + "c";
    }

    public static String kWh(BigDecimal energy) {
        if (energy == null) {
            return "";
        }
        return format("#,##0.0").format(energy.setScale(1, RoundingMode.HALF_UP)) + " kWh";
    }

    public static String kW(BigDecimal power) {
        if (power == null) {
            return "";
        }
        return format("#,##0.00").format(power.setScale(2, RoundingMode.HALF_UP)) + " kW";
    }

    public static String percent(BigDecimal fraction) {
        if (fraction == null) {
            return "";
        }
        return format("#,##0.0").format(fraction.setScale(1, RoundingMode.HALF_UP)) + "%";
    }

    /** A plain count, grouped, so five-figure interval counts stay readable. */
    public static String count(Number value) {
        return value == null ? "" : format("#,##0").format(value);
    }

    /** Half-hour slot index as a clock time, "19:30". */
    public static String slotTime(int slot) {
        return String.format(Locale.ROOT, "%02d:%02d", slot / 2, slot % 2 == 0 ? 0 : 30);
    }
}
