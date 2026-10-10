package org.kendar.dlna.ingestion;

import java.util.List;

/**
 * A parsed cue sheet (or the same shape built from embedded chapters): album-level fields plus the audio files it
 * describes, each with its tracks. Positions are microseconds from the start of the file the track belongs to.
 *
 * @param year     from {@code REM DATE} (leading integer), else null
 * @param warnings tolerated problems found while parsing
 */
public record CueSheet(String title, String performer, String songwriter, String genre, Integer year,
        Integer discNumber, List<CueFile> files, List<String> warnings) {

    public CueSheet {
        files = List.copyOf(files);
        warnings = List.copyOf(warnings);
    }

    /** One {@code FILE}: its name as written (null for chapters of the file itself) and its tracks in order. */
    public record CueFile(String name, List<CueTrack> tracks) {
        public CueFile {
            tracks = List.copyOf(tracks);
        }
    }

    /**
     * One audio TRACK. {@code startMicros} is INDEX 01; {@code gapMicros} INDEX 00 (-1 = none, informational: a
     * track ends where the next one's INDEX 01 begins).
     */
    public record CueTrack(int number, String title, String performer, String songwriter, String isrc,
            long gapMicros, long startMicros) {
    }
}
