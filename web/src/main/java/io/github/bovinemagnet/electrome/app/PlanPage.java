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
 */
public record PlanPage(
        PlanQuery query,
        List<PlanResult> results,
        int matched,
        int total,
        int hiddenByRequirements,
        int withRequirements,
        List<String> retailers) {

    public PlanPage {
        results = List.copyOf(results);
        retailers = List.copyOf(retailers);
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
