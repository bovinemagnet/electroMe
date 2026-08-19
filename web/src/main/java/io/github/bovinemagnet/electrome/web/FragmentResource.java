package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.AnalysisService;
import io.github.bovinemagnet.electrome.app.ComparisonService;
import io.github.bovinemagnet.electrome.app.PlanStore;
import io.github.bovinemagnet.electrome.app.UsageStore;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.ingest.DataQualityReport;
import io.github.bovinemagnet.electrome.view.Dashboard;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** HTMX fragment endpoints. Each returns rendered HTML, never JSON. */
@Path("/fragments")
public class FragmentResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance dashboard(Dashboard dashboard);

        static native TemplateInstance quality(
                DataQualityReport report, List<String> findings,
                String heaviestDay, String heaviestKWh, String medianKWh);
    }

    @Inject UsageStore usage;
    @Inject PlanStore planStore;
    @Inject ComparisonService comparisons;
    @Inject AnalysisService analyses;

    @GET
    @Path("/dashboard")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard(
            @QueryParam("from") String from, @QueryParam("to") String to) {
        var range = range(from, to);
        return Templates.dashboard(
                Dashboard.of(comparisons.compare(range), analyses.analyse(range), range));
    }

    @GET
    @Path("/quality")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance quality() {
        var report = usage.report();
        var analysis = analyses.analyse(usage.available());
        var heaviest = analysis.heaviestDay();
        return Templates.quality(
                report,
                report.summary(),
                heaviest == null ? "" : heaviest.toString(),
                heaviest == null ? "" : format(analysis.dailyTotals().get(heaviest)),
                format(analysis.medianDailyKWh()));
    }

    @POST
    @Path("/plans/reload")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance reloadPlans(
            @QueryParam("from") String from, @QueryParam("to") String to) {
        planStore.reload();
        return dashboard(from, to);
    }

    private static String format(java.math.BigDecimal value) {
        return value == null ? "" : value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Parses the requested window, falling back to the default and rejecting nonsense. */
    private DateRange range(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return usage.defaultWindow();
        }
        try {
            return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Dates must be yyyy-MM-dd, got " + from + " and " + to);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
