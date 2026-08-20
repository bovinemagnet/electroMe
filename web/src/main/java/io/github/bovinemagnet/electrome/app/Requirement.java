package io.github.bovinemagnet.electrome.app;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a plan asks a household to own or join before it will sell to them.
 *
 * <p>Several of the cheapest published plans are conditional on equipment: solar, a battery, an
 * electric vehicle, a membership. A ranking that puts those at the top without saying so
 * presents a saving the household may have no way to take.
 *
 * <p>Classification comes from free text because the standard's own enum does not carry it —
 * every Victorian eligibility entry is published as {@code type: OTHER}. That makes this
 * necessarily approximate, which is why {@link #OTHER} exists rather than a forced guess, and
 * why the requirement's own words are always shown beside the label.
 */
public enum Requirement {
    SOLAR("Solar required", "solar panel|solar pv|solar system"),
    BATTERY("Battery required", "battery|batteries"),
    ELECTRIC_VEHICLE("EV required", "electric vehicle|\\bev\\b|\\bevs\\b"),
    MEMBERSHIP("Membership required", "member|membership|subscription|netflix"),
    CONCESSION("Concession card required",
            "senior|pensioner|card ?holder|concession|health care card"),
    OTHER("Conditions apply", null);

    private final String label;
    private final Pattern pattern;

    Requirement(String label, String regex) {
        this.label = label;
        this.pattern = regex == null ? null : Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public String label() {
        return label;
    }

    /**
     * Something a household could plausibly go out and acquire.
     *
     * <p>{@link #OTHER} is not: it means the eligibility text says something this code could
     * not classify, so it can never be ticked off as met. Treating it as satisfiable would let
     * a plan into the verdict on the strength of a requirement nobody read.
     */
    public boolean ownable() {
        return this != OTHER;
    }

    /** Every requirement the published eligibility text states, in declaration order. */
    public static Set<Requirement> of(List<String> eligibility) {
        var found = new LinkedHashSet<Requirement>();
        if (eligibility == null || eligibility.isEmpty()) {
            return Set.of();
        }
        for (var text : eligibility) {
            if (text == null || text.isBlank()) {
                continue;
            }
            boolean classified = false;
            for (var requirement : values()) {
                if (requirement.pattern != null && requirement.pattern.matcher(text).find()) {
                    found.add(requirement);
                    classified = true;
                }
            }
            if (!classified) {
                found.add(OTHER);
            }
        }
        return Set.copyOf(found);
    }

    /** Reads a capability the household says it has, or null for anything unrecognised. */
    public static Requirement parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var wanted = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (var requirement : values()) {
            if (requirement.name().equals(wanted)) {
                return requirement;
            }
        }
        return "EV".equals(wanted) ? ELECTRIC_VEHICLE : null;
    }
}
