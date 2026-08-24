package io.github.bovinemagnet.electrome.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a plan file came from, when it was not written by hand.
 *
 * <p>The plans directory belongs to the user. Writing harvested plans into it is only safe if
 * the application can tell its own files apart from theirs, so a file written from the register
 * carries a {@code source} block naming the published plan and the day it was read. A file
 * without one was written by a person, and nothing may overwrite it.
 *
 * <p>The block is deliberately outside the tariff model. {@link PlanYamlLoader} reads only the
 * fields it knows about and ignores this one, so a saved plan and a hand-written plan load
 * through exactly the same path.
 *
 * @param cdrPlanId the published plan identifier, as the register gives it
 * @param readOn the day the register was read
 */
public record PlanOrigin(String cdrPlanId, LocalDate readOn) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public PlanOrigin {
        Objects.requireNonNull(cdrPlanId, "cdrPlanId");
        Objects.requireNonNull(readOn, "readOn");
        if (cdrPlanId.isBlank()) {
            throw new IllegalArgumentException("A plan origin needs the published plan id");
        }
    }

    public static PlanOrigin readToday(String cdrPlanId) {
        return new PlanOrigin(cdrPlanId, LocalDate.now());
    }

    /** Empty for a hand-written file, and for one whose source block is unreadable. */
    public static Optional<PlanOrigin> readFrom(String yaml) {
        try {
            var source = YAML.readTree(yaml).get("source");
            if (source == null || source.isNull()) {
                return Optional.empty();
            }
            var planId = source.path("cdrPlanId").asText(null);
            var readOn = source.path("readOn").asText(null);
            if (planId == null || planId.isBlank() || readOn == null || readOn.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new PlanOrigin(planId.trim(), LocalDate.parse(readOn.trim())));
        } catch (IOException | RuntimeException e) {
            // A file we cannot read the origin of is treated as one we did not write, which is
            // the cautious end of the choice: it is left alone rather than replaced.
            return Optional.empty();
        }
    }

    public static Optional<PlanOrigin> readFrom(Path file) {
        try {
            return readFrom(Files.readString(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
