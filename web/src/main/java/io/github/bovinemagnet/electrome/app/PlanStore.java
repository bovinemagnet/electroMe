package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.ingest.PlanYamlLoader;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Holds the plan definitions.
 *
 * <p>One bad plan file must not hide the good ones, so files are loaded individually and
 * failures are collected rather than aborting the load.
 */
@ApplicationScoped
public class PlanStore {

    @ConfigProperty(name = "electrome.plans.dir")
    String plansDir;

    @jakarta.inject.Inject MarketPlanSource market;

    private volatile List<Plan> localPlans = List.of();
    private volatile List<String> loadErrors = List.of();

    @PostConstruct
    void load() {
        reload();
    }

    public final void reload() {
        Path directory = Workspace.resolveDirectory(plansDir);
        if (!Files.isDirectory(directory)) {
            localPlans = List.of();
            loadErrors = List.of("No plan directory at " + directory.toAbsolutePath());
            return;
        }

        var loaded = new ArrayList<Plan>();
        var errors = new ArrayList<String>();
        try (var files = Files.list(directory)) {
            var candidates = files.filter(p -> {
                        var name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
            for (var file : candidates) {
                try {
                    loaded.add(PlanYamlLoader.load(file));
                } catch (RuntimeException e) {
                    errors.add(file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("Could not list " + directory.toAbsolutePath() + ": " + e.getMessage());
        }

        localPlans = List.copyOf(loaded);
        loadErrors = List.copyOf(errors);
    }

    /**
     * Local plan files first, then harvested market plans.
     *
     * <p>A hand-written plan wins on identifier collision. It is a deliberate statement,
     * usually the tariff the household is actually on and possibly no longer published, and
     * must not be silently replaced by a harvested plan of the same name.
     */
    public List<Plan> plans() {
        var harvested = market.plans();
        if (harvested.isEmpty()) {
            return localPlans;
        }
        var localIds = new java.util.HashSet<String>();
        for (var plan : localPlans) {
            localIds.add(plan.id());
        }
        var merged = new ArrayList<>(localPlans);
        for (var plan : harvested) {
            if (!localIds.contains(plan.id())) {
                merged.add(plan);
            }
        }
        return List.copyOf(merged);
    }

    /** Only the plans defined as files, ignoring anything harvested. */
    public List<Plan> localPlans() {
        return localPlans;
    }

    public Optional<Plan> byId(String id) {
        return plans().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public List<String> loadErrors() {
        return loadErrors;
    }
}
