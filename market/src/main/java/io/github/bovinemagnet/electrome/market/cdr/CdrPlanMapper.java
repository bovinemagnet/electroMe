package io.github.bovinemagnet.electrome.market.cdr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Charge;
import io.github.bovinemagnet.electrome.core.tariff.DailySupply;
import io.github.bovinemagnet.electrome.core.tariff.DaySelector;
import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.FlatRate;
import io.github.bovinemagnet.electrome.core.tariff.InvalidPlanException;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.PlanValidator;
import io.github.bovinemagnet.electrome.core.tariff.ResetPeriod;
import io.github.bovinemagnet.electrome.core.tariff.SolarFeedIn;
import io.github.bovinemagnet.electrome.core.tariff.Tier;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a CDR plan detail response into a {@link Plan}.
 *
 * <p>Pure: JSON in, domain object out, no network and no state.
 *
 * <p>Published rates are dollars exclusive of GST; the domain holds cents inclusive. The
 * conversion is therefore a single multiplication by 110, verified against a live Victorian
 * Default Offer plan whose published daily supply charge of 1.16581 becomes 128.24 c/day and
 * whose peak rate of 0.43309 becomes 47.64 c/kWh, both matching the regulator to the cent.
 */
public final class CdrPlanMapper {

    /** Dollars exclusive of GST to cents inclusive: multiply by 100, then by 1.1. */
    private static final BigDecimal TO_CENTS_INCLUSIVE = new BigDecimal("110");

    private static final Set<String> ALL_DAYS =
            Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final Set<String> WEEKDAYS = Set.of("MON", "TUE", "WED", "THU", "FRI");
    private static final Set<String> WEEKENDS = Set.of("SAT", "SUN");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CdrPlanMapper() {}

    public static Plan map(String detailJson, DistributionZone zone) {
        JsonNode root;
        try {
            root = MAPPER.readTree(detailJson).path("data");
        } catch (IOException e) {
            throw new UnmappablePlanException("unknown", "response is not valid JSON");
        }

        String planId = root.path("planId").asText("unknown");
        JsonNode contract = root.get("electricityContract");
        if (contract == null || contract.isNull()) {
            throw new UnmappablePlanException(planId, "no electricityContract in the response");
        }

        JsonNode tariffPeriods = contract.get("tariffPeriod");
        if (tariffPeriods == null || !tariffPeriods.isArray() || tariffPeriods.isEmpty()) {
            throw new UnmappablePlanException(planId, "no tariffPeriod in the contract");
        }
        // Seasonal plans publish several periods. Take the first: the model has no seasonal
        // dimension, and mapping one is honest where blending them would not be.
        JsonNode period = tariffPeriods.get(0);

        var charges = new ArrayList<Charge>();

        // Singular. The plural spelling does not exist.
        JsonNode supply = period.get("dailySupplyCharge");
        if (supply != null && !supply.isNull()) {
            charges.add(new DailySupply(cents(supply.asText(), planId, "dailySupplyCharge")));
        }

        String rateBlock = period.path("rateBlockUType").asText("");
        switch (rateBlock) {
            case "timeOfUseRates" -> charges.add(timeOfUse(period, planId));
            case "singleRate" -> charges.add(singleRate(period, planId));
            default -> throw new UnmappablePlanException(
                    planId, "unsupported rateBlockUType: " + rateBlock);
        }

        feedIn(contract, planId).ifPresent(charges::add);

        var plan = new Plan(
                planId,
                root.path("displayName").asText(planId),
                root.path("brandName").asText("Unknown"),
                zone,
                charges,
                true,
                null,
                null);

        try {
            PlanValidator.validate(plan);
        } catch (InvalidPlanException e) {
            throw new UnmappablePlanException(planId, e.getMessage());
        }
        return plan;
    }

