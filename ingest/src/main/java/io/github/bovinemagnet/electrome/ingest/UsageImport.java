package io.github.bovinemagnet.electrome.ingest;

import io.github.bovinemagnet.electrome.core.domain.UsageData;
import java.util.Objects;

/** The result of importing an interval data file. */
public record UsageImport(UsageData usage, DataQualityReport report) {

    public UsageImport {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(report, "report");
    }
}
