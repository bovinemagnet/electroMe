package io.github.bovinemagnet.electrome.ingest;

import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Membership;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.util.List;

/**
 * Writes a {@link Plan} back out as the YAML {@link PlanYamlLoader} reads.
 *
 * <p>Hand-written for the same reason the loader is: {@code core} carries no dependencies and
 * so cannot be annotated. The two are a matched pair, and {@code PlanYamlWriterTest} holds them
 * to it by loading everything this class writes.
 *
 * <p>The switch over {@link Charge} has no default branch. Adding a charge kind is a compile
 * error here as much as in the costing engine, which is the point: a kind the writer cannot
 * express is a plan that would change shape the moment it was saved.
 *
 * <p>Rates are written exactly as the plan holds them, with {@code gstInclusive: true}. Plans
 * are normalised to GST inclusive when they are loaded or mapped, so dividing back down to
 * write an exclusive figure would turn an exact rate into a repeating decimal and read back as
 * a different number.
 */
public final class PlanYamlWriter {

    private PlanYamlWriter() {}

    public static String write(Plan plan, PlanOrigin origin) {
        var out = new StringBuilder();

        out.append("# Saved from the Consumer Data Right register on ")
                .append(origin.readOn())
                .append(".\n")
                .append("# Edit it freely: the source block below is what marks it as ours to\n")
                .append("# replace on a later save. Remove the block and it is left alone.\n");

        out.append("id: ").append(scalar(plan.id())).append('\n');
        out.append("name: ").append(scalar(plan.name())).append('\n');
        out.append("retailer: ").append(scalar(plan.retailer())).append('\n');
        out.append("zone: ").append(plan.zone().name()).append('\n');
        out.append("gstInclusive: true\n");
        if (plan.validFrom() != null) {
            out.append("validFrom: ").append(plan.validFrom()).append('\n');
        }
        if (plan.validTo() != null) {
            out.append("validTo: ").append(plan.validTo()).append('\n');
        }

        out.append("source:\n");
        out.append("  cdrPlanId: ").append(scalar(origin.cdrPlanId())).append('\n');
        out.append("  readOn: ").append(origin.readOn()).append('\n');

        out.append("charges:\n");
        for (var charge : plan.charges()) {
            charge(out, charge);
        }
        return out.toString();
    }

    private static void charge(StringBuilder out, Charge charge) {
        switch (charge) {
            case DailySupply supply -> {
                out.append("  - type: dailySupply\n");
                out.append("    cents: ").append(number(supply.centsPerDay())).append('\n');
            }
            case FlatRate flat -> {
                out.append("  - type: flatRate\n");
                out.append("    cents: ").append(number(flat.centsPerKWh())).append('\n');
            }
            case TimeOfUse tou -> {
                out.append("  - type: timeOfUse\n");
                out.append("    bands:\n");
                for (var band : tou.bands()) {
                    band(out, band);
                }
            }
            case Tiered tiered -> {
                out.append("  - type: tiered\n");
                out.append("    reset: ").append(tiered.reset().name()).append('\n');
                tiers(out, "    ", tiered.tiers());
            }
            case Demand demand -> {
                out.append("  - type: demand\n");
                out.append("    from: ").append(clock(demand.fromMinuteOfDay())).append('\n');
                out.append("    to: ").append(clock(demand.toMinuteOfDay())).append('\n');
                out.append("    days: ").append(demand.days().name()).append('\n');
                out.append("    reset: ").append(demand.reset().name()).append('\n');
                out.append("    centsPerKWPerDay: ")
                        .append(number(demand.centsPerKWPerDay()))
                        .append('\n');
            }
            case ControlledLoad controlled -> {
                out.append("  - type: controlledLoad\n");
                out.append("    cents: ").append(number(controlled.centsPerKWh())).append('\n');
                if (controlled.windowed()) {
                    out.append("    from: ")
                            .append(clock(controlled.fromMinuteOfDay()))
                            .append('\n');
                    out.append("    to: ").append(clock(controlled.toMinuteOfDay())).append('\n');
                }
            }
            case SolarFeedIn feedIn -> {
                out.append("  - type: solarFeedIn\n");
                if (feedIn.flat()) {
                    // The whole-day band is how a flat credit is held, not how it was written.
                    // Writing it back as one rate keeps a hand-edited file looking hand-written.
                    out.append("    cents: ")
                            .append(number(feedIn.bands().get(0).centsPerKWh()))
                            .append('\n');
                } else {
                    out.append("    bands:\n");
                    for (var band : feedIn.bands()) {
                        band(out, band);
                    }
                }
            }
            case Membership membership -> {
                out.append("  - type: membership\n");
                out.append("    name: ").append(scalar(membership.name())).append('\n');
                out.append("    centsPerDay: ")
                        .append(number(membership.centsPerDay()))
                        .append('\n');
            }
            case Discount discount -> {
                out.append("  - type: discount\n");
                out.append("    name: ").append(scalar(discount.name())).append('\n');
                out.append("    basis: ").append(discount.basis().name()).append('\n');
                out.append("    scope: ").append(discount.scope().name()).append('\n');
                out.append("    value: ").append(number(discount.value())).append('\n');
                if (discount.condition() != null) {
                    out.append("    condition: ")
                            .append(scalar(discount.condition()))
                            .append('\n');
                }
            }
        }
    }

