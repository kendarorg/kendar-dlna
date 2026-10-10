package org.kendar.dlna.catalog;

/**
 * The part of a source audio file a virtual track plays: one cue sheet TRACK or one embedded chapter. The
 * virtual track's ref is {@code <file ref>#<index>}; the source file is its parent ref. Positions are in sample
 * frames at the source's sample rate ({@code endSample} exclusive), so the length is exact; the millisecond fields
 * are for display. {@code origin} is {@code cue} (a .cue file), {@code tag-cue} (a CUESHEET tag) or
 * {@code chapters} (ID3 CHAP, MP4 chpl, Vorbis CHAPTERxxx).
 */
public record Segment(int index, long startMillis, long endMillis, long startSample, long endSample,
        String origin) {

    public long samples() {
        return endSample - startSample;
    }
}
