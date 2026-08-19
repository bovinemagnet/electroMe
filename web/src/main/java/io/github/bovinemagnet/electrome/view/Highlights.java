package io.github.bovinemagnet.electrome.view;

import io.github.bovinemagnet.electrome.app.UsageAnalysis;
import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.ChargeKind;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * The handful of figures the dashboard leads with.
 *
 * <p>The dearest band's share of the bill against its share of the energy is the single fact
 * this application exists to communicate, so it is computed once here rather than assembled
 * ad hoc in a template.
 */
public record Highlights(
        BigDecimal totalKWh,
        BigDecimal averageDailyKWh,
        BigDecimal averageCentsPerKWh,
        BigDecimal peakShareOfBill,
        BigDecimal peakShareOfEnergy,
        String peakWindow,
        BigDecimal peakKW,
        String peakTime) {

    /**
     * True when the cheapest plan actually has a dearest time-of-use window.
     *
     * <p>A flat-rate plan has none, and a "share of bill from the peak window" figure would be
     * meaningless rather than merely zero, so the dashboard omits the tile instead.
     */
    public boolean hasPeakWindow() {
        return peakWindow != null && !peakWindow.isBlank();
    }

    public static Highlights of(BillBreakdown bill, UsageAnalysis analysis) {
        Optional<Band> dearest = BandPalette.dearestBand(bill.plan());
        String window = dearest.map(Band::describe).orElse("");
        String label = "Usage " + window;

        BigDecimal peakCost = BigDecimal.ZERO;
        BigDecimal peakKWh = BigDecimal.ZERO;
        for (var line : bill.lines()) {
            if (line.kind() == ChargeKind.USAGE && line.label().equals(label)) {
                peakCost = line.cost();
                peakKWh = line.quantity();
            }
        }

        var curve = analysis.overall();
        return new Highlights(
                bill.totalKWh(),
                analysis.averageDailyKWh(),
                bill.averageCentsPerKWh(),
                share(peakCost, bill.total()),
                share(peakKWh, bill.totalKWh()),
                humanWindow(window),
                curve.peakKW(),
                Money.slotTime(curve.peakSlot()));
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(new BigDecimal("100")).divide(whole, MathContext.DECIMAL64);
    }

    /** "16:00-21:00" reads better on a dashboard as "4pm-9pm". */
    private static String humanWindow(String window) {
        var parts = window.split("-");
        if (parts.length != 2) {
            return window;
        }
        return hour(parts[0]) + "–" + hour(parts[1]);
    }

    private static String hour(String clock) {
        var hm = clock.split(":");
        int h = Integer.parseInt(hm[0]);
        String minute = "00".equals(hm[1]) ? "" : ":" + hm[1];
        String suffix = h < 12 || h == 24 ? "am" : "pm";
        int display = h % 12 == 0 ? 12 : h % 12;
        return display + minute + suffix;
    }
}
