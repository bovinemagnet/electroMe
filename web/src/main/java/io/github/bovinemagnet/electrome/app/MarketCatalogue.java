package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.ingest.PlanLibrary;
import io.github.bovinemagnet.electrome.ingest.PlanOrigin;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The market screen's data: harvested plans joined to the plans directory.
 *
 * <p>Thin on purpose. Deciding what a save would do belongs to {@link PlanLibrary}, which knows
 * about files, and arranging the page belongs to {@link MarketPage}, which knows about neither
 * files nor configuration. What is left here is the wiring, and the one thing that genuinely
 * needs both: telling the rest of the application to re-read the directory once a file lands in
 * it.
 */
@ApplicationScoped
public class MarketCatalogue {

    @ConfigProperty(name = "electrome.plans.dir")
    String plansDir;

    @Inject MarketPlanSource market;
    @Inject PlanStore plans;
    @Inject UsageStore usage;
    @Inject ComparisonService comparisons;

    /**
     * The screen, costed over the window when there is usage to cost against.
     *
     * <p>No usage is not a failure. The page still lists what every plan charges; it simply
     * cannot say what any of them would cost this household, and says so rather than showing a
     * column of blanks.
     */
    public MarketPage page(DateRange range, MarketQuery query) {
        var harvested = market.plans();
        return MarketPage.of(
                harvested,
                library().previewAll(harvested),
                market.conditions(),
                market.extras(),
                costed(range, harvested),
                query);
    }

    private Comparison costed(DateRange range, java.util.List<Plan> harvested) {
        if (!usage.loaded() || harvested.isEmpty()) {
            return null;
        }
        return comparisons.compareAsPublished(range, harvested);
    }

    /**
     * Writes the named plans into the plans directory, then re-reads it.
     *
     * <p>The reload is the point of the whole screen. A file that has landed but not been read
     * is invisible on the dashboard, the plan browser and the rates table, which is exactly
     * where the reader was told to go and look.
     */
    public SaveReport save(List<String> planIds) {
        var directory = directory();
        if (planIds == null || planIds.isEmpty()) {
            return SaveReport.empty(directory.toAbsolutePath().toString());
        }

        var library = new PlanLibrary(directory);
        var written = new ArrayList<SaveReport.Written>();
        var failed = new ArrayList<String>();

        for (var plan : chosen(planIds)) {
            try {
                var saved = library.save(plan, PlanOrigin.readToday(plan.id()));
                written.add(new SaveReport.Written(
                        plan.id(),
                        plan.name(),
                        plan.retailer(),
                        saved.file().getFileName().toString(),
                        saved.outcome()));
            } catch (RuntimeException e) {
                // One unwritable file must not lose the others, and the reader needs to know
                // which one it was rather than that "the save failed".
                failed.add(plan.retailer() + " " + plan.name() + ": " + e.getMessage());
            }
        }

        if (!written.isEmpty()) {
            plans.reload();
        }
        return new SaveReport(written, failed, directory.toAbsolutePath().toString());
    }

    /**
     * The harvested plans with these identifiers, in the order the register gave them.
     *
     * <p>Driven from the harvest rather than from the request: a plan identifier that is not on
     * offer is dropped instead of being looked up somewhere it could mean something else.
     */
    private List<Plan> chosen(List<String> planIds) {
        var wanted = Set.copyOf(planIds);
        return market.plans().stream().filter(plan -> wanted.contains(plan.id())).toList();
    }

    public Path directory() {
        return Workspace.resolveDirectory(plansDir);
    }

    private PlanLibrary library() {
        return new PlanLibrary(directory());
    }
}
