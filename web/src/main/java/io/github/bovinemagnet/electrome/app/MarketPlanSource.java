package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.market.cdr.CdrCache;
import io.github.bovinemagnet.electrome.market.cdr.CdrPlanMapper;
import io.github.bovinemagnet.electrome.market.cdr.CdrClient;
import io.github.bovinemagnet.electrome.market.cdr.CdrRegisterClient;
import io.github.bovinemagnet.electrome.market.cdr.HarvestReport;
import io.github.bovinemagnet.electrome.market.cdr.MarketHarvest;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Plans harvested from the Consumer Data Right product reference data.
 *
 * <p>Nothing is fetched at startup. A harvest takes tens of seconds and reaches a public
 * regulator's infrastructure; doing it on every boot would be discourteous and would make the
 * application unusable without a network.
 */
@ApplicationScoped
public class MarketPlanSource {

    @ConfigProperty(name = "electrome.market.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "electrome.market.zone", defaultValue = "AUSNET")
    String zoneName;

    @ConfigProperty(name = "electrome.market.cache-dir", defaultValue = "../.cdr-cache")
    String cacheDir;

    private volatile List<Plan> plans = List.of();
    private volatile java.util.Map<String, List<String>> conditions = java.util.Map.of();
    private volatile java.util.Map<String, io.github.bovinemagnet.electrome.market.cdr.PlanExtras>
            extras = java.util.Map.of();
    private volatile HarvestReport report;
    private volatile String harvestError;
    private volatile java.time.LocalDateTime harvestedAt;

    public boolean enabled() {
        return enabled;
    }

    public boolean harvested() {
        return report != null;
    }

    public List<Plan> plans() {
        return plans;
    }

    public Optional<HarvestReport> lastReport() {
        return Optional.ofNullable(report);
    }

    /**
     * Eligibility conditions by plan id, for plans that carry any.
     *
     * <p>Many of the cheapest published plans require solar, a battery, an electric vehicle or
     * a membership. Ranking those against plans anyone can sign up to, with nothing to
     * distinguish them, would present a saving the household may not be able to take.
     */
    public java.util.Map<String, List<String>> conditions() {
        return conditions;
    }

    /**
     * Fees and incentives by plan id, for the plans that publish any.
     *
     * <p>Display only, and deliberately not costed: published fee amounts are GST inclusive
     * while unit prices are exclusive.
     */
    public java.util.Map<String, io.github.bovinemagnet.electrome.market.cdr.PlanExtras> extras() {
        return extras;
    }

    /**
     * When the register was last read.
     *
     * <p>A comparison is only as fresh as its harvest, and a tariff published three weeks ago
     * may not be on sale today. The screens that lean hardest on harvested plans say when.
     */
    public Optional<java.time.LocalDateTime> harvestedAt() {
        return Optional.ofNullable(harvestedAt);
    }

    /**
     * A plan the register no longer lists, recovered from the harvest cache.
     *
     * <p>A plan being withdrawn is itself worth knowing, and a household that picked one out
     * should see it disappear from sale rather than simply disappear. The cached detail is the
     * last thing the retailer published, which is exactly what the reader was looking at.
     */
    public Optional<Plan> fromCache(String planId) {
        if (!enabled || planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        try {
            var cache = new CdrCache(Workspace.resolveDirectory(cacheDir));
            return cache.read(planId)
                    .map(json -> CdrPlanMapper.map(json, DistributionZone.valueOf(zoneName)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public Optional<String> harvestError() {
        return Optional.ofNullable(harvestError);
    }

    public String zoneName() {
        return zoneName;
    }

    public void harvest() {
        if (!enabled) {
            harvestError = "Market harvesting is disabled by configuration.";
            return;
        }
        try {
            var harvester = new MarketHarvest(new CdrRegisterClient(), new CdrClient(),
                    new CdrCache(Workspace.resolveDirectory(cacheDir)));
            var result = harvester.harvest(DistributionZone.valueOf(zoneName));
            plans = result.plans();
            conditions = result.conditions();
            extras = result.extras();
            report = result.report();
            harvestedAt = java.time.LocalDateTime.now();
            harvestError = null;
        } catch (RuntimeException e) {
            harvestError = "Harvest failed: " + e.getMessage();
        }
    }
}
