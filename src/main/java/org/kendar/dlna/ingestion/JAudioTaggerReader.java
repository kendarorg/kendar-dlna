package org.kendar.dlna.ingestion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.TrackTags;

/**
 * {@link TagReader} backed by jaudiotagger: MP3, OGG Vorbis, FLAC, WAV, AIFF and MP4 audio (AAC, ALAC). Raw ADTS
 * {@code .aac} files have no tag container (jaudiotagger has no reader for them): they get empty tags.
 */
public final class JAudioTaggerReader implements TagReader {

    private static final Set<Format> SUPPORTED = EnumSet.of(Format.MP3, Format.OGG, Format.FLAC, Format.WAV,
            Format.AIFF,
            Format.AAC, Format.ALAC, Format.ADTS);

    static {
        // jaudiotagger logs every oddity at INFO/WARNING; tag problems are reported through exceptions instead
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    @Override
    public boolean supports(Format format) {
        return SUPPORTED.contains(format);
    }

    @Override
    public TrackMeta read(Path file, Format format) throws TagReadException {
        if (format == Format.ADTS) {
            return new TrackMeta(TrackTags.EMPTY, 0, 0, 0, 0, false);   // exact figures come from the decoder probe
        }
        try {
            AudioFile af = AudioFileIO.read(file.toFile());
            AudioHeader h = af.getAudioHeader();
            Format actual = format == Format.AAC || format == Format.ALAC
                    ? ("Alac".equalsIgnoreCase(h.getEncodingType()) ? Format.ALAC : Format.AAC) : null;
            long millis = Math.round(h.getPreciseTrackLength() * 1000.0);
            if (millis <= 0) {
                millis = h.getTrackLength() * 1000L;
            }
            int channels = parseInt(h.getChannels());
            Tag tag = af.getTag();
            if (tag == null) {
                return new TrackMeta(TrackTags.EMPTY, millis, (int) h.getBitRateAsNumber(),
                        h.getSampleRateAsNumber(), channels, false, 0, actual);
            }
            TrackTags tags = new TrackTags(
                    text(tag, FieldKey.TITLE), text(tag, FieldKey.ARTIST), text(tag, FieldKey.ALBUM),
                    text(tag, FieldKey.ALBUM_ARTIST), text(tag, FieldKey.GENRE),
                    leadingInt(text(tag, FieldKey.YEAR)), leadingInt(text(tag, FieldKey.TRACK)),
                    leadingInt(text(tag, FieldKey.DISC_NO)), leadingInt(text(tag, FieldKey.DISC_TOTAL)),
                    texts(tag, FieldKey.ARTIST), texts(tag, FieldKey.ALBUM_ARTIST), texts(tag, FieldKey.GENRE),
                    text(tag, FieldKey.COMPOSER), texts(tag, FieldKey.COMPOSER));
            boolean art = !tag.getArtworkList().isEmpty();
            return new TrackMeta(tags, millis, (int) h.getBitRateAsNumber(), h.getSampleRateAsNumber(), channels,
                    art, 0, actual);
        } catch (Exception e) {
            throw new TagReadException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static String text(Tag tag, FieldKey key) {
        try {
            String v = tag.getFirst(key);
            return v == null || v.isBlank() ? null : v.strip();
        } catch (RuntimeException e) {
            return null;   // field not supported by this tag format
        }
    }

    /** All values of a multi-value field (only when there is more than one; else the single value suffices). */
    private static List<String> texts(Tag tag, FieldKey key) {
        try {
            List<String> all = new ArrayList<>();
            for (String v : tag.getAll(key)) {
                if (v != null && !v.isBlank() && !all.contains(v.strip())) {
                    all.add(v.strip());
                }
            }
            return all.size() > 1 ? all : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Parses "7", "7/12" or "2024-05-01" style values to their leading integer. */
    static Integer leadingInt(String s) {
        if (s == null) {
            return null;
        }
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0 || i > 9) {
            return null;
        }
        return Integer.valueOf(s.substring(0, i));
    }

    private static int parseInt(String s) {
        Integer v = leadingInt(s);
        return v == null ? 0 : v;
    }
}
