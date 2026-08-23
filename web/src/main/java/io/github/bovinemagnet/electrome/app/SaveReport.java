package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.ingest.PlanLibrary;
import java.util.List;

/**
 * What a save did, file by file.
 *
 * <p>Reported rather than assumed. Writing into a directory the user maintains is the one thing
 * this application does that they cannot simply undo by reloading the page, so it says exactly
 * which files it touched and under what name — including the ones it declined to write over.
 */
public record SaveReport(List<Written> written, List<String> failed, String directory) {

    /**
     * @param fileName the name as it ended up, which is not always the name that was offered
     */
    public record Written(
            String planId,
            String planName,
            String retailer,
            String fileName,
            PlanLibrary.Outcome outcome) {

        public boolean isNew() {
            return outcome == PlanLibrary.Outcome.NEW || outcome == PlanLibrary.Outcome.RENAMED;
        }

        public String outcomeLabel() {
            return switch (outcome) {
                case NEW -> "New file";
                case REPLACED -> "Replaced";
                case UNCHANGED -> "Already up to date";
                case RENAMED -> "New file, renamed";
            };
        }

        public String outcomeClass() {
            return switch (outcome) {
                case NEW -> "unheld";
                case REPLACED -> "stale";
                case UNCHANGED -> "held";
                case RENAMED -> "renamed";
            };
        }
    }

    public SaveReport {
        written = List.copyOf(written);
        failed = List.copyOf(failed);
    }

    public static SaveReport empty(String directory) {
        return new SaveReport(List.of(), List.of(), directory);
    }

    public boolean anything() {
        return !written.isEmpty() || !failed.isEmpty();
    }

    public int count() {
        return written.size();
    }

    public long newFiles() {
        return written.stream().filter(Written::isNew).count();
    }

    public long replaced() {
        return written.stream()
                .filter(w -> w.outcome() == PlanLibrary.Outcome.REPLACED)
                .count();
    }

    public long unchanged() {
        return written.stream()
                .filter(w -> w.outcome() == PlanLibrary.Outcome.UNCHANGED)
                .count();
    }

    /** True when a plan kept its own name rather than the one the retailer's name suggests. */
    public boolean anyRenamed() {
        return written.stream().anyMatch(w -> w.outcome() == PlanLibrary.Outcome.RENAMED);
    }

    public boolean anyFailed() {
        return !failed.isEmpty();
    }
}
