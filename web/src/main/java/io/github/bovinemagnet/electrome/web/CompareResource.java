package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.ComparisonService;
import io.github.bovinemagnet.electrome.app.PlanMatrix;
import io.github.bovinemagnet.electrome.app.PlanStore;
import io.github.bovinemagnet.electrome.app.UsageStore;
import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.view.MatrixView;
import io.github.bovinemagnet.electrome.view.Shell;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Two to four plans side by side, and what separates them.
 *
 * <p>Below two there is nothing to compare. Above four the columns stop being readable and the
 * plan browser is the better tool, so the surplus is named rather than rendered.
 */
@Path("/compare")
public class CompareResource {

    /** Above this the side-by-side columns stop being readable. */
    private static final int MAX_PLANS = 4;

    private static final int MIN_PLANS = 2;

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance compare(Shell shell, MatrixView view, List<String> unknown,
                String plansQuery);
    }

    @Inject UsageStore usage;
    @Inject PlanStore plans;
    @Inject ComparisonService comparisons;
    @Inject PlanBrowsing browsing;

    /**
     * @param selected plan identifiers, either repeated or comma separated; checkboxes in the
     *     browser produce the first form and a hand-written link the second
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance compare(
            @QueryParam("plans") List<String> selected,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {

        var range = browsing.range(from, to);
        var shell = shell(range);
        var requested = identifiers(selected);

        var resolved = new ArrayList<Plan>();
        var unknown = new ArrayList<String>();
        for (var id : requested) {
            plans.byId(id).ifPresentOrElse(resolved::add, () -> unknown.add(id));
        }

        // Beyond the column limit the surplus is named, not silently rendered or silently lost.
        var surplus = new ArrayList<String>();
        if (resolved.size() > MAX_PLANS) {
            for (var extra : resolved.subList(MAX_PLANS, resolved.size())) {
                surplus.add(extra.id());
            }
            resolved = new ArrayList<>(resolved.subList(0, MAX_PLANS));
        }

        if (resolved.size() < MIN_PLANS) {
            // A one-column matrix is not a comparison. Say so, and point at the browser.
            return Templates.compare(shell, null, List.copyOf(unknown), plansQuery(requested));
        }

        var bills = new ArrayList<BillBreakdown>();
        for (var plan : resolved) {
            bills.add(comparisons.cost(plan, range));
        }

        var view = MatrixView.of(
                PlanMatrix.of(bills, range), usage.usage(), range,
                List.copyOf(unknown), List.copyOf(surplus));

        return Templates.compare(shell, view, List.copyOf(unknown), plansQuery(requested));
    }

    /**
     * Splits and de-duplicates the requested identifiers, preserving the order given.
     *
     * <p>Comparing a plan against itself would produce a matrix with two identical columns and a
     * gap of zero, which tells a reader nothing.
     */
    private static List<String> identifiers(List<String> selected) {
        var ids = new LinkedHashSet<String>();
        if (selected != null) {
            for (var value : selected) {
                if (value == null) {
                    continue;
                }
                for (var part : value.split(",")) {
                    var trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        ids.add(trimmed);
                    }
                }
            }
        }
        return List.copyOf(ids);
    }

    /** The selection as a query fragment, so links out of the screen come back to it. */
    private static String plansQuery(List<String> ids) {
        if (ids.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        for (var id : ids) {
            parts.add("plans=" + java.net.URLEncoder.encode(
                    id, java.nio.charset.StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }

    private Shell shell(DateRange range) {
        if (!usage.loaded()) {
            return Shell.unloaded(usage.loadError());
        }
        return Shell.of("compare", range, usage.available(), plans.plans().size(),
                plans.loadErrors());
    }
}
