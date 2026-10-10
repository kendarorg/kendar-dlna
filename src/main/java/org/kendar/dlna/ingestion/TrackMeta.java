package org.kendar.dlna.ingestion;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.TrackTags;

/**
 * Result of reading one audio file: tags plus technical properties (0 = unknown). {@code sampleCount} is the exact
 * number of sample frames at {@code sampleRate} when the reader knows it (needed for the exact length of transcoded
 * output), else 0. {@code format} is set when the reader found out that the file is not what its extension
 * suggests (an {@code .m4a} holding Apple Lossless), else null.
 */
public record TrackMeta(TrackTags tags, long durationMillis, int bitrateKbps, int sampleRate, int channels,
        boolean embeddedArt, long sampleCount, Format format) {

    public TrackMeta(TrackTags tags, long durationMillis, int bitrateKbps, int sampleRate, int channels,
            boolean embeddedArt, long sampleCount) {
        this(tags, durationMillis, bitrateKbps, sampleRate, channels, embeddedArt, sampleCount, null);
    }

    public TrackMeta(TrackTags tags, long durationMillis, int bitrateKbps, int sampleRate, int channels,
            boolean embeddedArt) {
        this(tags, durationMillis, bitrateKbps, sampleRate, channels, embeddedArt, 0, null);
    }

    public TrackMeta withSampleCount(long count) {
        return new TrackMeta(tags, durationMillis, bitrateKbps, sampleRate, channels, embeddedArt, count, format);
    }

    public TrackMeta withFormat(Format actual) {
        return new TrackMeta(tags, durationMillis, bitrateKbps, sampleRate, channels, embeddedArt, sampleCount,
                actual);
    }
}
