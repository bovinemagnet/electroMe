package io.github.bovinemagnet.electrome.core.tariff;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rejects plans that cannot produce a meaningful bill. */
public final class PlanValidator {

    /** A Monday-to-Sunday week used to exercise every day-of-week selector. */
    private static final LocalDate REFERENCE_MONDAY = LocalDate.of(2025, 8, 25);

    private PlanValidator() {}

    public static void validate(Plan plan) {
        var problems = problems(plan);
        if (!problems.isEmpty()) {
            throw new InvalidPlanException(plan.id(), problems);
        }
    }

    public static List<String> problems(Plan plan) {
        var problems = new ArrayList<String>();

        long supplyCharges = plan.charges().stream().filter(DailySupply.class::isInstance).count();
        if (supplyCharges > 1) {
            problems.add("has " + supplyCharges + " daily supply charges, expected at most one");
        }

        long usageCharges = plan.charges().stream()
                .filter(c -> c instanceof FlatRate || c instanceof TimeOfUse || c instanceof Tiered)
                .count();
        if (usageCharges == 0) {
            problems.add("has no usage charge");
        } else if (usageCharges > 1) {
            problems.add(
                    "has " + usageCharges + " usage charges, expected exactly one usage charge");
        }

        for (var charge : plan.charges()) {
            if (charge instanceof TimeOfUse tou) {
                problems.addAll(tilingProblems(tou.bands(), "priced"));
            }
            // A feed-in that covers only part of the day is indistinguishable from one that
            // pays nothing for the rest, and the two mean different things to a household with
            // solar. Requiring the bands to tile forces the tariff to say which it is.
            if (charge instanceof SolarFeedIn feedIn) {
                problems.addAll(tilingProblems(feedIn.bands(), "credited"));
            }
        }

        return List.copyOf(problems);
    }

    /**
     * Confirms the bands cover every minute of every weekday exactly once.
     *
     * <p>Brute force over 7 days by 1440 minutes rather than reasoning about how day selectors
     * interact. Ten thousand checks is free, and it is correct for any combination.
     *
     * <p>Shared by time-of-use charges and by feed-in credits, which have the same requirement
     * for the same reason. {@code verb} only shapes the message.
     */
    private static List<String> tilingProblems(List<Band> bands, String verb) {
        var problems = new ArrayList<String>();
        var holidays = HolidayCalendar.none();

        for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
            var date = REFERENCE_MONDAY.plusDays(dayOffset);
            DayOfWeek dayOfWeek = date.getDayOfWeek();
            Integer gapStart = null;
            Integer overlapStart = null;

            for (int minute = 0; minute <= Band.MINUTES_PER_DAY; minute++) {
                // A sentinel past the end of the day closes any run still open.
                int matches = minute == Band.MINUTES_PER_DAY
                        ? -1
                        : countMatches(bands, date, minute, holidays);

                if (matches == 0 && gapStart == null) {
                    gapStart = minute;
                } else if (matches != 0 && gapStart != null) {
                    problems.add(describe(dayOfWeek, "is not " + verb + " from", gapStart));
                    gapStart = null;
                }

                if (matches > 1 && overlapStart == null) {
                    overlapStart = minute;
                } else if (matches <= 1 && overlapStart != null) {
                    problems.add(
                            describe(dayOfWeek,
                                    "is " + verb + " by more than one band from", overlapStart));
                    overlapStart = null;
                }
            }
        }
        return problems;
    }

    private static int countMatches(
            List<Band> bands, LocalDate date, int minute, HolidayCalendar holidays) {
        int matches = 0;
        for (var band : bands) {
            if (band.matchesTime(minute) && band.days().matches(date, holidays)) {
                matches++;
            }
        }
        return matches;
    }

    private static String describe(DayOfWeek dayOfWeek, String problem, int minute) {
        return String.format(
                Locale.ROOT, "%s %s %02d:%02d", dayOfWeek, problem, minute / 60, minute % 60);
    }
}
