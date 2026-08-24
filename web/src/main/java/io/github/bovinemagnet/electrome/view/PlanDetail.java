package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.ChargeLine;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Membership;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import io.github.bovinemagnet.electrome.market.cdr.PlanExtras;
import io.github.bovinemagnet.electrome.market.cdr.PlanFee;
import io.github.bovinemagnet.electrome.market.cdr.PlanIncentive;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/**
 * One plan, fully expanded: everything a comparison row cannot show.
 *
 * <p>Assembled once, so the template stays dumb. Anything a reader could question — which band
 * is dearest, what a fee actually amounts to, whether the total includes it — is decided here
 * where it can be tested.
 *
 * @param local true when this plan came from a file rather than the harvest; a local file wins
 *     on identifier collision and so behaves differently
 * @param harvestedAt when the register was last read, for a plan that came from it; null for a
 *     plan defined as a file, whose freshness is the household's own business
 * @param shortlisted true when the household has picked this plan out of the market
 * @param withdrawn true when the register no longer publishes it
 */
public record PlanDetail(
        BillBreakdown bill,
        DateRange range,
        List<Strip> strips,
        BigDecimal supplyCentsPerDay,
        List<String> components,
        List<String> conditions,
        List<PlanFee> fees,
        List<PlanIncentive> incentives,
        boolean local,
        java.time.LocalDateTime harvestedAt,
        boolean shortlisted,
        boolean withdrawn) {

    /**
     * One 24-hour axis.
     *
     * <p>A plan that prices weekdays and weekends differently gets a strip each. Drawing both on
     * one axis would overlap their windows and read as a tariff charging two rates at once.
     */
    public record Strip(String label, List<Segment> segments) {}

    /**
     * One window on the strip.
     *
     * @param widthPercent already formatted for a CSS width, e.g. "20.83"
     */
    public record Segment(
            String window, String colour, BigDecimal centsPerKWh, String widthPercent) {}

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal MINUTES_PER_DAY =
            BigDecimal.valueOf(Band.MINUTES_PER_DAY);

    public PlanDetail {
        strips = List.copyOf(strips);
        components = List.copyOf(components);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        fees = fees == null ? List.of() : List.copyOf(fees);
        incentives = incentives == null ? List.of() : List.copyOf(incentives);
    }

    public static PlanDetail of(BillBreakdown bill, DateRange range, List<String> conditions,
            PlanExtras extras, boolean local) {
        return of(bill, range, conditions, extras, local, null, false, false);
    }

    /** The same, with where the plan came from and whether the household is watching it. */
    public static PlanDetail of(BillBreakdown bill, DateRange range, List<String> conditions,
            PlanExtras extras, boolean local, java.time.LocalDateTime harvestedAt,
            boolean shortlisted, boolean withdrawn) {
        var plan = bill.plan();
        var resolved = extras == null ? PlanExtras.none() : extras;
        return new PlanDetail(
                bill,
                range,
                strips(plan),
                supplyCentsPerDay(plan),
                components(plan),
                conditions,
                resolved.fees(),
                resolved.incentives(),
                local,
                harvestedAt,
                shortlisted,
                withdrawn);
    }

    /**
     * True when this plan came from the register rather than from a file.
     *
     * <p>Independent of whether a harvest has run in this session: a plan recovered from the
     * harvest cache is still the register's, and still needs to say so.
     */
    public boolean harvested() {
        return !local;
    }

    /** Whether we can say when the register was last read, as opposed to only that it was. */
    public boolean harvestDateKnown() {
        return harvestedAt != null;
    }

    /** The harvest date, as a reader reads a date. */
    public String harvestedOn() {
        return harvestedAt == null
                ? ""
                : harvestedAt.format(java.time.format.DateTimeFormatter.ofPattern(
                        "d MMMM yyyy 'at' HH:mm", java.util.Locale.ENGLISH));
    }

    public Plan plan() {
        return bill.plan();
    }

    public List<ChargeLine> lines() {
        return bill.lines();
    }

    public boolean conditional() {
        return !conditions.isEmpty();
    }

    /** Whether the total above assumes the household earns a conditional discount. */
    public boolean assumesConditions() {
        return bill.assumesConditions();
    }

    public List<String> discountConditions() {
        return bill.discountConditions();
    }

    public boolean hasExtras() {
        return !fees.isEmpty() || !incentives.isEmpty();
    }

    /**
     * The sentence that has to accompany every fee and incentive shown.
     *
     * <p>Published fee amounts are GST inclusive while unit prices are exclusive, so they are
     * deliberately not costed. Showing them without saying so would mislead precisely where a
     * reader has come looking for detail.
     */
    public String excludedNote() {
        return "Published by the retailer and not included in the costed figures above.";
    }

    /** One strip per day selector the plan uses, in the order the selectors are declared. */
    private static List<Strip> strips(Plan plan) {
        var bands = BandPalette.timeOfUseBands(plan);
        if (bands.isEmpty()) {
            return flatStrip(plan);
        }

        var byDays = new EnumMap<DaySelector, List<Band>>(DaySelector.class);
        for (var band : bands) {
            byDays.computeIfAbsent(band.days(), unused -> new ArrayList<>()).add(band);
        }

        var strips = new ArrayList<Strip>();
        for (var entry : byDays.entrySet()) {
            strips.add(new Strip(label(entry.getKey()), segments(plan, entry.getValue())));
        }
        return strips;
    }

    /** A plan with no windows still has a rate, and it applies all day. */
    private static List<Strip> flatStrip(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof FlatRate flat) {
                return List.of(new Strip("All days", List.of(new Segment(
                        "00:00-24:00",
                        BandPalette.cssVar(BandPalette.SHOULDER),
                        flat.centsPerKWh(),
                        "100.00"))));
            }
        }
        return List.of();
    }

    /**
     * Bands as proportional segments across the day, in clock order.
     *
     * <p>A band running past midnight occupies two places on a 24-hour axis, so it is split.
     * Drawing it once, from its start to its end, would run the segment backwards.
     */
    private static List<Segment> segments(Plan plan, List<Band> bands) {
        var pieces = new ArrayList<Segment>();
        for (var band : bands) {
            String colour = BandPalette.cssVar(BandPalette.tokenFor(plan, band));
            if (band.wrapsMidnight()) {
                pieces.add(piece(band, 0, band.toMinuteOfDay(), colour));
                pieces.add(piece(band, band.fromMinuteOfDay(), Band.MINUTES_PER_DAY, colour));
            } else {
                pieces.add(piece(band, band.fromMinuteOfDay(), band.toMinuteOfDay(), colour));
            }
        }
        pieces.sort(Comparator.comparing(segment -> segment.window()));
        return List.copyOf(pieces);
    }

    private static Segment piece(Band band, int from, int to, String colour) {
        return new Segment(
                format(from) + "-" + format(to),
                colour,
                band.centsPerKWh(),
                percentOfDay(to - from));
    }

    private static String format(int minuteOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                minuteOfDay / 60, minuteOfDay % 60);
    }

    private static String percentOfDay(int minutes) {
        return BigDecimal.valueOf(minutes)
                .multiply(ONE_HUNDRED)
                .divide(MINUTES_PER_DAY, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String label(DaySelector days) {
        return switch (days) {
            case ALL -> "All days";
            case WEEKDAYS -> "Weekdays";
            case WEEKENDS -> "Weekends";
            case BUSINESS_DAYS -> "Business days";
        };
    }

    private static BigDecimal supplyCentsPerDay(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof DailySupply supply) {
                return supply.centsPerDay();
            }
        }
        return null;
    }

    /** What the plan carries besides its usage rates, named rather than left to be inferred. */
    private static List<String> components(Plan plan) {
        var named = new ArrayList<String>();
        for (var charge : plan.charges()) {
            String name = switch (charge) {
                case DailySupply unused -> "Daily supply";
                case FlatRate unused -> "Flat usage rate";
                case TimeOfUse unused -> "Time-of-use rates";
                case Tiered unused -> "Block rates";
                case Demand unused -> "Demand charge";
                // Named rather than inferred: a credit that pays eleven cents at six o'clock
                // and under two at noon is a different offer from a flat one.
                case SolarFeedIn feedIn ->
                        feedIn.varies() ? "Solar feed-in, by time of day" : "Solar feed-in";
                case Membership unused -> "Membership fee";
                case Discount unused -> "Discount";
                case ControlledLoad unused -> "Controlled load";
            };
            if (!named.contains(name)) {
                named.add(name);
            }
        }
        return named;
    }
}
