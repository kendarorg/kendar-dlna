package org.kendar.dlna.ingestion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Track;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.TrackTags;

/**
 * Metadata of MIDI files ({@code .mid}, {@code .midi}, {@code .kar}). MIDI has no tag block, so only what the
 * format carries is mapped onto the ID3-style fields; album, genre and track number stay empty (set the album
 * through {@code settings.json}):
 * <ul>
 *   <li><b>title</b>: the sequence/track name (meta type 0x03) of the <em>first</em> track, which by the standard
 *       names the sequence (later tracks name instruments); else the karaoke {@code @T} title line;</li>
 *   <li><b>artist</b>: the second karaoke {@code @T} line (the usual {@code .kar} convention: title, then artist);</li>
 *   <li><b>year</b>: a four-digit year in the first copyright notice (meta type 0x02), e.g. "(c) 1996 ...".</li>
 * </ul>
 * The duration is the sequence length. Only offered when MIDI rendering is available, because a file that cannot be
 * rendered cannot be served.
 */
public final class MidiTagReader implements TagReader {

    private static final int TEXT = 0x01;
    private static final int COPYRIGHT = 0x02;
    private static final int TRACK_NAME = 0x03;
    private static final Pattern YEAR = Pattern.compile("(?<![\\p{N}])(19\\d{2}|20\\d{2})(?![\\p{N}])");

    private final boolean renderingAvailable;
    private final String unavailableReason;

    /**
     * @param renderingAvailable whether MIDI can be rendered; when false the files are listed as unsupported
     * @param unavailableReason  shown to the admin when it cannot (e.g. "no SoundFont configured")
     */
    public MidiTagReader(boolean renderingAvailable, String unavailableReason) {
        this.renderingAvailable = renderingAvailable;
        this.unavailableReason = unavailableReason;
    }

    @Override
    public boolean supports(Format format) {
        return format == Format.MIDI && renderingAvailable;
    }

    @Override
    public boolean owns(Format format) {
        return format == Format.MIDI;
    }

    @Override
    public String unsupportedReason(Format format) {
        return "MIDI rendering unavailable: " + unavailableReason;
    }

    @Override
    public TrackMeta read(Path file, Format format) throws TagReadException {
        try {
            Sequence seq = MidiSystem.getSequence(file.toFile());
            long millis = seq.getMicrosecondLength() / 1000;
            return new TrackMeta(tags(seq), millis, 0, 0, 0, false);
        } catch (Exception e) {
            throw new TagReadException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The ID3-aligned tags a sequence carries; every field is null when the file has nothing for it. */
    public static TrackTags tags(Sequence seq) {
        Track[] tracks = seq.getTracks();
        String sequenceName = null;
        String copyright = null;
        java.util.List<String> karaoke = new java.util.ArrayList<>();
        for (int t = 0; t < tracks.length; t++) {
            Track track = tracks[t];
            for (int i = 0; i < track.size(); i++) {
                MidiEvent e = track.get(i);
                if (!(e.getMessage() instanceof MetaMessage m)) {
                    continue;
                }
                String text = switch (m.getType()) {
                    case TRACK_NAME, COPYRIGHT, TEXT -> decode(m.getData()).strip();
                    default -> null;
                };
                if (text == null || text.isEmpty()) {
                    continue;
                }
                if (m.getType() == TRACK_NAME && t == 0 && sequenceName == null) {
                    sequenceName = text;
                } else if (m.getType() == COPYRIGHT && copyright == null) {
                    copyright = text;
                } else if (m.getType() == TEXT && text.startsWith("@T")) {
                    String line = text.substring(2).strip();
                    if (!line.isEmpty()) {
                        karaoke.add(line);
                    }
                }
            }
        }
        String title = sequenceName != null ? sequenceName : karaoke.size() > 0 ? karaoke.get(0) : null;
        String artist = karaoke.size() > 1 ? karaoke.get(1) : null;
        Integer year = null;
        if (copyright != null) {
            Matcher y = YEAR.matcher(copyright);
            if (y.find()) {
                year = Integer.valueOf(y.group(1));
            }
        }
        return new TrackTags(title, artist, null, null, null, year, null, null, null);
    }

    /** UTF-8 when valid, else ISO-8859-1 (the usual encoding of old MIDI files). */
    public static String decode(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
                    .replace("\0", "");
        } catch (CharacterCodingException ex) {
            return new String(data, StandardCharsets.ISO_8859_1).replace("\0", "");
        }
    }
}