    private static void band(StringBuilder out, Band band) {
        out.append("      - from: ").append(clock(band.fromMinuteOfDay())).append('\n');
        out.append("        to: ").append(clock(band.toMinuteOfDay())).append('\n');
        out.append("        days: ").append(band.days().name()).append('\n');
        if (band.reset() == null) {
            out.append("        cents: ").append(number(band.centsPerKWh())).append('\n');
        } else {
            out.append("        reset: ").append(band.reset().name()).append('\n');
            tiers(out, "        ", band.tiers());
        }
    }

    private static void tiers(StringBuilder out, String indent, List<Tier> tiers) {
        out.append(indent).append("tiers:\n");
        for (var tier : tiers) {
            if (tier.unbounded()) {
                out.append(indent).append("  - cents: ")
                        .append(number(tier.centsPerKWh()))
                        .append('\n');
            } else {
                out.append(indent).append("  - upToKWh: ")
                        .append(number(tier.thresholdKWh()))
                        .append('\n');
                out.append(indent).append("    cents: ")
                        .append(number(tier.centsPerKWh()))
                        .append('\n');
            }
        }
    }

    /**
     * Minutes past midnight as a clock time, with the end of the day written "24:00".
     *
     * <p>The loader accepts that sentinel and the tariff model needs it: a band ending at
     * midnight is not the same as one ending where the day starts.
     */
    private static String clock(int minuteOfDay) {
        return String.format("\"%02d:%02d\"", minuteOfDay / 60, minuteOfDay % 60);
    }

    /**
     * The rate as it is held, unrounded and quoted.
     *
     * <p>Quoted because YAML reads a bare {@code 22.60} as a float, and a float has no scale to
     * lose the trailing zero from: the value read back is {@code 22.6}, which is the same
     * number but not an equal {@code BigDecimal}. That difference is not cosmetic here. A saved
     * plan is compared against the register on the next read to say whether its rates have
     * moved, and a comparison that reports every saved plan as changed the moment it is written
     * would be worse than not making it.
     *
     * <p>{@code toPlainString} rather than {@code toString}: a rate that reached scientific
     * notation would still parse, but nobody opening the file could check it against a bill.
     */
    private static String number(BigDecimal value) {
        return '"' + value.toPlainString() + '"';
    }

    /**
     * A double-quoted scalar, always.
     *
     * <p>Published plan names carry colons, hashes, leading percent signs and trailing spaces,
     * every one of which means something else in unquoted YAML. Quoting unconditionally is one
     * rule rather than a list of exceptions to get wrong.
     */
    private static String scalar(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
