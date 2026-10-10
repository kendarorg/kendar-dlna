package org.kendar.dlna.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.kendar.dlna.shared.RootRef;

/**
 * Outcome of one finished scan. {@code scope} is null for the whole library. {@code failure} is non-null when the
 * scan aborted (the catalog was left untouched); {@code errors} are per-file problems of a scan that completed.
 */
public record ScanReport(ScanTrigger trigger, RootRef scope, Instant startedAt, Instant finishedAt,
        long directories, long audioFiles, long tracksReused, long tracksRead, int tracksBefore, int tracksAfter,
        Set<String> changedContainers, List<String> errors, String failure) {

    public ScanReport {
        changedContainers = Set.copyOf(changedContainers);
        errors = List.copyOf(errors);
    }

    public boolean succeeded() {
        return failure == null;
    }
}
