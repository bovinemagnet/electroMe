package io.github.bovinemagnet.electrome.core.tariff;

import io.github.bovinemagnet.electrome.core.domain.IntervalReading;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A credit per kWh exported to the grid. Contributes a negative amount to the bill.
 *
 * <p>Banded, because retailers increasingly pay for exports the way they charge for imports: a
 * few cents in the evening peak when the grid wants the energy, and next to nothing at midday
 * when it does not. Sixty-six of the plans published for the AusNet network do exactly that.
 *
 * <p>A flat credit is the degenerate case of one band covering the whole day, held that way for
 * the same reason a single-rate {@link Band} is one unbounded tier: one code path through the
 * costing engine rather than two that can disagree.
 *
 * <p>A band may be capped, because published plans are: Flow Power's 4Free pays seventeen cents
 * for the first fifteen kilowatt hours exported between half past five and half past nine, and
 * two cents after that. The tier machinery on {@link Band} already expresses it and the costing
 * engine already accumulates against it, so export uses the same path consumption does.
 */
public record SolarFeedIn(List<Band> bands) implements Charge {

    public SolarFeedIn {
        Objects.requireNonNull(bands, "bands");
        bands = List.copyOf(bands);
        if (bands.isEmpty()) {
            throw new IllegalArgumentException("A feed-in tariff needs at least one band");
        }
    }

    /** One rate, all day, every day: what most plans still publish. */
    public SolarFeedIn(BigDecimal centsPerKWh) {
        this(List.of(new Band(0, Band.MINUTES_PER_DAY, DaySelector.ALL, requireCredit(centsPerKWh))));
    }

    /** Whether one rate covers the whole day, which is what a detail view can state in a line. */
    public boolean flat() {
        return bands.size() == 1;
    }

    /** Whether what the household earns depends on when it exports. */
    public boolean varies() {
        return bestRate().compareTo(lowestRate()) != 0;
    }

    /**
     * The best rate this plan pays anywhere.
     *
     * <p>The figure a retailer advertises, and the one a reader sorting by feed-in means. Read
     * across every block of every band: it is neither the first band nor the first block, since
     * the generous rate is usually the evening one and usually the one that runs out.
     */
    public BigDecimal bestRate() {
        return rates().max(BigDecimal::compareTo).orElseThrow();
    }

    /** The worst rate it pays, which is what a household exporting at noon actually earns. */
    public BigDecimal lowestRate() {
        return rates().min(BigDecimal::compareTo).orElseThrow();
    }

    /** True where the credit runs out after so much export in a day. */
    public boolean capped() {
        return bands.stream().anyMatch(Band::capped);
    }

    private java.util.stream.Stream<BigDecimal> rates() {
        return bands.stream().flatMap(band -> band.tiers().stream()).map(Tier::centsPerKWh);
    }

    /**
     * The headline rate for the band covering this half hour, or null when none does.
     *
     * <p>The band's first block, so on a capped credit this is what the household earns until
     * the allowance runs out rather than what it averages.
     *
     * <p>Null rather than zero. A tariff that prices only part of the day is a different thing
     * from one that pays nothing for the rest, and the validator rejects the first, so a null
     * here means a plan reached the engine that should not have.
     */
    public BigDecimal rateAt(IntervalReading reading, HolidayCalendar holidays) {
        for (var band : bands) {
            if (band.matches(reading, holidays)) {
                return band.centsPerKWh();
            }
        }
        return null;
    }

    private static BigDecimal requireCredit(BigDecimal centsPerKWh) {
        Objects.requireNonNull(centsPerKWh, "centsPerKWh");
        if (centsPerKWh.signum() < 0) {
            throw new IllegalArgumentException("Feed-in rate must not be negative: " + centsPerKWh);
        }
        return centsPerKWh;
    }

    @Override
    public String label() {
        return "Solar feed-in credit";
    }
}
