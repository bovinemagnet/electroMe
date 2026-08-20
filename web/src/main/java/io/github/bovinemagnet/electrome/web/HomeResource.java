package io.github.bovinemagnet.electrome.web;

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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** The page shell. Everything inside it is loaded by HTMX. */
@Path("/")
public class HomeResource {

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance home(Shell shell);
    }

    @Inject UsageStore usage;
    @Inject PlanStore plans;

    /**
     * The window arrives in the query string so that arriving from another screen keeps it.
     *
     * <p>Absent, it falls back to the default window rather than failing: a first visit carries
     * no parameters.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home(
            @QueryParam("from") String from, @QueryParam("to") String to) {
        if (!usage.loaded()) {
            return Templates.home(Shell.unloaded(usage.loadError()));
        }
        return Templates.home(Shell.of("dashboard", range(from, to), usage.available(),
                plans.plans().size(), plans.loadErrors()));
    }

    private DateRange range(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return usage.defaultWindow();
        }
        try {
            return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Dates must be yyyy-MM-dd");
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
