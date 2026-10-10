package org.kendar.dlna.ingestion;

import java.time.Instant;
import java.util.List;

import org.kendar.dlna.shared.RootRef;

/**
 * Point-in-time status of the scanner for the admin UI: whether a scan runs (with live progress) and the report
 * of the last finished scan.
 */
public record ScanStatus(boolean running, ScanTrigger trigger, RootRef scope, Instant startedAt, long directories,
        long audioFiles, long tracksReused, long tracksRead, List<String> errors, ScanReport lastReport) {

    public ScanStatus {
        errors = List.copyOf(errors);
    }
}
