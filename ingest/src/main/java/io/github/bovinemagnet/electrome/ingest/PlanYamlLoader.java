package io.github.bovinemagnet.electrome.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.ControlledLoad;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.Demand;
import io.github.bovinemagnet.electrome.core.tariff.Discount;
import io.github.bovinemagnet.electrome.core.tariff.DiscountBasis;
import io.github.bovinemagnet.electrome.core.tariff.DiscountScope;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.PlanValidator;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds {@link Plan} objects from YAML.
 *
 * <p>The mapping is written by hand rather than driven by annotations, because {@code core}
 * carries no dependencies and so cannot be annotated. The trade buys precise error messages:
 * a malformed plan names the field at fault instead of surfacing a binding stack trace.
 */
public final class PlanYamlLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final BigDecimal GST_MULTIPLIER = new BigDecimal("1.1");

    private PlanYamlLoader() {}

    public static Plan load(Path file) {
        try {
            return load(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read plan file " + file, e);
        }
    }

    /** Loads every {@code .yaml} and {@code .yml} file in a directory, sorted by file name. */
    public static List<Plan> loadDirectory(Path directory) {
        try (var files = Files.list(directory)) {
            return files.filter(p -> {
                        var name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted()
                    .map(PlanYamlLoader::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list plan directory " + directory, e);
        }
    }

    public static Plan load(String yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new IllegalArgumentException("Plan is not valid YAML: " + e.getMessage(), e);
        }

        String id = text(root, "id");
        boolean inclusive = root.path("gstInclusive").asBoolean(true);

        var charges = new ArrayList<Charge>();
        JsonNode chargeNodes = root.get("charges");
        if (chargeNodes == null || !chargeNodes.isArray() || chargeNodes.isEmpty()) {
            throw new IllegalArgumentException("Plan " + id + " has no charges");
        }
        for (JsonNode node : chargeNodes) {
            charges.add(charge(id, node, inclusive));
        }

        var plan = new Plan(
                id,
                text(root, "name"),
                text(root, "retailer"),
                DistributionZone.valueOf(text(root, "zone")),
                charges,
                true,
                date(root, "validFrom"),
                date(root, "validTo"));

        PlanValidator.validate(plan);
        return plan;
    }

    private static Charge charge(String planId, JsonNode node, boolean inclusive) {
        String type = text(node, "type");
        return switch (type) {
            case "dailySupply" -> new DailySupply(rate(node, "cents", inclusive));
            case "flatRate" -> new FlatRate(rate(node, "cents", inclusive));
            case "timeOfUse" -> timeOfUse(planId, node, inclusive);
            case "tiered" -> tiered(planId, node, inclusive);
            case "controlledLoad" -> controlledLoad(planId, node, inclusive);
            case "demand" -> demand(node, inclusive);
            case "solarFeedIn" -> new SolarFeedIn(rate(node, "cents", inclusive));
            case "discount" -> discount(node);
            default -> throw new IllegalArgumentException(
                    "Plan " + planId + " uses unknown charge type: " + type);
        };
    }

    private static TimeOfUse timeOfUse(String planId, JsonNode node, boolean inclusive) {
        JsonNode bandNodes = node.get("bands");
        if (bandNodes == null || !bandNodes.isArray() || bandNodes.isEmpty()) {
            throw new IllegalArgumentException("Plan " + planId + " timeOfUse has no bands");
        }
        var bands = new ArrayList<Band>();
        for (JsonNode band : bandNodes) {
            bands.add(band(planId, band, inclusive));
        }
        return new TimeOfUse(bands);
    }

    /**
     * One window, priced either at a flat rate or in blocks that reset.
     *
     * <p>The two forms are mutually exclusive by design. A band carrying both {@code cents}
     * and {@code tiers} has no single meaning, and quietly preferring one of them would price
     * a household's bill from a field the author did not think they were writing.
     */
    private static Band band(String planId, JsonNode node, boolean inclusive) {
        String from = text(node, "from");
        String to = text(node, "to");
        String where = "Plan " + planId + " band " + from + "-" + to;

        boolean hasCents = present(node, "cents");
        boolean hasTiers = present(node, "tiers");
        if (hasCents == hasTiers) {
            throw new IllegalArgumentException(
                    where + " must declare exactly one of cents or tiers");
        }

        if (hasCents) {
            if (present(node, "reset")) {
                throw new IllegalArgumentException(
                        where + " declares reset, which only applies to a band with tiers");
            }
            return Band.parse(from, to, daySelector(node), rate(node, "cents", inclusive));
        }

        if (!present(node, "reset")) {
            throw new IllegalArgumentException(
                    where + " has tiers and so needs a reset period");
        }
        return Band.parseTiered(from, to, daySelector(node),
                ResetPeriod.valueOf(text(node, "reset")),
                tiers(where, node.get("tiers"), inclusive));
    }

    /**
     * A separate rate for the controlled circuit, with an optional energised window.
     *
     * <p>Stating neither {@code from} nor {@code to} means the circuit is energised all day,
     * which is how a plan that publishes no window behaves.
     */
    private static ControlledLoad controlledLoad(
            String planId, JsonNode node, boolean inclusive) {
        var from = node.get("from");
        var to = node.get("to");
        if ((from == null || from.isNull()) != (to == null || to.isNull())) {
            throw new IllegalArgumentException("Plan " + planId
                    + " controlledLoad needs both from and to, or neither");
        }
        var cents = rate(node, "cents", inclusive);
        if (from == null || from.isNull()) {
            return ControlledLoad.anyTime(cents);
        }
        return new ControlledLoad(cents,
                Band.parseMinuteOfDay(from.asText()),
                endMinuteOfDay(to.asText()));
    }

    /** "00:00" as an end time means the end of the day, as it does for a band. */
    private static int endMinuteOfDay(String clockTime) {
        int minute = Band.parseMinuteOfDay(clockTime);
        return minute == 0 ? Band.MINUTES_PER_DAY : minute;
    }

    private static Tiered tiered(String planId, JsonNode node, boolean inclusive) {
        return new Tiered(
                ResetPeriod.valueOf(text(node, "reset")),
                tiers("Plan " + planId + " tiered", node.get("tiers"), inclusive));
    }

    private static List<Tier> tiers(String where, JsonNode tierNodes, boolean inclusive) {
        if (tierNodes == null || !tierNodes.isArray() || tierNodes.isEmpty()) {
            throw new IllegalArgumentException(where + " has no tiers");
        }
        var tiers = new ArrayList<Tier>();
        for (JsonNode tier : tierNodes) {
            JsonNode upTo = tier.get("upToKWh");
            tiers.add(new Tier(
                    upTo == null || upTo.isNull() ? null : new BigDecimal(upTo.asText()),
                    rate(tier, "cents", inclusive)));
        }
        return tiers;
    }

    private static Demand demand(JsonNode node, boolean inclusive) {
        return new Demand(
                Band.parseMinuteOfDay(text(node, "from")),
                Band.parseMinuteOfDay(text(node, "to")),
                daySelector(node),
                ResetPeriod.valueOf(text(node, "reset")),
                rate(node, "centsPerKWPerDay", inclusive));
    }

    private static Discount discount(JsonNode node) {
        // Discounts are proportions or already-final amounts; GST does not apply to them.
        return new Discount(
                text(node, "name"),
                DiscountBasis.valueOf(text(node, "basis")),
                DiscountScope.valueOf(text(node, "scope")),
                new BigDecimal(text(node, "value")),
                present(node, "condition") ? text(node, "condition") : null);
    }

    private static DaySelector daySelector(JsonNode node) {
        JsonNode days = node.get("days");
        return days == null || days.isNull()
                ? DaySelector.ALL
                : DaySelector.valueOf(days.asText());
    }

    private static boolean present(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    private static BigDecimal rate(JsonNode node, String field, boolean inclusive) {
        var value = new BigDecimal(text(node, field));
        return inclusive ? value : value.multiply(GST_MULTIPLIER, MathContext.DECIMAL64);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value.asText().trim();
    }

    private static LocalDate date(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return LocalDate.parse(value.asText().trim());
    }
}
