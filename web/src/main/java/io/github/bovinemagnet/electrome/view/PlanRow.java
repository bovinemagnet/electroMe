package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.app.PlanResult;
import java.util.List;

/**
 * One comparison row: the plan's result, its bar segments, and any eligibility conditions.
 *
 * @param conditions empty for a plan anyone can sign up to
 */
public record PlanRow(PlanResult result, BillBars bars, List<String> conditions) {

    public PlanRow {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public boolean conditional() {
        return !conditions.isEmpty();
    }

    /** The conditions as one string, for a tooltip. */
    public String conditionsText() {
        return String.join(" ", conditions);
    }
}
