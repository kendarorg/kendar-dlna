package org.kendar.dlna.ingestion;

/** A scan was requested while another one is running (scans never overlap). */
public final class ScanAlreadyRunningException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ScanAlreadyRunningException() {
        super("A scan is already running");
    }
}