    /**
     * Text that states something a household must own or join to be offered a plan.
     *
     * <p>Every eligibility entry in the Victorian data carries {@code type: OTHER}, so the
     * standard's enum is as useless here as it is for time-of-use bands, and classification has
     * to come from the free text.
     *
     * <p>Most of that text is boilerplate — being in the right distribution zone, having an
     * applicable network tariff, having a smart meter, being a residential customer. Flagging on
     * its mere presence marks 99% of plans and therefore says nothing. These patterns pick out
     * the real requirements instead, and on a live harvest separate about a fifth of plans from
     * the rest.
     */
    private static final java.util.regex.Pattern MATERIAL_REQUIREMENT =
            java.util.regex.Pattern.compile(
                    "solar panel|solar pv|battery|electric vehicle|\\bev\\b|bundled with"
                            + "|member|netflix|concession|must have an",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The requirements a household must meet to be offered this plan.
     *
     * <p>Read separately from the tariff, because it does not affect what the plan costs — it
     * affects whether you can have it. Several of the cheapest published plans require solar, a
     * battery, an electric vehicle or a membership, and ranking those alongside plans anyone can
     * sign up to, with no distinction, would present a saving the household cannot take.
     */
    public static List<String> requirementsOf(String detailJson) {
        JsonNode contract;
        try {
            contract = MAPPER.readTree(detailJson).path("data").path("electricityContract");
        } catch (IOException e) {
            return List.of();
        }
        var conditions = new ArrayList<String>();
        for (JsonNode entry : contract.path("eligibility")) {
            String information = entry.path("information").asText("").trim();
            if (!information.isEmpty() && MATERIAL_REQUIREMENT.matcher(information).find()) {
                conditions.add(information);
            }
        }
        return List.copyOf(conditions);
    }

    /**
     * Time-of-use bands.
     *
     * <p>The {@code type} field is never read. Real Victorian plans label a 51.7c evening peak
     * and a 27.7c overnight rate both as SHOULDER, so the label carries no information.
     * Identity comes from the window and the rate.
     *
     * <p>One rate entry may carry several windows, which is how a band spanning midnight is
     * published: 21:00 to 00:00 and 00:00 to 17:00 as two entries at the same price.
     */
    private static TimeOfUse timeOfUse(JsonNode period, String planId) {
        var bands = new ArrayList<Band>();
        for (JsonNode rateEntry : period.path("timeOfUseRates")) {
            JsonNode rates = rateEntry.path("rates");
            if (!rates.isArray() || rates.isEmpty()) {
                throw new UnmappablePlanException(planId, "a timeOfUseRates entry has no rates");
            }
            BigDecimal centsPerKWh =
                    cents(rates.get(0).path("unitPrice").asText(), planId, "unitPrice");

            JsonNode windows = rateEntry.path("timeOfUse");
            if (!windows.isArray() || windows.isEmpty()) {
                throw new UnmappablePlanException(planId, "a rate entry has no timeOfUse windows");
            }
            for (JsonNode window : windows) {
                bands.add(new Band(
                        Band.parseMinuteOfDay(window.path("startTime").asText()),
                        endMinute(window.path("endTime").asText()),
                        daySelector(window, planId),
                        centsPerKWh));
            }
        }
        if (bands.isEmpty()) {
            throw new UnmappablePlanException(planId, "no time-of-use bands");
        }
        return new TimeOfUse(bands);
    }

    /**
     * "00:00" as an end time means the end of the day.
     *
     * <p>A window of 21:00 to 00:00 covers the evening, not a zero-length interval.
     */
    private static int endMinute(String endTime) {
        int minute = Band.parseMinuteOfDay(endTime);
        return minute == 0 ? Band.MINUTES_PER_DAY : minute;
    }

    /** A single rate, which may carry volume blocks. */
    private static Charge singleRate(JsonNode period, String planId) {
        JsonNode rates = period.path("singleRate").path("rates");
        if (!rates.isArray() || rates.isEmpty()) {
            throw new UnmappablePlanException(planId, "singleRate has no rates");
        }

        boolean blocked = false;
        for (JsonNode rate : rates) {
            if (rate.hasNonNull("volume")) {
                blocked = true;
                break;
            }
        }
        if (!blocked) {
            return new FlatRate(
                    cents(rates.get(0).path("unitPrice").asText(), planId, "unitPrice"));
        }

        // Three encodings occur in the wild: a quarterly volume with period P3M, a daily
        // volume, and a trailing balance row with no volume. All produce the same shape here.
        var tiers = new ArrayList<Tier>();
        ResetPeriod reset = ResetPeriod.QUARTERLY;
        BigDecimal cumulative = BigDecimal.ZERO;

        for (int i = 0; i < rates.size(); i++) {
            JsonNode rate = rates.get(i);
            BigDecimal price = cents(rate.path("unitPrice").asText(), planId, "unitPrice");
            boolean last = i == rates.size() - 1;

            if (rate.hasNonNull("volume") && !last) {
                cumulative = cumulative.add(new BigDecimal(rate.get("volume").asText()));
                tiers.add(new Tier(cumulative, price));
                if (rate.hasNonNull("period")) {
                    reset = resetPeriod(rate.get("period").asText(), planId);
                }
            } else {
                tiers.add(new Tier(null, price));
                break;
            }
        }
        if (tiers.size() == 1) {
            return new FlatRate(tiers.get(0).centsPerKWh());
        }
        return new Tiered(reset, tiers);
    }

    private static ResetPeriod resetPeriod(String iso8601, String planId) {
        return switch (iso8601) {
            case "P1D" -> ResetPeriod.DAILY;
            case "P1M" -> ResetPeriod.MONTHLY;
            case "P3M" -> ResetPeriod.QUARTERLY;
            case "P1Y" -> ResetPeriod.ANNUAL;
            default -> throw new UnmappablePlanException(
                    planId, "unsupported block period: " + iso8601);
        };
    }

    /** Feed-in is frequently null, so an absent block is normal rather than a fault. */
    private static Optional<Charge> feedIn(JsonNode contract, String planId) {
        for (JsonNode scheme : contract.path("solarFeedInTariff")) {
            JsonNode rates = scheme.path("singleTariff").path("rates");
            if (rates.isArray() && !rates.isEmpty()) {
                return Optional.of(new SolarFeedIn(
                        cents(rates.get(0).path("unitPrice").asText(), planId, "feed-in")));
            }
        }
        return Optional.empty();
    }

    /**
     * Maps the published day list to a selector.
     *
     * <p>PUBLIC_HOLIDAYS is a valid value in the standard but does not occur in Victorian data,
     * and almost every window uses all seven days. Anything the model cannot express is
     * rejected rather than approximated.
     */
    private static DaySelector daySelector(JsonNode window, String planId) {
        var days = new LinkedHashSet<String>();
        for (JsonNode day : window.path("days")) {
            days.add(day.asText().toUpperCase(Locale.ROOT));
        }
        if (days.isEmpty() || days.equals(ALL_DAYS)) {
            return DaySelector.ALL;
        }
        if (days.equals(WEEKDAYS)) {
            return DaySelector.WEEKDAYS;
        }
        if (days.equals(WEEKENDS)) {
            return DaySelector.WEEKENDS;
        }
        throw new UnmappablePlanException(
                planId, "unrepresentable day selection: " + String.join(",", days));
    }

    /** Dollars exclusive of GST to cents inclusive. */
    private static BigDecimal cents(String dollarsExclusive, String planId, String field) {
        if (dollarsExclusive == null || dollarsExclusive.isBlank()) {
            throw new UnmappablePlanException(planId, "missing " + field);
        }
        try {
            return new BigDecimal(dollarsExclusive).multiply(TO_CENTS_INCLUSIVE);
        } catch (NumberFormatException e) {
            throw new UnmappablePlanException(
                    planId, "unparseable " + field + ": " + dollarsExclusive);
        }
    }
}
