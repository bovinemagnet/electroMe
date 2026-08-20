package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.MarketPlanSource;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** Triggers and reports on a market harvest. */
@Path("/fragments/market")
public class MarketResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance market(
                boolean enabled, boolean harvested, String zone,
                List<String> summary, List<String> skipReasons, String error);
    }

    @Inject MarketPlanSource market;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance status() {
        return render();
    }

    @POST
    @jakarta.ws.rs.Path("/harvest")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance harvest() {
        market.harvest();
        return render();
    }

    private TemplateInstance render() {
        return Templates.market(
                market.enabled(),
                market.harvested(),
                market.zoneName(),
                market.lastReport().map(r -> r.summary()).orElse(List.of()),
                market.lastReport().map(r -> r.skipReasons()).orElse(List.of()),
                market.harvestError().orElse(null));
    }
}
