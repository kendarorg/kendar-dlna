package org.kendar.dlna.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Audio formats the server knows about. Most are identified by file extension; {@link #ALAC} shares the
 * {@code .m4a} extension with {@link #AAC} and is told apart by the codec inside the MP4 container (the tag reader
 * reports it, see {@code TrackMeta.format()}).
 */
public enum Format {
    MP3("mp3"),
    OGG("ogg", "oga"),
    FLAC("flac"),
    WAV("wav"),
    AIFF("aiff", "aif", "aifc"),
    MIDI("mid", "midi", "kar"),
    OPUS("opus"),
    /** AAC-LC in an MP4 container ({@code .m4a}, {@code .mp4}). */
    AAC("m4a", "mp4"),
    /** Apple Lossless in an MP4 container; found through {@code .m4a} files, hence no extension of its own. */
    ALAC(),
    /** Raw AAC-LC frames with ADTS headers ({@code .aac}): no container, so no tags. */
    ADTS("aac");

    private final List<String> extensions;

    Format(String... extensions) {
        this.extensions = List.of(extensions);
    }

    /** Names of all tag fields of a {@code TagEdit}, in a stable order. */
    public static final List<String> ALL_TAG_FIELDS = List.of("title", "artist", "album", "albumArtist", "genre",
            "year", "trackNo", "discNo", "discTotal", "composer");

    /** Whether the tags of files of this format can be written (at least partly; not Opus and raw ADTS AAC). */
    public boolean tagsEditable() {
        return this != OPUS && this != ADTS;
    }

    /**
     * The tag fields that can be written for this format: all of them, except MIDI (a sequence only holds a title,
     * an artist and a copyright year) and the formats without writable tags (none).
     */
    public List<String> editableFields() {
        if (this == MIDI) {
            return List.of("title", "artist", "year");
        }
        return tagsEditable() ? ALL_TAG_FIELDS : List.of();
    }

    public List<String> extensions() {
        return extensions;
    }

    /**
     * Whether a file recorded as {@code a} and one recognised by extension as {@code b} are the same kind of file:
     * equal, or both MP4 audio ({@link #AAC} / {@link #ALAC}, which share {@code .m4a}).
     */
    public static boolean sameFileKind(Format a, Format b) {
        return a == b || (isMp4Audio(a) && isMp4Audio(b));
    }

    private static boolean isMp4Audio(Format f) {
        return f == AAC || f == ALAC;
    }

    public static Optional<Format> fromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (Format f : values()) {
            if (f.extensions.contains(ext)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}
