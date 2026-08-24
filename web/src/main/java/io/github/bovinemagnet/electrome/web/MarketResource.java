package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.MarketCatalogue;
import io.github.bovinemagnet.electrome.app.MarketPage;
import io.github.bovinemagnet.electrome.app.MarketQuery;
import io.github.bovinemagnet.electrome.app.MarketPlanSource;
import io.github.bovinemagnet.electrome.app.PlanStore;
import io.github.bovinemagnet.electrome.app.SaveReport;
import io.github.bovinemagnet.electrome.app.UsageStore;
import io.github.bovinemagnet.electrome.view.Shell;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Browsing what the register publishes, and keeping the plans worth keeping.
 *
 * <p>The plan browser answers what a tariff would cost this household. This screen answers the
 * question before it: which published tariffs are worth having as files at all. Saving one
 * writes it into the plans directory, after which every other screen treats it exactly like a
 * plan written by hand — because it now is one.
 */
@Path("/market")
public class MarketResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance market(
                Shell shell, MarketPage page, boolean enabled, boolean harvested, String zone,
                String harvestedAt, String error, String directory, SaveReport saved);

        static native TemplateInstance results(
                Shell shell, MarketPage page, String directory, SaveReport saved);
    }

    @Inject MarketPlanSource market;
    @Inject MarketCatalogue catalogue;
    @Inject PlanBrowsing browsing;
    @Inject UsageStore usage;
    @Inject PlanStore plans;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance market(@BeanParam Criteria criteria) {
        return screen(criteria, null);
    }

    /**
     * Reads the register, then shows what came back.
     *
     * <p>A POST because it reaches the network and replaces what the screen is showing. Never
     * on load: the application has to start and be useful with no network at all.
     */
    @POST
    @Path("/harvest")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance harvest(@BeanParam Criteria criteria) {
        market.harvest();
        return screen(criteria, null);
    }

    /** The table alone, for the criteria form to swap in without reloading the screen. */
    @GET
    @Path("/results")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance results(@BeanParam Criteria criteria) {
        return Templates.results(
                shell(criteria), catalogue.page(range(criteria), criteria.query()),
                catalogue.directory().toAbsolutePath().toString(), null);
    }

    /**
     * Writes the ticked plans into the plans directory.
     *
     * <p>The only thing in the application that changes something outside itself, so it answers
     * with what it did to each file rather than with a count.
     */
    @POST
    @Path("/save")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance save(
            @BeanParam Criteria criteria, @FormParam("plans") List<String> planIds) {
        var report = catalogue.save(planIds == null ? List.of() : planIds);
        return Templates.results(
                shell(criteria), catalogue.page(range(criteria), criteria.query()),
                report.directory(), report);
    }

    private TemplateInstance screen(Criteria criteria, SaveReport saved) {
        return Templates.market(
                shell(criteria),
                catalogue.page(range(criteria), criteria.query()),
                market.enabled(),
                market.harvested(),
                market.zoneName(),
                market.harvestedAt().map(Object::toString).orElse(null),
                market.harvestError().orElse(null),
                catalogue.directory().toAbsolutePath().toString(),
                saved);
    }

    private Shell shell(Criteria criteria) {
        if (!usage.loaded()) {
            return Shell.unloaded(usage.loadError());
        }
        return Shell.of("market", range(criteria), usage.available(), plans.plans().size(),
                plans.loadErrors());
    }

    private io.github.bovinemagnet.electrome.core.domain.DateRange range(Criteria criteria) {
        return browsing.range(criteria.from, criteria.to);
    }

    /** The criteria as they arrive, so every method does not repeat eight parameters. */
    public static class Criteria {
        @QueryParam("from") String from;
        @QueryParam("to") String to;
        @QueryParam("search") String search;
        @QueryParam("retailer") String retailer;
        @QueryParam("shape") String shape;
        @QueryParam("sort") String sort;
        @QueryParam("held") String hideHeld;
        @QueryParam("flexible") String includeMarketLinked;
        @QueryParam("limit") String limit;

        MarketQuery query() {
            return MarketQuery.of(
                    search, retailer, shape, sort, hideHeld, includeMarketLinked, limit);
        }
    }
}
