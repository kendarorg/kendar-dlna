package org.kendar.dlna.delivery;

import org.kendar.dlna.catalog.Track;

/**
 * Feedback from transcoding to the catalog: the measured length of a rendered MIDI file, so that later browses
 * report the exact duration and size. Implemented by the composition root (the catalog's single writer).
 */
@FunctionalInterface
public interface RenderedLengthSink {

    /** Ignores everything. */
    RenderedLengthSink NONE = (track, samples, millis) -> {
    };

    /**
     * @param track   the track as it was when rendering started
     * @param samples exact number of sample frames at the 48 kHz output rate
     * @param millis  duration in milliseconds
     */
    void rendered(Track track, long samples, long millis);
}
