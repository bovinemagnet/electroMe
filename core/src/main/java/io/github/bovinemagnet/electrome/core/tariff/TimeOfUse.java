package io.github.bovinemagnet.electrome.core.tariff;

import java.util.List;
import java.util.Objects;

/** Usage priced by the time of day the energy was consumed. */
public record TimeOfUse(List<Band> bands) implements Charge {

    public TimeOfUse {
        Objects.requireNonNull(bands, "bands");
        if (bands.isEmpty()) {
            throw new IllegalArgumentException("Time of use requires at least one band");
        }
        bands = List.copyOf(bands);
    }

    @Override
    public String label() {
        return "Time of use usage";
    }
}
