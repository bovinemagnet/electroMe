package io.github.bovinemagnet.electrome.core.cost;

import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import io.github.bovinemagnet.electrome.core.domain.UsageData;
import io.github.bovinemagnet.electrome.core.domain.UsageSeries;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Costs a usage series against a plan. */
public final class CostingEngine {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final HolidayCalendar holidays;

    public CostingEngine() {
        this(HolidayCalendar.none());
    }

    public CostingEngine(HolidayCalendar holidays) {
        this.holidays = Objects.requireNonNull(holidays, "holidays");
    }

    public BillBreakdown cost(UsageData usage, Plan plan, DateRange range) {
        var consumption = usage.consumption().slice(range);
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
                case SolarFeedIn c -> lines.add(feedInLine(c, export));
                case Discount c -> discounts.add(c);
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

    private ChargeLine flatLine(FlatRate charge, UsageSeries consumption) {
        var kWh = consumption.totalKWh();
        return new ChargeLine(charge.label(), ChargeKind.USAGE, kWh, Unit.KWH,
                charge.centsPerKWh(), dollars(kWh.multiply(charge.centsPerKWh())));
    }

    private List<ChargeLine> timeOfUseLines(
            TimeOfUse charge, UsageSeries consumption, List<IntervalReading> uncovered) {
        var totals = new LinkedHashMap<Band, BigDecimal>();
        for (var band : charge.bands()) {
            totals.put(band, BigDecimal.ZERO);
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
                totals.merge(match, reading.kWh(), BigDecimal::add);
            }
        }
        var lines = new ArrayList<ChargeLine>();
        for (var entry : totals.entrySet()) {
            var band = entry.getKey();
            var kWh = entry.getValue();
            lines.add(new ChargeLine("Usage " + band.describe(), ChargeKind.USAGE, kWh, Unit.KWH,
                    band.centsPerKWh(), dollars(kWh.multiply(band.centsPerKWh()))));
        }
        return lines;
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

    private ChargeLine feedInLine(SolarFeedIn charge, UsageSeries export) {
        var kWh = export.totalKWh();
        return new ChargeLine(charge.label(), ChargeKind.FEED_IN, kWh, Unit.KWH,
                charge.centsPerKWh(), dollars(kWh.multiply(charge.centsPerKWh())).negate());
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
