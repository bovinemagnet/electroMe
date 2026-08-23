package io.github.bovinemagnet.electrome.core.cost;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Membership;
import io.github.bovinemagnet.electrome.core.tariff.HolidayCalendar;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Costs a usage series against a plan. */
public final class CostingEngine {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /** Accumulation key for a band priced at one rate, which never resets. */
    private static final Object NO_RESET = new Object();

    private final HolidayCalendar holidays;

    public CostingEngine() {
        this(HolidayCalendar.none());
    }

    public CostingEngine(HolidayCalendar holidays) {
        this.holidays = Objects.requireNonNull(holidays, "holidays");
    }

    public BillBreakdown cost(UsageData usage, Plan plan, DateRange range) {
        var controlled = usage.controlled().slice(range);
        var controlledCharge = controlledCharge(plan);

        // A household with a controlled circuit still uses that energy, whatever the plan says
        // about it. Anything the plan's controlled rate does not cover — because the plan has no
        // such rate, or because the energy fell outside the energised window — is priced as
        // ordinary consumption, which is what the retailer does. Leaving it unpriced would make
        // those plans look artificially cheap.
        var consumption = controlled.isEmpty()
                ? usage.consumption().slice(range)
                : merge(usage.consumption().slice(range),
                        notCoveredBy(controlledCharge, controlled));
        var export = usage.export().slice(range);
        var lines = new ArrayList<ChargeLine>();
        var discounts = new ArrayList<Discount>();
        var uncovered = new ArrayList<IntervalReading>();

        // No default branch: the compiler enforces that every Charge kind is costed.
        for (Charge charge : plan.charges()) {
            switch (charge) {
                case DailySupply c -> lines.add(supplyLine(c, consumption));
                case FlatRate c -> lines.add(flatLine(c, consumption));
                case TimeOfUse c -> lines.addAll(timeOfUseLines(c, consumption, uncovered));
                case Tiered c -> lines.addAll(tieredLines(c, consumption));
                case Demand c -> lines.add(demandLine(c, consumption));
                case SolarFeedIn c -> lines.addAll(feedInLines(c, export));
                case Discount c -> discounts.add(c);
                case ControlledLoad c -> lines.add(controlledLine(c, controlled, consumption));
                case Membership c -> lines.add(membershipLine(c, consumption));
            }
        }

        lines.addAll(discountLines(discounts, lines));

        var total = BigDecimal.ZERO;
        for (var line : lines) {
            total = total.add(line.cost());
        }

        return new BillBreakdown(
                plan, range, consumption.billingDays().size(), lines, total, uncovered);
    }

    private ChargeLine supplyLine(DailySupply charge, UsageSeries consumption) {
        var days = BigDecimal.valueOf(consumption.billingDays().size());
        return new ChargeLine(charge.label(), ChargeKind.SUPPLY, days, Unit.DAY,
                charge.centsPerDay(), dollars(days.multiply(charge.centsPerDay())));
    }

    /**
     * Energy on the controlled circuit, at the controlled rate.
     *
     * <p>Where the plan states an energised window, energy outside it is not on the controlled
     * tariff. It is priced as ordinary consumption instead, which is what the retailer does.
     */
    private ChargeLine controlledLine(
            ControlledLoad charge, UsageSeries controlled, UsageSeries consumption) {
        var kWh = BigDecimal.ZERO;
        for (var reading : controlled.readings()) {
            if (charge.covers(reading)) {
                kWh = kWh.add(reading.kWh());
            }
        }
        return new ChargeLine(charge.label(), ChargeKind.CONTROLLED, kWh, Unit.KWH,
                charge.centsPerKWh(), dollars(kWh.multiply(charge.centsPerKWh())));
    }

    /** Controlled-circuit energy the plan's controlled rate does not apply to. */
    private static UsageSeries notCoveredBy(ControlledLoad charge, UsageSeries controlled) {
        if (charge == null) {
            return controlled;
        }
        if (!charge.windowed()) {
            return UsageSeries.empty();
        }
        var outside = new ArrayList<IntervalReading>();
        for (var reading : controlled.readings()) {
            if (!charge.covers(reading)) {
                outside.add(reading);
            }
        }
        return UsageSeries.of(outside);
    }

