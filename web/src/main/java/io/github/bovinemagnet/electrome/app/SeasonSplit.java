package io.github.bovinemagnet.electrome.app;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The year cut into two parts, by month.
 *
 * <p>A household whose consumption changes shape with the season is not necessarily best served
 * by one tariff all year. A pool running November to March moves several thousand kilowatt hours
 * into the middle of the day, and a plan that wins on that shape can lose badly on the winter
 * one. Whether switching twice a year is worth the effort is a question this application can
 * answer and a ranking of annual totals cannot.
 *
 * <p>Cut by month rather than by date, because a tariff's own accumulation periods are monthly
 * at their shortest. Splitting mid-month would divide a monthly block or cap across both halves
 * and let each start again, which would make both seasons cheaper than the year they came from.
 *
 * @param from the first month of the first season
 * @param to the last month of the first season, inclusive, wrapping through December
 */
public record SeasonSplit(Month from, Month to) {

    /** A month as it should read in a control: "November", not the enum's own shouting. */
    public record MonthChoice(Month month, String label) {}

    /** Filtration and heating season in southern Australia, which is what prompted this. */
    public static final SeasonSplit DEFAULT = new SeasonSplit(Month.NOVEMBER, Month.MARCH);

    public SeasonSplit {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (span(from, to).size() == Month.values().length) {
            throw new IllegalArgumentException(
                    "A split covering every month leaves no second season to compare against");
        }
    }

    /** Every month, in calendar order, ready for a select control. */
    public static List<MonthChoice> choices() {
        var choices = new ArrayList<MonthChoice>();
        for (var month : Month.values()) {
            choices.add(new MonthChoice(
                    month, month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)));
        }
        return List.copyOf(choices);
    }

    /**
     * Reads a split from query parameters, falling back to the default.
     *
     * <p>Accepts a month name in any case, an abbreviation, or a number. A value that means
     * nothing falls back rather than failing: a mistyped URL should show the default report,
     * not an error page.
     */
    public static SeasonSplit of(String from, String to) {
        Month start = month(from);
        Month end = month(to);
        if (start == null || end == null) {
            return DEFAULT;
        }
        try {
            return new SeasonSplit(start, end);
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /** The months in the first season. */
    public Set<Month> months() {
        return span(from, to);
    }

    /** Everything the first season does not cover, which is the second season. */
    public Set<Month> otherMonths() {
        var rest = EnumSet.allOf(Month.class);
        rest.removeAll(months());
        return rest;
    }

    public boolean contains(Month month) {
        return months().contains(month);
    }

    /** The first season as it reads on the screen, for example "Nov-Mar". */
    public String label() {
        return abbreviate(from) + "-" + abbreviate(to);
    }

    /** The second season, named by its own first and last months in calendar order. */
    public String otherLabel() {
        var rest = otherMonths();
        Month start = to.plus(1);
        Month end = from.minus(1);
        return rest.isEmpty() ? "" : abbreviate(start) + "-" + abbreviate(end);
    }

    /** True when this is the split the page offers before anything is chosen. */
    public boolean isDefault() {
        return equals(DEFAULT);
    }

    // -----------------------------------------------------------------

    private static Set<Month> span(Month from, Month to) {
        var months = EnumSet.noneOf(Month.class);
        Month month = from;
        while (true) {
            months.add(month);
            if (month == to) {
                return months;
            }
            month = month.plus(1);
        }
    }

    private static String abbreviate(Month month) {
        return month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static Month month(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var text = value.trim();
        try {
            int number = Integer.parseInt(text);
            return number >= 1 && number <= 12 ? Month.of(number) : null;
        } catch (NumberFormatException ignored) {
            // Not a number, so try it as a name.
        }
        var wanted = text.toUpperCase(Locale.ROOT);
        for (var month : Month.values()) {
            if (month.name().equals(wanted) || abbreviate(month).toUpperCase(Locale.ROOT)
                    .equals(wanted)) {
                return month;
            }
        }
        return null;
    }
}
