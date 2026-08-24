package io.github.bovinemagnet.electrome.market.cdr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.github.bovinemagnet.electrome.core.tariff.Membership;
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
import java.util.regex.Pattern;
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

        membership(contract).ifPresent(charges::add);
        demand(tariffPeriods, planId).ifPresent(charges::add);
        controlledLoad(contract, planId).ifPresent(charges::add);
        charges.addAll(discounts(contract, planId));
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
                            + "|member|netflix|concession|must have an"
                            // A card is not something a household can simply go and get, and a
                            // seniors or pensioner offer priced as though anyone could take it
                            // puts a saving at the top of the ranking that most readers cannot.
                            + "|senior|pensioner|card ?holder|health care card",
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
     * Fees and incentives, for display beside the plan.
     *
     * <p>Read separately from the tariff and never costed. A detail view that shows rates while
     * silently omitting a monthly membership fee would mislead precisely where a reader has gone
     * looking for detail, so they are captured; but {@code fees[].amount} is published GST
     * inclusive while {@code unitPrice} is exclusive, so folding them into the engine mixes two
     * tax bases and is a separate decision.
     *
     * <p>An unreadable response yields no extras rather than an error: this block is optional
     * decoration, and the detail panel must still render without it.
     */
    public static PlanExtras extrasOf(String detailJson) {
        JsonNode contract;
        try {
            contract = MAPPER.readTree(detailJson).path("data").path("electricityContract");
        } catch (IOException e) {
            return PlanExtras.none();
        }

        var fees = new ArrayList<PlanFee>();
        for (JsonNode entry : contract.path("fees")) {
            BigDecimal amount = decimalOrNull(entry.path("amount"));
            BigDecimal rate = decimalOrNull(entry.path("rate"));
            // A fee stating neither an amount nor a rate says nothing a reader can act on.
            if (amount == null && rate == null) {
                continue;
            }
            fees.add(new PlanFee(
                    entry.path("type").asText("OTHER"),
                    entry.path("term").asText(""),
                    rate == null ? amount : null,
                    rate,
                    entry.path("description").asText("").trim()));
        }

        var incentives = new ArrayList<PlanIncentive>();
        for (JsonNode entry : contract.path("incentives")) {
            incentives.add(new PlanIncentive(
                    entry.path("displayName").asText("").trim(),
                    entry.path("category").asText("OTHER"),
                    entry.path("description").asText("").trim(),
                    entry.path("eligibility").asText("").trim()));
        }

        // A fixed-amount discount is a one-off credit rather than a rate, so it belongs
        // beside the fees: published, shown, and deliberately not costed.
        for (JsonNode entry : contract.path("discounts")) {
            if (!"fixedAmount".equals(entry.path("methodUType").asText(""))) {
                continue;
            }
            BigDecimal amount = decimalOrNull(entry.path("fixedAmount").path("amount"));
            incentives.add(new PlanIncentive(
                    entry.path("displayName").asText("Discount").trim(),
                    entry.path("category").asText("OTHER"),
                    entry.path("description").asText("").trim(),
                    amount == null ? "" : "$" + amount.toPlainString() + " off the bill"));
        }

        var variation = contract.path("variation").asText("").trim();
        return new PlanExtras(fees, incentives, marketLinked(variation), variation);
    }

    /**
     * A recurring fee the household must pay to be on the plan.
     *
     * <p>The only published fee that belongs in a total. Every other kind — paper bills, card
     * processing, dishonours, disconnection, exit — is a fact about what the household does
     * rather than about the tariff, and costing those would charge a reader for behaviour they
     * have not got. Across the whole AusNet register exactly one retailer charges a membership.
     */
    private static Optional<Charge> membership(JsonNode contract) {
        for (JsonNode fee : contract.path("fees")) {
            if (!"MEMBERSHIP".equalsIgnoreCase(fee.path("type").asText(""))) {
                continue;
            }
            var perYear = membershipPerYear(
                    fee.path("amount").asText(null), fee.path("description").asText(""));
            if (perYear == null) {
                // Left uncosted and still shown as a fee, which is what happens to every fee
                // this mapper cannot read with confidence.
                continue;
            }
            return Optional.of(Membership.perYear("Membership fee", perYear));
        }
        return Optional.empty();
    }

    /**
     * What a membership costs for a year, or null where that cannot be established.
     *
     * <p>The published {@code term} is not usable. Amber record a yearly total of three hundred
     * dollars under the term FIXED, which in the standard means a one-off amount, and state the
     * real period — twenty-five dollars a month — only in free text. So the period is read from
     * the description and then checked against the amount: a monthly figure has to multiply up
     * to the published total, and a yearly figure has to equal it.
     *
     * <p>Where the two disagree, or where no period is stated at all, nothing is costed. That
     * leaves the plan understated by a fee the screen still shows and flags, which is the
     * better of the two errors: overstating it would put a number on the bill that nobody
     * published.
     */
    static BigDecimal membershipPerYear(String amount, String description) {
        if (amount == null || amount.isBlank() || description == null) {
            return null;
        }
        BigDecimal published;
        try {
            published = new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (published.signum() <= 0) {
            return null;
        }

        var monthly = statedAmount(description, MONTHLY_FEE);
        if (monthly != null) {
            return monthly.multiply(TWELVE).compareTo(published) == 0 ? published : null;
        }
        var yearly = statedAmount(description, YEARLY_FEE);
        if (yearly != null) {
            return yearly.compareTo(published) == 0 ? published : null;
        }
        return null;
    }

    /**
     * The amount from whichever alternative in the pattern matched.
     *
     * <p>"$199 per year" and "annual membership of $199" put the figure either side of the
     * period word, so the pattern has two capturing groups and only one of them is filled.
     */
    private static BigDecimal statedAmount(String description, Pattern pattern) {
        var matcher = pattern.matcher(description);
        if (!matcher.find()) {
            return null;
        }
        for (int group = 1; group <= matcher.groupCount(); group++) {
            var found = matcher.group(group);
            if (found == null) {
                continue;
            }
            try {
                return new BigDecimal(found.replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Whether the retailer says this plan's rates follow the wholesale market.
     *
     * <p>There is no field for this. The register models a tariff as a set of published rates,
     * and a retailer selling exposure to the spot market has to publish one anyway — Amber
     * publish 40.2c and say in {@code variation} that it is illustrative. So this reads what
     * they say.
     *
     * <p>Deliberately narrow. "Pass through" is not a trigger: a dozen ordinary plans use it for
     * network charges recovered at cost, which is how every plan works. "Subject to change" is
     * not a trigger either — almost every plan in the register says that, and a filter that
     * matched it would hide the whole market. Naming the wholesale or spot market is the claim
     * that actually distinguishes these plans, and across the AusNet network it selects exactly
     * the two retailers that sell them.
     */
    static boolean marketLinked(String variation) {
        if (variation == null || variation.isBlank()) {
            return false;
        }
        var text = variation.toLowerCase(java.util.Locale.ROOT);
        return text.contains("wholesale")
                || text.contains("spot price")
                || text.contains("spot market");
    }

    private static final BigDecimal TWELVE = new BigDecimal("12");

    /** "$25 per month", "$25 monthly", "$25 a month". */
    private static final Pattern MONTHLY_FEE = Pattern.compile(
            "\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:\\(?[^)]*\\)?\\s*)?"
                    + "(?:per month|a month|monthly|/month|pm\\b)",
            Pattern.CASE_INSENSITIVE);

    /** "$199 per year", "Annual membership of $199", "$199 a year". */
    private static final Pattern YEARLY_FEE = Pattern.compile(
            "\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:\\(?[^)]*\\)?\\s*)?"
                    + "(?:per year|per annum|a year|annually|yearly|/year|pa\\b)"
                    + "|annual[^$]{0,40}\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);

    /** Absent, null or unparseable all mean "not stated", which is not an error here. */
    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
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
            JsonNode windows = rateEntry.path("timeOfUse");
            if (!windows.isArray() || windows.isEmpty()) {
                throw new UnmappablePlanException(planId, "a rate entry has no timeOfUse windows");
            }

            // A window's blocks reset daily unless the register says otherwise. Victorian
            // capped windows publish no period at all, and a fifty kilowatt hour allowance on a
            // four hour window is a daily one by inspection: it is twelve kilowatts sustained.
            var blocks = blocks(rates, planId, ResetPeriod.DAILY);
            if (blocks.tiers().size() != rates.size()) {
                throw new UnmappablePlanException(planId, "a time-of-use rate entry has "
                        + rates.size() + " rate rows that do not form ascending volume blocks");
            }

            boolean capped = blocks.tiers().size() > 1;
            if (!capped) {
                for (JsonNode window : windows) {
                    bands.add(new Band(
                            Band.parseMinuteOfDay(window.path("startTime").asText()),
                            endMinute(window.path("endTime").asText()),
                            daySelector(window, planId),
                            blocks.tiers().get(0).centsPerKWh()));
                }
                continue;
            }

            // One allowance, so one band. Where the register splits a window at midnight —
            // 15:00 to 00:00 and 00:00 to 11:00 is one overnight stretch published as two —
            // the pieces rejoin into a single wrapping band that shares the allowance as the
            // tariff does. Windows that genuinely do not touch cannot: a band owns its own
            // allowance, so mapping them separately would hand the household the cap twice.
            var merged = mergeContiguous(windows, planId);
            if (merged.isEmpty()) {
                throw new UnmappablePlanException(planId, "one capped rate covers "
                        + windows.size() + " separate windows, sharing a single allowance the "
                        + "model cannot express");
            }
            bands.add(new Band(merged.get()[0], merged.get()[1],
                    daySelector(windows.get(0), planId), blocks.reset(), blocks.tiers()));
        }
        if (bands.isEmpty()) {
            throw new UnmappablePlanException(planId, "no time-of-use bands");
        }
        return new TimeOfUse(bands);
    }

    /**
     * Several published windows as one, where they meet end to start.
     *
     * <p>The register splits a window that crosses midnight into two entries, because a time of
     * day cannot express "until 11:00 tomorrow". Rejoining them matters only for a capped rate,
     * where the two halves share one allowance: a daily block over 15:00 to 11:00 is fifteen
     * kilowatt hours across the pair, not fifteen in each.
     *
     * <p>Returns empty where the windows do not form one chain, or where they do not agree on
     * which days they apply to.
     */
    private static Optional<int[]> mergeContiguous(JsonNode windows, String planId) {
        var pieces = new ArrayList<int[]>();
        DaySelector days = null;
        for (JsonNode window : windows) {
            DaySelector selector = daySelector(window, planId);
            if (days != null && days != selector) {
                return Optional.empty();
            }
            days = selector;
            pieces.add(new int[] {
                    Band.parseMinuteOfDay(window.path("startTime").asText()),
                    endMinute(window.path("endTime").asText())});
        }
        if (pieces.size() == 1) {
            return Optional.of(pieces.get(0));
        }

        for (int first = 0; first < pieces.size(); first++) {
            var remaining = new ArrayList<>(pieces);
            var head = remaining.remove(first);
            int from = head[0];
            int to = head[1];
            boolean extended = true;
            while (extended && !remaining.isEmpty()) {
                extended = false;
                for (var piece = remaining.iterator(); piece.hasNext();) {
                    var next = piece.next();
                    if (to % Band.MINUTES_PER_DAY == next[0] % Band.MINUTES_PER_DAY) {
                        to = next[1];
                        piece.remove();
                        extended = true;
                        break;
                    }
                }
            }
            // A chain that consumes every piece and does not swallow the whole day.
            if (remaining.isEmpty() && from % Band.MINUTES_PER_DAY != to % Band.MINUTES_PER_DAY) {
                return Optional.of(new int[] {from, to});
            }
        }
        return Optional.empty();
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

        var blocks = blocks(rates, planId, ResetPeriod.QUARTERLY);
        if (blocks.tiers().size() == 1) {
            return new FlatRate(blocks.tiers().get(0).centsPerKWh());
        }
        // Most Victorian block tariffs price every block identically, and then how often the
        // allowance resets changes nothing. Where the blocks are priced differently it changes
        // everything — fifteen kilowatt hours at the cheaper rate is a different tariff daily
        // from quarterly — and a register that states no period gives no way to tell. Refused
        // rather than guessed at, on the same principle as a capped window.
        if (!blocks.periodStated() && !blocks.pricedIdentically()) {
            throw new UnmappablePlanException(planId,
                    "a volume block states no period and its blocks are priced differently, so "
                            + "there is no way to tell whether the allowance is daily or "
                            + "quarterly");
        }
        return new Tiered(blocks.reset(), blocks.tiers());
    }

    /**
     * Cumulative volume blocks read from a rates array, and how often they start again.
     *
     * @param periodStated false when the register gave no period and the reset is this
     *     mapper's default rather than the retailer's word
     */
    private record Blocks(List<Tier> tiers, ResetPeriod reset, boolean periodStated) {

        /** True when every block charges the same, so how often it resets changes nothing. */
        boolean pricedIdentically() {
            return tiers.stream().map(Tier::centsPerKWh).distinct().count() <= 1;
        }
    }

    /**
     * Walks a CDR {@code rates} array into ascending blocks.
     *
     * <p>Three encodings occur in the wild: a quarterly volume with period P3M, a daily volume,
     * and a trailing balance row with no volume. All produce the same shape here.
     *
     * <p>Shared by the single-rate and the time-of-use paths, which read the same structure and
     * differ only in what a missing period defaults to. Writing it twice is how the two came to
     * disagree in the first place: the time-of-use path read the first row and dropped the rest.
     */
    private static Blocks blocks(JsonNode rates, String planId, ResetPeriod defaultReset) {
        var tiers = new ArrayList<Tier>();
        ResetPeriod reset = defaultReset;
        boolean periodStated = false;
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
                    periodStated = true;
                }
            } else {
                tiers.add(new Tier(null, price));
                break;
            }
        }
        return new Blocks(tiers, reset, periodStated);
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

    /**
     * A demand charge, where the contract publishes one that the model can hold.
     *
     * <p>Demand is published as its own tariff period beside the usage rates, so a plan
     * carrying one still maps its usage from the first period as before. Reading only that
     * first period is why these plans have been priced as ordinary flat tariffs with their
     * largest single charge missing.
     *
     * <p>Every demand plan on the AusNet register publishes a summer rate and a winter one.
     * Charging a household one of them all year is not an approximation but a different
     * tariff, so a seasonal demand charge is refused rather than picked from.
     */
    private static Optional<Charge> demand(JsonNode tariffPeriods, String planId) {
        JsonNode demandPeriod = null;
        boolean seasonal = false;
        for (JsonNode period : tariffPeriods) {
            if (period.hasNonNull("startDate") || period.hasNonNull("endDate")) {
                seasonal = true;
            }
            if ("demandCharges".equals(period.path("rateBlockUType").asText(""))
                    && demandPeriod == null) {
                demandPeriod = period;
            }
        }
        if (demandPeriod == null) {
            return Optional.empty();
        }
        if (seasonal) {
            throw new UnmappablePlanException(planId,
                    "seasonal demand charges: the tariff publishes a different demand rate per "
                            + "season and the model has no seasonal dimension");
        }

        JsonNode published = demandPeriod.path("demandCharges");
        if (!published.isArray() || published.isEmpty()) {
            throw new UnmappablePlanException(planId, "a demandCharges period has no charges");
        }
        if (published.size() > 1) {
            throw new UnmappablePlanException(planId, "several demand charges on one period, "
                    + "which the model holds only one of");
        }

        JsonNode charge = published.get(0);
        if (charge.hasNonNull("minDemand") || charge.hasNonNull("maxDemand")) {
            throw new UnmappablePlanException(planId,
                    "a demand charge clamped to a minimum or maximum, which the model does not "
                            + "express");
        }
        return Optional.of(new Demand(
                Band.parseMinuteOfDay(charge.path("startTime").asText("00:00")),
                endMinute(charge.path("endTime").asText("00:00")),
                daySelector(charge, planId),
                measurementPeriod(charge.path("measurementPeriod").asText(""), planId),
                cents(charge.path("amount").asText(), planId, "demand amount")));
    }

    /** How often the demand meter starts looking for a new peak. */
    private static ResetPeriod measurementPeriod(String published, String planId) {
        return switch (published) {
            case "DAY" -> ResetPeriod.DAILY;
            case "MONTH" -> ResetPeriod.MONTHLY;
            default -> throw new UnmappablePlanException(
                    planId, "unsupported demand measurement period: " + published);
        };
    }

    /**
     * The separate rate for a controlled circuit.
     *
     * <p>Read from the contract rather than the tariff period, which is why it has been going
     * missing: a household with a hot water circuit was having that energy priced at the
     * ordinary rate on every harvested plan.
     */
    private static Optional<Charge> controlledLoad(JsonNode contract, String planId) {
        JsonNode published = contract.path("controlledLoad");
        if (!published.isArray() || published.isEmpty()) {
            return Optional.empty();
        }
        if (published.size() > 1) {
            throw new UnmappablePlanException(planId,
                    "several controlled load rates, which the model holds only one of");
        }
        JsonNode block = published.get(0);
        String uType = block.path("rateBlockUType").asText("");
        if (!"singleRate".equals(uType)) {
            throw new UnmappablePlanException(
                    planId, "controlled load published as " + uType + ", which is not a rate");
        }
        JsonNode rates = block.path("singleRate").path("rates");
        if (!rates.isArray() || rates.isEmpty()) {
            throw new UnmappablePlanException(planId, "controlled load has no rates");
        }
        if (rates.size() > 1 || rates.get(0).hasNonNull("volume")) {
            throw new UnmappablePlanException(
                    planId, "controlled load published in volume blocks, which the model does "
                            + "not express");
        }
        return Optional.of(ControlledLoad.anyTime(
                cents(rates.get(0).path("unitPrice").asText(), planId, "controlled load rate")));
    }

    /**
     * Discounts, and what a household must do to earn them.
     *
     * <p>Only proportional discounts are costed. Every fixed-amount discount on the register is
     * a one-off sign-up credit — "$200 sign up credit", "welcome credit of $150" — and the
     * model applies a fixed discount once per costing, so costing one would subtract it from
     * every year the household stays rather than the first. Those are carried into
     * {@link #extrasOf} instead, shown beside the plan and not priced.
     */
    private static List<Charge> discounts(JsonNode contract, String planId) {
        var discounts = new ArrayList<Charge>();
        for (JsonNode entry : contract.path("discounts")) {
            if (!"percentOfBill".equals(entry.path("methodUType").asText(""))) {
                continue;
            }
            BigDecimal rate = decimalOrNull(entry.path("percentOfBill").path("rate"));
            // Published as a fraction of the bill. Anything outside that is a schema surprise,
            // and a discount read ten-fold would be a large silent saving.
            if (rate == null || rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw new UnmappablePlanException(
                        planId, "discount rate outside 0 to 1: " + entry.path("percentOfBill"));
            }
            boolean conditional = "CONDITIONAL".equals(entry.path("type").asText(""));
            discounts.add(new Discount(
                    displayNameOr(entry, "Discount"),
                    DiscountBasis.PERCENTAGE,
                    DiscountScope.TOTAL,
                    rate.multiply(new BigDecimal("100")),
                    conditional ? conditionText(entry) : null));
        }
        return discounts;
    }

    /** What the household has to do, in the retailer's own words. */
    private static String conditionText(JsonNode discount) {
        String description = discount.path("description").asText("").trim();
        if (!description.isEmpty()) {
            return description;
        }
        String category = discount.path("category").asText("").trim();
        return category.isEmpty() ? "conditions apply" : category;
    }

    private static String displayNameOr(JsonNode node, String fallback) {
        String name = node.path("displayName").asText("").trim();
        return name.isEmpty() ? fallback : name;
    }

    /**
     * The export credit, flat or by time of day.
     *
     * <p>Feed-in is frequently absent, so no block at all is normal rather than a fault. What is
     * not normal is a block the mapper cannot read: sixty-six plans published for this network
     * pay by time of day and carry no {@code singleTariff}, and reading only that block mapped
     * them with no credit at all. A plan priced as paying nothing for exports looks exactly like
     * a plan that pays nothing for exports, so the omission was invisible.
     */
    private static Optional<Charge> feedIn(JsonNode contract, String planId) {
        for (JsonNode scheme : contract.path("solarFeedInTariff")) {
            JsonNode rates = scheme.path("singleTariff").path("rates");
            if (rates.isArray() && !rates.isEmpty()) {
                return Optional.of(new SolarFeedIn(
                        cents(rates.get(0).path("unitPrice").asText(), planId, "feed-in")));
            }
            JsonNode varying = scheme.path("timeVaryingTariffs");
            if (varying.isArray() && !varying.isEmpty()) {
                return Optional.of(timeVaryingFeedIn(varying, planId));
            }
        }
        return Optional.empty();
    }

    /**
     * One band per published window.
     *
     * <p>Shaped like {@link #timeOfUse}, and for the same reasons: {@code type} is not read
     * because retailers label the same rate three different ways, and one rate entry carries
     * several windows because a stretch crossing midnight cannot be written as one.
     *
     * <p>No merging of contiguous windows, unlike a capped usage band. Where a credit is capped
     * the register publishes it against a single window, so there is nothing to rejoin; where it
     * is not, two touching windows at the same rate are simply two bands that happen to agree
     * and the engine credits them identically.
     */
    private static SolarFeedIn timeVaryingFeedIn(JsonNode varying, String planId) {
        var bands = new ArrayList<Band>();
        for (JsonNode entry : varying) {
            JsonNode rates = entry.path("rates");
            if (!rates.isArray() || rates.isEmpty()) {
                throw new UnmappablePlanException(
                        planId, "a time-varying feed-in entry has no rates");
            }
            // A capped credit: Flow Power pay seventeen cents for the first fifteen kilowatt
            // hours exported in the evening and two cents after. Read as blocks, exactly as a
            // capped usage window is.
            var blocks = blocks(rates, planId, ResetPeriod.DAILY);
            if (blocks.tiers().size() != rates.size()) {
                throw new UnmappablePlanException(planId, "a time-varying feed-in entry has "
                        + rates.size() + " rate rows that do not form ascending volume blocks");
            }

            JsonNode windows = entry.path("timeVariations");
            if (!windows.isArray() || windows.isEmpty()) {
                throw new UnmappablePlanException(
                        planId, "a time-varying feed-in entry has no windows");
            }
            boolean capped = blocks.tiers().size() > 1;
            if (capped && windows.size() > 1) {
                // A band owns its allowance, so splitting one capped entry across windows would
                // hand the household the allowance once per window.
                throw new UnmappablePlanException(planId, "one capped feed-in rate covers "
                        + windows.size() + " separate windows, sharing a single allowance the "
                        + "model cannot express");
            }
            for (JsonNode window : windows) {
                int from = Band.parseMinuteOfDay(window.path("startTime").asText());
                int to = endMinute(window.path("endTime").asText());
                bands.add(capped
                        ? new Band(from, to, daySelector(window, planId),
                                blocks.reset(), blocks.tiers())
                        : new Band(from, to, daySelector(window, planId),
                                blocks.tiers().get(0).centsPerKWh()));
            }
        }
        if (bands.isEmpty()) {
            throw new UnmappablePlanException(planId, "no time-varying feed-in windows");
        }
        return new SolarFeedIn(bands);
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
