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

/** The plan browser screen: find, filter and inspect any plan. */
@Path("/plans")
public class PlanBrowserResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance browser(Shell shell, PlanPage page, PlanDetail detail);
    }

    @Inject PlanBrowsing browsing;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance browse(
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
        return Templates.browser(browsing.shell(range), browsing.page(range, query), null);
    }

    /**
     * The same screen with one plan opened.
     *
     * <p>A separate URL rather than only a fragment, so a plan can be linked to and returned
     * to: a URL is a complete description of what you are looking at.
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance browseWithDetail(
            @PathParam("id") String id,
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
        return Templates.browser(
                browsing.shell(range), browsing.page(range, query), browsing.detail(id, range));
    }
}