    private static ControlledLoad controlledCharge(Plan plan) {
        for (var charge : plan.charges()) {
            if (charge instanceof ControlledLoad controlled) {
                return controlled;
            }
        }
        return null;
    }

    /** Two series as one, start-ordered, for energy the plan prices together. */
    private static UsageSeries merge(UsageSeries first, UsageSeries second) {
        var all = new ArrayList<IntervalReading>(
                first.readings().size() + second.readings().size());
        all.addAll(first.readings());
        all.addAll(second.readings());
        return UsageSeries.of(all);
    }

    /**
     * A recurring fee charged for being on the plan, priced over the days billed.
     *
     * <p>Shaped exactly like the supply charge because it behaves exactly like one: a fixed
     * amount per day that consumption does not change. It gets its own bill line and its own
     * kind rather than being folded into supply, because a reader comparing daily supply
     * charges across plans is asking what the network costs, not what the retailer charges for
     * membership.
     */
    private ChargeLine membershipLine(Membership charge, UsageSeries consumption) {
        var days = BigDecimal.valueOf(consumption.billingDays().size());
        return new ChargeLine(charge.label(), ChargeKind.MEMBERSHIP, days, Unit.DAY,
                charge.centsPerDay(), dollars(days.multiply(charge.centsPerDay())));
    }

    private ChargeLine flatLine(FlatRate charge, UsageSeries consumption) {
        var kWh = consumption.totalKWh();
        return new ChargeLine(charge.label(), ChargeKind.USAGE, kWh, Unit.KWH,
                charge.centsPerKWh(), dollars(kWh.multiply(charge.centsPerKWh())));
    }

    /**
     * Usage priced by window, and within a window by block.
     *
     * <p>Each band keeps its own running total per reset period, so two capped bands on the
     * same plan cannot spend each other's cap. The walk is chronological — {@code readings()}
     * is start-ordered — because a cap is exhausted in the order the meter recorded it, and
     * knowing <em>when</em> the free window ran out is what cap-aware scheduling needs.
     */
    private List<ChargeLine> timeOfUseLines(
            TimeOfUse charge, UsageSeries consumption, List<IntervalReading> uncovered) {
        var perTier = new LinkedHashMap<Band, BigDecimal[]>();
        var accumulated = new LinkedHashMap<Band, Map<Object, BigDecimal>>();
        for (var band : charge.bands()) {
            var kWh = new BigDecimal[band.tiers().size()];
            Arrays.fill(kWh, BigDecimal.ZERO);
            perTier.put(band, kWh);
            accumulated.put(band, new HashMap<Object, BigDecimal>());
        }

        for (var reading : consumption.readings()) {
            Band match = null;
            for (var band : charge.bands()) {
                if (band.matches(reading, holidays)) {
                    match = band;
                    break;
                }
            }
            if (match == null) {
                uncovered.add(reading);
            } else {
                allocate(match, reading, perTier.get(match), accumulated.get(match));
            }
        }

        var lines = new ArrayList<ChargeLine>();
        for (var entry : perTier.entrySet()) {
            var band = entry.getKey();
            var kWhPerTier = entry.getValue();
            for (int i = 0; i < band.tiers().size(); i++) {
                var tier = band.tiers().get(i);
                var kWh = kWhPerTier[i];
                lines.add(new ChargeLine(bandLabel(band, tier), ChargeKind.USAGE, kWh, Unit.KWH,
                        tier.centsPerKWh(), dollars(kWh.multiply(tier.centsPerKWh()))));
            }
        }
        return lines;
    }

    /** Adds one reading to a band's blocks, from wherever the reset period had got to. */
    private static void allocate(
            Band band,
            IntervalReading reading,
            BigDecimal[] kWhPerTier,
            Map<Object, BigDecimal> accumulated) {

        Object key = band.reset() == null ? NO_RESET : band.reset().keyFor(reading.date());
        var consumed = accumulated.getOrDefault(key, BigDecimal.ZERO);
        var remaining = reading.kWh();

        for (int i = 0; i < band.tiers().size() && remaining.signum() > 0; i++) {
            Tier tier = band.tiers().get(i);
            BigDecimal capacity;
            if (tier.unbounded()) {
                capacity = remaining;
            } else {
                var headroom = tier.thresholdKWh().subtract(consumed);
                if (headroom.signum() <= 0) {
                    continue;
                }
                capacity = headroom.min(remaining);
            }
            kWhPerTier[i] = kWhPerTier[i].add(capacity);
            consumed = consumed.add(capacity);
            remaining = remaining.subtract(capacity);
        }
        accumulated.put(key, consumed);
    }

