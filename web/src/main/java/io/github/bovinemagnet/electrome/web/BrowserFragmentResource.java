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
            @QueryParam("limit") String limit) {

        var range = browsing.range(from, to);
        var query = browsing.query(search, requirements, retailers, shape, sort, limit);
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
