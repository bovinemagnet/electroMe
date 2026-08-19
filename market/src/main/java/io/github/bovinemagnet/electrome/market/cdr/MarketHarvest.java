package io.github.bovinemagnet.electrome.market.cdr;

import io.github.bovinemagnet.electrome.core.tariff.DistributionZone;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Fetches, caches, maps and deduplicates the plans available in one distribution zone. */
public final class MarketHarvest {

    /**
     * The standards permit far more and no throttling was observed in testing, but there is no
     * reason for a household tool to lean on a public regulator's infrastructure.
     */
    private static final int MAX_CONCURRENCY = 4;

    private final CdrRegisterClient register;
    private final CdrClient client;
    private final CdrCache cache;

    public MarketHarvest(CdrRegisterClient register, CdrClient client, CdrCache cache) {
        this.register = Objects.requireNonNull(register, "register");
        this.client = Objects.requireNonNull(client, "client");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /**
     * @param conditions eligibility text by plan id, for the plans that carry any. A plan
     *     absent from this map is one anyone can sign up to.
     */
    public record HarvestResult(
            List<Plan> plans, HarvestReport report, Map<String, List<String>> conditions) {}

    public HarvestResult harvest(DistributionZone zone) {
        long startedAt = System.nanoTime();

        var brands = register.brands().stream().filter(RetailerBrand::usable).toList();
        var candidates = new ArrayList<Candidate>();
        int listed = 0;

        for (var brand : brands) {
            var summaries = listAll(brand);
            listed += summaries.size();
            for (var summary : summaries) {
                if (summary.isResidentialElectricity() && summary.servesZone(zone)) {
                    candidates.add(new Candidate(brand, summary));
                }
            }
        }

        var mapped = new ArrayList<Plan>();
        var skipped = new ArrayList<String>();
        var conditions = new ConcurrentHashMap<String, List<String>>();

        try (var pool = Executors.newFixedThreadPool(MAX_CONCURRENCY)) {
            var results = new ArrayList<Future<Plan>>();
            for (var candidate : candidates) {
                results.add(pool.submit(() -> mapOne(candidate, zone, conditions)));
            }
            for (int i = 0; i < results.size(); i++) {
                try {
                    mapped.add(results.get(i).get());
                } catch (ExecutionException e) {
                    var cause = e.getCause();
                    skipped.add(candidates.get(i).summary().planId() + ": "
                            + (cause == null ? e.getMessage() : cause.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Harvest interrupted", e);
                }
            }
        }

        var distinct = deduplicate(mapped);
        var report = new HarvestReport(
                brands.size(), listed, candidates.size(), mapped.size(), distinct.size(),
                skipped, Duration.ofNanos(System.nanoTime() - startedAt));
        // Only keep conditions for the plans that survived deduplication.
        var kept = new LinkedHashMap<String, List<String>>();
        for (var plan : distinct) {
            var planConditions = conditions.get(plan.id());
            if (planConditions != null && !planConditions.isEmpty()) {
                kept.put(plan.id(), planConditions);
            }
        }
        return new HarvestResult(distinct, report, Map.copyOf(kept));
    }

    private List<PlanSummary> listAll(RetailerBrand brand) {
        var all = new ArrayList<PlanSummary>();
        try {
            String firstPage = client.fetchPlanList(brand, 1, CdrClient.MAX_PAGE_SIZE);
            all.addAll(CdrClient.parseList(firstPage));
            int pages = CdrClient.totalPages(firstPage);
            for (int page = 2; page <= pages; page++) {
                all.addAll(CdrClient.parseList(
                        client.fetchPlanList(brand, page, CdrClient.MAX_PAGE_SIZE)));
            }
        } catch (RuntimeException e) {
            // One retailer being unavailable must not abort the whole harvest.
            return all;
        }
        return all;
    }

    private Plan mapOne(Candidate candidate, DistributionZone zone,
            Map<String, List<String>> conditions) {
        String planId = candidate.summary().planId();
        String json;
        if (cache.isFresh(planId, candidate.summary().lastUpdated())) {
            json = cache.read(planId).orElseThrow();
        } else {
            json = client.fetchPlanDetail(candidate.brand(), planId);
            cache.write(planId, json);
            cache.recordLastUpdated(planId, candidate.summary().lastUpdated());
        }
        var eligibility = CdrPlanMapper.requirementsOf(json);
        if (!eligibility.isEmpty()) {
            conditions.put(planId, eligibility);
        }
        return CdrPlanMapper.map(json, zone);
    }

    /**
     * Collapses plans with identical charges from the same retailer.
     *
     * <p>One retailer publishes thousands of plan identifiers because every postcode group and
     * meter variant is separate. Two retailers at the same price is a real choice; one retailer
     * listing the same rates forty times is noise.
     *
     * <p>Charge records are value types, so structural equality does the work.
     */
    public static List<Plan> deduplicate(List<Plan> plans) {
        var seen = new LinkedHashMap<Key, Plan>();
        for (var plan : plans) {
            seen.putIfAbsent(new Key(plan.retailer(), plan.charges()), plan);
        }
        return List.copyOf(seen.values());
    }

    private record Key(String retailer, List<?> charges) {}

    private record Candidate(RetailerBrand brand, PlanSummary summary) {}
}
