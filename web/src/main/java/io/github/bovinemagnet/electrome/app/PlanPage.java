package io.github.bovinemagnet.electrome.app;

import java.util.List;

/**
 * One screenful of the plan browser: what matched, and what the counts around it mean.
 *
 * <p>The counts exist so that nothing is silently dropped. A reader looking at eight rows must be
 * able to tell whether that is all there is, all that matched, or all that fitted.
 *
 * @param results the rows to render, filtered, sorted and truncated to the requested limit
 * @param matched how many plans met every criterion, before the limit was applied
 * @param total how many plans were costed, which is every plan known
 * @param hiddenByRequirements plans excluded solely by the requirement filter, that every other
 *     criterion would have shown
 * @param withRequirements plans needing equipment or a membership among those the other criteria
 *     would have shown, whichever way the requirement filter is set
 * @param retailers every retailer present in the comparison, so the control can offer all of them
 * @param verdict the answer the table is evidence for, computed over every plan rather than
 *     the filtered subset: narrowing a search should not change which plan is cheapest
 * @param notes what each row has to say beyond its total, by plan id
 * @param rates what each plan charges, as published, by plan id
 * @param rateColumns the components any plan on this page charges, in vocabulary order
 */
public record PlanPage(
        PlanQuery query,
        List<PlanResult> results,
        int matched,
        int total,
        int hiddenByRequirements,
        int withRequirements,
        List<String> retailers,
        Verdict verdict,
        java.util.Map<String, PlanNotes> notes,
        java.util.Map<String, PlanRates> rates,
        List<PlanMatrix.Component> rateColumns) {

    public PlanPage {
        results = List.copyOf(results);
        retailers = List.copyOf(retailers);
        verdict = verdict == null ? Verdict.none() : verdict;
        notes = notes == null ? java.util.Map.of() : java.util.Map.copyOf(notes);
        rates = rates == null ? java.util.Map.of() : java.util.Map.copyOf(rates);
        rateColumns = rateColumns == null ? List.of() : List.copyOf(rateColumns);
    }

    /** A page from before the ranking had a verdict to offer. */
    public PlanPage(PlanQuery query, List<PlanResult> results, int matched, int total,
            int hiddenByRequirements, int withRequirements, List<String> retailers) {
        this(query, results, matched, total, hiddenByRequirements, withRequirements, retailers,
                Verdict.none(), java.util.Map.of(), java.util.Map.of(), List.of());
    }

    /** What this row has to say beyond its total. */
    public PlanNotes notesFor(PlanResult result) {
        return notes.getOrDefault(result.bill().plan().id(), PlanNotes.plain());
    }

    /** What this plan charges, as published. */
    public PlanRates ratesFor(PlanResult result) {
        return rates.get(result.bill().plan().id());
    }

    public boolean showsRates() {
        return !rateColumns.isEmpty();
    }

    public boolean empty() {
        return results.isEmpty();
    }

    /** True when the limit cut the list short, which the reader has to be told. */
    public boolean truncated() {
        return matched > results.size();
    }

    /** True when there is nothing at all, as opposed to nothing matching. */
    public boolean noPlansAtAll() {
        return total == 0;
    }
}
