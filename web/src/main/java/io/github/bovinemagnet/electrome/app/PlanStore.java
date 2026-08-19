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

    private volatile List<Plan> plans = List.of();
    private volatile List<String> loadErrors = List.of();

    @PostConstruct
    void load() {
        reload();
    }

    public final void reload() {
        Path directory = Path.of(plansDir);
        if (!Files.isDirectory(directory)) {
            plans = List.of();
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

        plans = List.copyOf(loaded);
        loadErrors = List.copyOf(errors);
    }

    public List<Plan> plans() {
        return plans;
    }

    public Optional<Plan> byId(String id) {
        return plans.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public List<String> loadErrors() {
        return loadErrors;
    }
}
