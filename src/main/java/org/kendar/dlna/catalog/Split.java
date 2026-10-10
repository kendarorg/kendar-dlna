package org.kendar.dlna.catalog;

import java.util.List;

/**
 * Marks a {@link Folder} as a virtual album: one audio file split into tracks. {@code hash} identifies what the
 * split was derived from ({@code cue:<hash of the .cue>} or {@code emb:<origin>}), {@code sourceMtime} /
 * {@code sourceSize} the file it was derived from; a rescan reuses the split while all of them are unchanged.
 * {@code cueFile} is the name of the .cue file (null for embedded sections), {@code warnings} tolerated problems.
 */
public record Split(String origin, String hash, long sourceMtime, long sourceSize, String cueFile,
        List<String> warnings) {

    public Split {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
