package io.github.bovinemagnet.electrome.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * How far from your own plan a published plan sits, on one scale shared by every row.
 *
 * <p>Diverging, and anchored on the household's own tariff rather than on the cheapest plan
 * listed. "Cheaper than what I pay now" is the decision being made; "within $40 of the cheapest
 * plan in Victoria" is not, and a scale anchored on the cheapest row moves every time the
 * filters change.
 *
 * <p>Magnitude is carried by bar length as well as by colour, so the ranking survives being
 * read by somebody who cannot separate the two hues, or printed in grey.
 *
 * @param widest the largest distance from the anchor on the page, which sets the full bar
 * @param anchorName what the middle of the scale is, for the legend
 */
public record ValueScale(BigDecimal widest, String anchorName) {

    /** Enough steps to rank at a glance, few enough that each is visibly distinct. */
    private static final int STEPS = 4;

    private static final ValueScale NONE = new ValueScale(BigDecimal.ZERO, null);

    public ValueScale {
        widest = widest == null || widest.signum() < 0 ? BigDecimal.ZERO : widest;
    }

    /** A scale with nothing to anchor to, which renders as no colour at all. */
    public static ValueScale none() {
        return NONE;
    }

    public static ValueScale over(List<BigDecimal> differences, String anchorName) {
        var widest = differences.stream()
                .filter(java.util.Objects::nonNull)
                .map(BigDecimal::abs)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        return new ValueScale(widest, anchorName);
    }

    public boolean anchored() {
        return anchorName != null && widest.signum() > 0;
    }

    /**
     * Which side of the household's own plan this one falls, as a class name.
     *
     * <p>Direction and intensity are separate so the stylesheet can say once that cheaper is
     * green and the bar runs left, rather than repeating it for every step.
     */
    public String direction(BigDecimal difference) {
        if (!anchored() || difference == null) {
            return "unpriced";
        }
        if (difference.signum() == 0) {
            return "anchor";
        }
        return difference.signum() < 0 ? "cheaper" : "dearer";
    }

    /** 0 at the anchor or unpriced, 1 for a small difference, 4 for the widest on the page. */
    public int stepFor(BigDecimal difference) {
        if (!anchored() || difference == null || difference.signum() == 0) {
            return 0;
        }
        return step(difference);
    }

    /**
     * How far along its half of the track this plan's bar runs, as a percentage.
     *
     * <p>Never zero for a plan that differs at all: a bar that rounds away would read as "the
     * same as yours" when it is not.
     */
    public int barPercent(BigDecimal difference) {
        if (!anchored() || difference == null || difference.signum() == 0) {
            return 0;
        }
        var share = difference.abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(widest, 0, RoundingMode.HALF_UP)
                .intValue();
        return Math.max(2, Math.min(100, share));
    }

    public boolean cheaperThanAnchor(BigDecimal difference) {
        return difference != null && difference.signum() < 0;
    }

    /** The end of the scale, so the legend states what the extremes are worth. */
    public BigDecimal widestSaving() {
        return widest;
    }

    private int step(BigDecimal difference) {
        var share = difference.abs()
                .multiply(BigDecimal.valueOf(STEPS))
                .divide(widest, 0, RoundingMode.CEILING)
                .intValue();
        return Math.max(1, Math.min(STEPS, share));
    }
}
