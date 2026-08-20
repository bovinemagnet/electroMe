package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.ApplianceCatalogue;
import io.github.bovinemagnet.electrome.app.ApplianceService;
import io.github.bovinemagnet.electrome.app.PlanStore;
import io.github.bovinemagnet.electrome.app.UsageStore;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.view.Shell;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.List;

/**
 * What would an appliance cost, when should it run, and does owning one change the plan?
 *
 * <p>The third question is the interesting one. Adding an electric vehicle is not a uniform
 * increase in consumption — it lands almost entirely in the cheapest window — so a plan that
 * loses on current consumption may win comfortably with a car in the household.
 */
@Path("/what-if")
public class WhatIfResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance whatIf(
                Shell shell,
                ApplianceCatalogue.Preset preset,
                List<ApplianceCatalogue.Preset> presets,
                ApplianceService.Outcome outcome,
                BigDecimal energyPerRunKWh,
                BigDecimal powerKW,
                String availableFrom,
                String deadline,
                int runsPerWeek,
                BigDecimal kmPerWeek,
                BigDecimal kWhPerHundredKm);
    }

    @Inject UsageStore usage;
    @Inject PlanStore plans;
    @Inject ApplianceService appliances;
    @Inject io.github.bovinemagnet.electrome.app.ShortlistService shortlist;
    @Inject PlanBrowsing browsing;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance whatIf(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("appliance") String applianceId,
            @QueryParam("energy") BigDecimal energy,
            @QueryParam("power") BigDecimal power,
            @QueryParam("available") String available,
            @QueryParam("by") String deadline,
            @QueryParam("days") Integer runsPerWeek,
            @QueryParam("km") BigDecimal kmPerWeek,
            @QueryParam("kwh100") BigDecimal kWhPerHundredKm) {

        var range = browsing.range(from, to);
        var preset = ApplianceCatalogue.preset(applianceId);

        int runs = runsPerWeek == null ? preset.runsPerWeek() : clampRuns(runsPerWeek);
        Integer availableMinute = minuteOrNull(available);
        Integer deadlineMinute = minuteOrNull(deadline);

        // A driver knows their weekly mileage, not their nightly kilowatt-hours, so the car is
        // described in kilometres and converted here.
        BigDecimal perRun = energy;
        BigDecimal km = kmPerWeek;
        BigDecimal efficiency = kWhPerHundredKm;
        if ("ev".equals(preset.id())) {
            km = km == null ? ApplianceCatalogue.DEFAULT_KM_PER_WEEK : km;
            efficiency = efficiency == null
                    ? ApplianceCatalogue.DEFAULT_KWH_PER_HUNDRED_KM
                    : efficiency;
            if (perRun == null) {
                perRun = ApplianceCatalogue.chargeKWh(km, efficiency, runs);
            }
        }

        var load = ApplianceCatalogue.build(
                preset, perRun, power, availableMinute, deadlineMinute, runs);

        // The shortlist, not the whole register. Scheduling an appliance against three
        // hundred tariffs answers a question nobody asked and buries the four the household
        // actually cares about; a picked plan is here on the same footing as a plan file.
        var outcome = appliances.evaluate(usage.usage(), shortlist.plans(), load, range);

        return Templates.whatIf(
                shell(range), preset, ApplianceCatalogue.all(), outcome,
                perRun == null ? preset.energyPerRunKWh() : perRun,
                power == null ? preset.powerKW() : power,
                clock(availableMinute == null ? preset.availableFromMinute() : availableMinute),
                clock(deadlineMinute == null ? preset.deadlineMinute() : deadlineMinute),
                runs, km, efficiency);
    }

    /** Seven days is every day; anything outside the week is a typo rather than a request. */
    private static int clampRuns(int requested) {
        return Math.max(0, Math.min(7, requested));
    }

    /** "18:00" as minutes past midnight, or null to keep the preset's own default. */
    private static Integer minuteOrNull(String clockTime) {
        if (clockTime == null || clockTime.isBlank()) {
            return null;
        }
        try {
            var parts = clockTime.trim().split(":");
            int minute = Integer.parseInt(parts[0]) * 60
                    + (parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
            if (minute < 0 || minute > 24 * 60) {
                throw new BadRequestException("Times must be between 00:00 and 24:00");
            }
            return minute;
        } catch (NumberFormatException e) {
            throw new BadRequestException("Times must be HH:mm");
        }
    }

    private static String clock(int minuteOfDay) {
        return String.format(
                java.util.Locale.ROOT, "%02d:%02d", (minuteOfDay / 60) % 24, minuteOfDay % 60);
    }

    private Shell shell(DateRange range) {
        if (!usage.loaded()) {
            return Shell.unloaded(usage.loadError());
        }
        return Shell.of("what-if", range, usage.available(), plans.plans().size(),
                plans.loadErrors());
    }
}
