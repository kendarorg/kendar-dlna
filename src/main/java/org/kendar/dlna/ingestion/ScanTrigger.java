package org.kendar.dlna.ingestion;

/** What started a scan. */
public enum ScanTrigger {
    /** The initial full scan (no usable persisted index). */
    STARTUP,
    /** The background scan after a fast start from a stale index. */
    CATCH_UP,
    SCHEDULED,
    MANUAL
}
