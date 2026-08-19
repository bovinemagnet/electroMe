package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.app.PlanResult;

/** One comparison row: the plan's result plus its bar segments. */
public record PlanRow(PlanResult result, BillBars bars) {}
