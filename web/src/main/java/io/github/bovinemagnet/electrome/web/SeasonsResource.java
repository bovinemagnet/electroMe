package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.PlanStore;
import io.github.bovinemagnet.electrome.app.SeasonSplit;
import io.github.bovinemagnet.electrome.app.SeasonalReport;
import io.github.bovinemagnet.electrome.app.SeasonalService;
import io.github.bovinemagnet.electrome.app.UsageStore;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.view.Shell;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Every plan over each half of the year, and whether switching between them pays.
 *
 * <p>A household whose consumption changes shape with the season may be served better by two
 * tariffs than by one, and no ranking of annual totals can show it. This screen asks the
 * question directly.
 */
@Path("/seasons")
public class SeasonsResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance seasons(
                Shell shell, SeasonalReport report, List<SeasonSplit.MonthChoice> months);
    }

    @Inject UsageStore usage;
    @Inject PlanStore plans;
    @Inject SeasonalService seasons;
    @Inject PlanBrowsing browsing;

    /**
     * @param seasonFrom first month of the first season, by name, abbreviation or number
     * @param seasonTo last month of the first season, inclusive
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance seasons(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("seasonFrom") String seasonFrom,
            @QueryParam("seasonTo") String seasonTo) {

        var range = browsing.range(from, to);
        var split = SeasonSplit.of(seasonFrom, seasonTo);
        return Templates.seasons(
                shell(range), seasons.report(range, split), SeasonSplit.choices());
    }

    private Shell shell(DateRange range) {
        if (!usage.loaded()) {
            return Shell.unloaded(usage.loadError());
        }
        return Shell.of("seasons", range, usage.available(), plans.plans().size(),
                plans.loadErrors());
    }
}
