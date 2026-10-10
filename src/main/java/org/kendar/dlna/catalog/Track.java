package org.kendar.dlna.catalog;

import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.shared.StableIds;

/**
 * A playable audio file. {@code durationMillis} is 0 when unknown. {@code sampleCount} (exact, 0 = unknown) and
 * {@code renderedMillis} (measured length of a MIDI rendering, 0 = not rendered yet) are slots filled by later
 * milestones; they are persisted with the index. {@code addedAt} (epoch millis, 0 = unknown) is when a scan first
 * saw the file (on the very first scan: its creation time, else its mtime); it survives rescans and tag edits.
 */
public record Track(RootRef ref, long mtime, long size, Format format, TrackTags tags, long durationMillis,
        int bitrateKbps, int sampleRate, int channels, boolean embeddedArt, long sampleCount, long renderedMillis,
        long addedAt, Segment segment) {

    public Track {
        tags = tags == null ? TrackTags.EMPTY : tags;
    }

    /** A whole-file track (no segment). */
    public Track(RootRef ref, long mtime, long size, Format format, TrackTags tags, long durationMillis,
            int bitrateKbps, int sampleRate, int channels, boolean embeddedArt, long sampleCount,
            long renderedMillis, long addedAt) {
        this(ref, mtime, size, format, tags, durationMillis, bitrateKbps, sampleRate, channels, embeddedArt,
                sampleCount, renderedMillis, addedAt, null);
    }

    public Track(RootRef ref, long mtime, long size, Format format, TrackTags tags, long durationMillis,
            int bitrateKbps, int sampleRate, int channels, boolean embeddedArt, long sampleCount,
            long renderedMillis) {
        this(ref, mtime, size, format, tags, durationMillis, bitrateKbps, sampleRate, channels, embeddedArt,
                sampleCount, renderedMillis, 0);
    }

    public Track(RootRef ref, long mtime, long size, Format format, TrackTags tags, long durationMillis,
            int bitrateKbps, int sampleRate, int channels, boolean embeddedArt) {
        this(ref, mtime, size, format, tags, durationMillis, bitrateKbps, sampleRate, channels, embeddedArt, 0, 0, 0);
    }

    /** The same track with another first-seen timestamp. */
    public Track withAddedAt(long millis) {
        return new Track(ref, mtime, size, format, tags, durationMillis, bitrateKbps, sampleRate, channels,
                embeddedArt, sampleCount, renderedMillis, millis, segment);
    }

    /** True for a virtual track (a cue sheet track or a chapter of a larger file). */
    public boolean isSegment() {
        return segment != null;
    }

    /** The ref of the file holding the audio: the track itself, or the source file of a virtual track. */
    public RootRef fileRef() {
        if (segment == null) {
            return ref;
        }
        int hash = ref.rel().lastIndexOf('#');
        return hash < 0 ? ref : new RootRef(ref.root(), ref.rel().substring(0, hash));
    }

    public static String idOf(RootRef ref) {
        return StableIds.forPath("t", ref);
    }

    public String id() {
        return idOf(ref);
    }

    /** File name including extension. */
    public String fileName() {
        return ref.name();
    }

    /** The folder holding the track: its directory, or the virtual album (the source file) of a segment. */
    public RootRef parentRef() {
        return segment == null ? ref.parent() : fileRef();
    }
}
