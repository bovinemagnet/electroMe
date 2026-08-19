package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.ChargeKind;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A bill's charge components as proportional bar segments.
 *
 * <p>Plain DOM rather than a chart: the comparison bar lives inside its table row, so the
 * comparison reads left to right in one pass instead of forcing the eye between a chart and a
 * table that say the same thing.
 */
public record BillBars(List<Segment> segments) {

    /** @param widthPercent already formatted for a CSS width, e.g. "41.6" */
    public record Segment(String label, String colour, BigDecimal cost, String widthPercent,
            String sharePercent) {}

    /** Segments in bill order, scaled so {@code scaleTo} is the full bar width. */
    public static BillBars of(BillBreakdown bill, BigDecimal scaleTo) {
        var segments = new ArrayList<Segment>();
        if (scaleTo == null || scaleTo.signum() == 0) {
            return new BillBars(segments);
        }
        for (var line : bill.lines()) {
            if (line.cost().signum() <= 0) {
                continue;
            }
            segments.add(new Segment(
                    line.label(),
                    BandPalette.cssVar(BandPalette.tokenFor(bill.plan(), line)),
                    line.cost(),
                    percent(line.cost(), scaleTo),
                    percent(line.cost(), bill.total())));
        }
        return new BillBars(segments);
    }

    /**
     * Segments sorted dearest first, scaled so the leader fills the bar.
     *
     * <p>Ranked bars rather than a pie: six components are hard to compare by angle and easy
     * by length. Widths are relative to the largest component so the ordering is legible;
     * {@code sharePercent} still carries each one's share of the whole bill.
     */
    public static BillBars ranked(BillBreakdown bill) {
        var sorted = new ArrayList<>(of(bill, bill.total()).segments());
        sorted.sort(Comparator.comparing(Segment::cost).reversed());
        if (sorted.isEmpty()) {
            return new BillBars(sorted);
        }
        BigDecimal largest = sorted.get(0).cost();
        var scaled = new ArrayList<Segment>(sorted.size());
        for (var s : sorted) {
            scaled.add(new Segment(s.label(), s.colour(), s.cost(),
                    percent(s.cost(), largest), s.sharePercent()));
        }
        return new BillBars(scaled);
    }

    private static String percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return "0";
        }
        return part.multiply(new BigDecimal("100"))
                .divide(whole, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
