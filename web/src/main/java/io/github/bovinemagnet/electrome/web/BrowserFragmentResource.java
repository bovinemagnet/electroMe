package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.PlanPage;
import io.github.bovinemagnet.electrome.view.PlanDetail;
import io.github.bovinemagnet.electrome.view.Shell;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** The plan browser's HTMX fragments. Each returns rendered HTML, never JSON. */
@Path("/fragments/browser")
public class BrowserFragmentResource {

    @CheckedTemplate(basePath = "PlanBrowserResource")
    static class Templates {
        static native TemplateInstance results(Shell shell, PlanPage page);

        static native TemplateInstance detail(Shell shell, PlanDetail detail);
    }

    @Inject PlanBrowsing browsing;
    @Inject io.github.bovinemagnet.electrome.app.Shortlist shortlist;

    /**
     * Adds the ticked plans to the household's shortlist and re-renders the table.
     *
     * <p>A POST because it changes something that outlives the request: the selection is
     * written beside the plan files, so it is still there after a restart.
     */
    @jakarta.ws.rs.POST
    @jakarta.ws.rs.Path("/shortlist")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pick(
            @jakarta.ws.rs.FormParam("plans") List<String> plans,
            @jakarta.ws.rs.FormParam("from") String from,
            @jakarta.ws.rs.FormParam("to") String to) {

        shortlist.add(plans == null ? List.of() : plans);
        var range = browsing.range(from, to);
        return Templates.results(
                browsing.shell(range), browsing.page(range, browsing.query(
                        null, null, List.of(), null, null, null)));
    }

    /** Drops one plan from the shortlist and re-renders. */
    @jakarta.ws.rs.POST
    @jakarta.ws.rs.Path("/shortlist/remove")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance unpick(
            @jakarta.ws.rs.FormParam("plan") String planId,
            @jakarta.ws.rs.FormParam("from") String from,
            @jakarta.ws.rs.FormParam("to") String to) {

        shortlist.remove(planId);
        var range = browsing.range(from, to);
        return Templates.results(
                browsing.shell(range), browsing.page(range, browsing.query(
                        null, null, List.of(), null, null, null)));
    }

    /** The results table alone, so applying a criterion does not reload the screen. */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance results(
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
        var query = browsing.query(search, requirements, retailers, shape, sort, limit,
                have, unconditional, minimumSaving);
        return Templates.results(browsing.shell(range), browsing.page(range, query));
    }

    /** One plan's detail panel. */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(
            @PathParam("id") String id,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {

        var range = browsing.range(from, to);
        return Templates.detail(browsing.shell(range), browsing.detail(id, range));
    }
}
