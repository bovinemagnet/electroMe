package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.ComparisonService;
import io.github.bovinemagnet.electrome.app.MarketPlanSource;
import io.github.bovinemagnet.electrome.app.PlanPage;
import io.github.bovinemagnet.electrome.app.PlanQuery;
import io.github.bovinemagnet.electrome.app.PlanQueryService;
import io.github.bovinemagnet.electrome.app.PlanStore;
import io.github.bovinemagnet.electrome.app.UsageStore;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.view.PlanDetail;
import io.github.bovinemagnet.electrome.view.Shell;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Assembles what the plan browser renders.
 *
 * <p>The screen and its fragments are separate resource classes, because a JAX-RS request is
 * matched to one root resource by its path prefix and does not then look elsewhere — a method
 * under {@code /fragments} has to live in a class rooted there. Both need identical assembly,
 * so it lives here rather than being written twice.
 */
@ApplicationScoped
public class PlanBrowsing {

    @Inject UsageStore usage;
    @Inject PlanStore plans;
    @Inject MarketPlanSource market;
    @Inject ComparisonService comparisons;
    @Inject PlanQueryService queries;

    /**
     * Costs every plan over the window, then narrows to what was asked for.
     *
     * <p>The costing is unconditional. Criteria change what is shown, never what is computed,
     * so a count like "37 hidden" is always truthful.
     */
    public PlanPage page(DateRange range, PlanQuery query) {
        return queries.apply(comparisons.compare(range), query, market.conditions());
    }

    /**
     * One plan costed over the window, with everything published about it.
     *
     * @throws NotFoundException for an unknown identifier, which is an ordinary consequence of
     *     a stale link rather than a fault
     */
    public PlanDetail detail(String id, DateRange range) {
        var plan = plans.byId(id).orElseThrow(() -> new NotFoundException("No plan " + id));
        boolean local = plans.localPlans().stream().anyMatch(p -> p.id().equals(id));
        return PlanDetail.of(
                comparisons.cost(plan, range),
                range,
                market.conditions().get(id),
                market.extras().get(id),
                local);
    }

    public Shell shell(DateRange range) {
        if (!usage.loaded()) {
            return Shell.unloaded(usage.loadError());
        }
        return Shell.of("plans", range, usage.available(), plans.plans().size(),
                plans.loadErrors());
    }

    public PlanQuery query(String search, String requirements, List<String> retailers,
            String shape, String sort, String limit) {
        return PlanQuery.of(search, requirements, retailers, shape, sort, limit);
    }

    /** The same, including what the household says it owns and what it will settle for. */
    public PlanQuery query(String search, String requirements, List<String> retailers,
            String shape, String sort, String limit, List<String> have,
            String unconditional, String minimumSaving) {
        return PlanQuery.of(search, requirements, retailers, shape, sort, limit,
                have, unconditional, minimumSaving);
    }

    /** An absent window falls back to the default; a malformed one is still a 400. */
    public DateRange range(String from, String to) {
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
