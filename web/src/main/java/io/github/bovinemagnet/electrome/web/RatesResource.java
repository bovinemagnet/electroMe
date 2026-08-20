package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.PlanPage;
import io.github.bovinemagnet.electrome.app.PlanStore;
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
 * Every plan's published rates, side by side.
 *
 * <p>The browser answers "what would this cost me"; this answers "what does it charge". They
 * are different questions — a tariff's rates are a fact about the tariff, while its total is a
 * fact about this household — and a reader deciding whether to shift a pool pump into the
 * middle of the day wants the first.
 *
 * <p>Built from the same criteria and the same costing as the browser, so the two screens
 * cannot rank the same plans differently.
 */
@Path("/rates")
public class RatesResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance rates(Shell shell, PlanPage page);
    }

    @Inject UsageStore usage;
    @Inject PlanStore plans;
    @Inject PlanBrowsing browsing;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance rates(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("search") String search,
            @QueryParam("requirements") String requirements,
            @QueryParam("retailers") List<String> retailers,
            @QueryParam("shape") String shape,
            @QueryParam("sort") String sort,
            @QueryParam("limit") String limit,
            @QueryParam("have") List<String> have,
            @QueryParam("unconditional") String unconditional,
            @QueryParam("minSaving") String minimumSaving) {

        var range = browsing.range(from, to);
        return Templates.rates(shell(range), page(range, search, requirements, retailers, shape,
                sort, limit, have, unconditional, minimumSaving));
    }

    private PlanPage page(DateRange range, String search, String requirements,
            List<String> retailers, String shape, String sort, String limit, List<String> have,
            String unconditional, String minimumSaving) {
        return browsing.page(range, browsing.query(search, requirements, retailers, shape, sort,
                limit, have, unconditional, minimumSaving));
    }

    private Shell shell(DateRange range) {
        if (!usage.loaded()) {
            return Shell.unloaded(usage.loadError());
        }
        return Shell.of("rates", range, usage.available(), plans.plans().size(),
                plans.loadErrors());
    }
}
