package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.PlanStore;
import io.github.bovinemagnet.electrome.app.UsageStore;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;

/** The page shell. Everything inside it is loaded by HTMX. */
@Path("/")
public class HomeResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance home(
                boolean loaded,
                String loadError,
                List<String> planErrors,
                LocalDate availableFrom,
                LocalDate availableTo,
                LocalDate windowFrom,
                LocalDate windowTo,
                int planCount);
    }

    @Inject UsageStore usage;
    @Inject PlanStore plans;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home() {
        var available = usage.available();
        var window = usage.defaultWindow();
        return Templates.home(
                usage.loaded(),
                usage.loadError(),
                plans.loadErrors(),
                available.from(),
                available.to(),
                window.from(),
                window.to(),
                plans.plans().size());
    }
}
