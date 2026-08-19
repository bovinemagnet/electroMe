package io.github.bovinemagnet.electrome.app;

import java.math.BigDecimal;
import java.util.List;

/**
 * Average power draw across the day.
 *
 * <p>Always 48 entries, index 0 being 00:00 to 00:30. Slots with no readings are zero rather
 * than absent, so a chart never has to handle a ragged series.
 *
 * <p>Expressed in kW rather than kWh per interval. The numbers differ only by a factor of two
 * but users think in power draw, and kW compares directly against a demand tariff.
 */
public record LoadCurve(String label, List<BigDecimal> averageKWByHalfHour) {

    public LoadCurve {
        if (averageKWByHalfHour.size() != 48) {
            throw new IllegalArgumentException(
                    "A load curve needs 48 slots, got " + averageKWByHalfHour.size());
        }
        averageKWByHalfHour = List.copyOf(averageKWByHalfHour);
    }

    /** The highest slot, for annotating the peak. */
    public int peakSlot() {
        int peak = 0;
        for (int i = 1; i < averageKWByHalfHour.size(); i++) {
            if (averageKWByHalfHour.get(i).compareTo(averageKWByHalfHour.get(peak)) > 0) {
                peak = i;
            }
        }
        return peak;
    }

    public BigDecimal peakKW() {
        return averageKWByHalfHour.get(peakSlot());
    }
}