    /**
     * The bill line for one block of one band.
     *
     * <p>An uncapped band keeps the plain window label it has always had, so a bill from
     * before capped bands existed reconciles line for line against one produced now.
     */
    private static String bandLabel(Band band, Tier tier) {
        String window = "Usage " + band.describe();
        if (!band.capped()) {
            return window;
        }
        return tier.unbounded()
                ? window + " balance"
                : window + " to " + tier.thresholdKWh().stripTrailingZeros().toPlainString()
                        + " kWh";
    }

    private List<ChargeLine> tieredLines(Tiered charge, UsageSeries consumption) {
        // Accumulate per reset period, then allocate each period's total across the blocks.
        var byPeriod = new LinkedHashMap<Object, BigDecimal>();
        for (var reading : consumption.readings()) {
            byPeriod.merge(charge.reset().keyFor(reading.date()), reading.kWh(), BigDecimal::add);
        }

        var kWhPerTier = new BigDecimal[charge.tiers().size()];
        Arrays.fill(kWhPerTier, BigDecimal.ZERO);

        for (var periodTotal : byPeriod.values()) {
            var remaining = periodTotal;
            var consumed = BigDecimal.ZERO;
            for (int i = 0; i < charge.tiers().size() && remaining.signum() > 0; i++) {
                Tier tier = charge.tiers().get(i);
                BigDecimal capacity = tier.unbounded()
                        ? remaining
                        : tier.thresholdKWh().subtract(consumed).min(remaining);
                if (capacity.signum() <= 0) {
                    continue;
                }
                kWhPerTier[i] = kWhPerTier[i].add(capacity);
                consumed = consumed.add(capacity);
                remaining = remaining.subtract(capacity);
            }
        }

        var lines = new ArrayList<ChargeLine>();
        for (int i = 0; i < charge.tiers().size(); i++) {
            Tier tier = charge.tiers().get(i);
            String label = tier.unbounded()
                    ? "Block usage balance"
                    : "Block usage to " + tier.thresholdKWh().stripTrailingZeros().toPlainString()
                            + " kWh";
            lines.add(new ChargeLine(label, ChargeKind.USAGE, kWhPerTier[i], Unit.KWH,
                    tier.centsPerKWh(), dollars(kWhPerTier[i].multiply(tier.centsPerKWh()))));
        }
        return lines;
    }

    private ChargeLine demandLine(Demand charge, UsageSeries consumption) {
        Band window = charge.window();
        var peakPerPeriod = new LinkedHashMap<Object, BigDecimal>();
        var daysPerPeriod = new LinkedHashMap<Object, TreeSet<LocalDate>>();

        for (var reading : consumption.readings()) {
            Object key = charge.reset().keyFor(reading.date());
            daysPerPeriod.computeIfAbsent(key, k -> new TreeSet<>()).add(reading.date());
            if (window.matches(reading, holidays)) {
                peakPerPeriod.merge(key, reading.averageKW(), BigDecimal::max);
            }
        }

        var cost = BigDecimal.ZERO;
        var peakSum = BigDecimal.ZERO;
        for (var entry : peakPerPeriod.entrySet()) {
            var days = BigDecimal.valueOf(daysPerPeriod.get(entry.getKey()).size());
            cost = cost.add(entry.getValue().multiply(charge.centsPerKWPerDay()).multiply(days));
            peakSum = peakSum.add(entry.getValue());
        }
        // Quantity is the mean of each period's peak, which is what a demand line shows.
        var quantity = peakPerPeriod.isEmpty()
                ? BigDecimal.ZERO
                : peakSum.divide(BigDecimal.valueOf(peakPerPeriod.size()), MathContext.DECIMAL64);

        return new ChargeLine(charge.label(), ChargeKind.DEMAND, quantity, Unit.KW,
                charge.centsPerKWPerDay(), dollars(cost));
    }

    /**
     * One credit line per band the plan pays a different rate in.
     *
     * <p>A flat credit is one band covering the whole day, so it still produces the single line
     * it always did. A banded one produces a line each, because a household that exports at
     * midday and one that exports at six o'clock earn different amounts from the same tariff,
     * and a single blended figure would hide which of the two they are.
     *
     * <p>Blocks accumulate through the same {@link #allocate} consumption uses. A capped credit
     * — seventeen cents for the first fifteen kilowatt hours a day, two cents after — is the
     * same arithmetic as a capped usage window with the sign reversed, and sharing the code is
     * what stops the two drifting apart.
     */
    private List<ChargeLine> feedInLines(SolarFeedIn charge, UsageSeries export) {
        var perTier = new LinkedHashMap<Band, BigDecimal[]>();
        var accumulated = new LinkedHashMap<Band, Map<Object, BigDecimal>>();
        for (var band : charge.bands()) {
            var kWh = new BigDecimal[band.tiers().size()];
            Arrays.fill(kWh, BigDecimal.ZERO);
            perTier.put(band, kWh);
            accumulated.put(band, new HashMap<Object, BigDecimal>());
        }

        for (var reading : export.readings()) {
            for (var band : charge.bands()) {
                if (band.matches(reading, holidays)) {
                    allocate(band, reading, perTier.get(band), accumulated.get(band));
                    break;
                }
            }
            // Export in no band earns nothing. The validator requires feed-in bands to cover
            // every minute, so reaching here means a plan got past validation.
        }

        var lines = new ArrayList<ChargeLine>();
        for (var entry : perTier.entrySet()) {
            var band = entry.getKey();
            var kWhPerTier = entry.getValue();
            for (int i = 0; i < band.tiers().size(); i++) {
                var tier = band.tiers().get(i);
                var kWh = kWhPerTier[i];
                lines.add(new ChargeLine(feedInLabel(charge, band, tier), ChargeKind.FEED_IN,
                        kWh, Unit.KWH, tier.centsPerKWh(),
                        dollars(kWh.multiply(tier.centsPerKWh())).negate()));
            }
        }
        return lines;
    }

    /**
     * A flat credit keeps its plain name; a banded one names the window, and a capped one names
     * the allowance it runs out at.
     */
    private static String feedInLabel(SolarFeedIn charge, Band band, Tier tier) {
        String name = charge.flat() ? charge.label() : charge.label() + " " + band.describe();
        if (!band.capped()) {
            return name;
        }
        return tier.unbounded()
                ? name + " balance"
                : name + " to " + tier.thresholdKWh().stripTrailingZeros().toPlainString()
                        + " kWh";
    }

    private List<ChargeLine> discountLines(List<Discount> discounts, List<ChargeLine> priced) {
        if (discounts.isEmpty()) {
            return List.of();
        }
        var subtotals = new EnumMap<ChargeKind, BigDecimal>(ChargeKind.class);
        var total = BigDecimal.ZERO;
        for (var line : priced) {
            subtotals.merge(line.kind(), line.cost(), BigDecimal::add);
            total = total.add(line.cost());
        }

        var lines = new ArrayList<ChargeLine>();
        for (var discount : discounts) {
            BigDecimal base = switch (discount.scope()) {
                case USAGE -> subtotals.getOrDefault(ChargeKind.USAGE, BigDecimal.ZERO);
                case SUPPLY -> subtotals.getOrDefault(ChargeKind.SUPPLY, BigDecimal.ZERO);
                case TOTAL -> total;
            };
            BigDecimal amount = discount.basis() == DiscountBasis.PERCENTAGE
                    ? base.multiply(discount.value()).divide(ONE_HUNDRED, MathContext.DECIMAL64)
                    : dollars(discount.value());
            lines.add(new ChargeLine(discount.label(), ChargeKind.DISCOUNT, BigDecimal.ZERO,
                    Unit.NONE, null, amount.negate()));
        }
        return lines;
    }

    /** Converts a cents amount to dollars at full precision. */
    private static BigDecimal dollars(BigDecimal cents) {
        return cents.divide(ONE_HUNDRED, MathContext.DECIMAL64);
    }
}
