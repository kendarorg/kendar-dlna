package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.kendar.dlna.catalog.Format;

/**
 * The lyrics a track already has, without any network: a sidecar {@code .lrc} (synced) or {@code .txt} next to the
 * file, the lyric events of a {@code .kar} / MIDI file, or the lyrics tag of the audio file (ID3 USLT, Vorbis LYRICS,
 * MP4 lyrics), in that order.
 */
public final class LocalLyrics {

    private static final Logger LOG = Logger.getLogger(LocalLyrics.class.getName());
    static final long MAX_BYTES = 1024 * 1024;

    private LocalLyrics() {
    }

    /** The sidecar file for a given extension ({@code lrc} or {@code txt}): same name, other extension. */
    public static Path sidecar(Path audio, String extension) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return audio.resolveSibling(base + "." + extension);
    }

    public static Optional<Lyrics> read(Path audio, Format format) {
        Optional<Lyrics> lrc = readText(sidecar(audio, "lrc"), "lrc");
        if (lrc.isPresent()) {
            return lrc;
        }
        Optional<Lyrics> txt = readText(sidecar(audio, "txt"), "txt");
        if (txt.isPresent()) {
            return txt;
        }
        if (format == Format.MIDI) {
            Optional<Lyrics> kar = readKar(audio);
            if (kar.isPresent()) {
                return kar;
            }
        }
        if (format != null && !format.rendered()) {
            return readTag(audio);
        }
        return Optional.empty();
    }

    private static Optional<Lyrics> readText(Path file, String source) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
                return Optional.empty();
            }
            Lyrics l = LrcParser.parse(decode(Files.readAllBytes(file)), source);
            return l.isEmpty() ? Optional.empty() : Optional.of(l);
        } catch (IOException | RuntimeException e) {
            LOG.fine("Cannot read " + file + ": " + e);
            return Optional.empty();
        }
    }

    /** UTF-8 (BOM allowed), else windows-1252. */
    static String decode(byte[] bytes) {
        int skip = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    private static Optional<Lyrics> readTag(Path audio) {
        try {
            java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
            Tag tag = AudioFileIO.read(audio.toFile()).getTag();
            if (tag == null) {
                return Optional.empty();
            }
            String text = tag.getFirst(FieldKey.LYRICS);
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            Lyrics l = LrcParser.parse(text, "tag");
            return l.isEmpty() ? Optional.empty() : Optional.of(l);
        } catch (Exception e) {
            return Optional.empty();   // no tag, unsupported format or no lyrics field
        }
    }

    // ---- .kar / MIDI lyric events ------------------------------------------------------------------------------

    /** Lyrics from the lyric (or text) meta events of a MIDI file, timed with its tempo map. */
    static Optional<Lyrics> readKar(Path file) {
        try {
            Sequence seq = MidiSystem.getSequence(file.toFile());
            if (seq.getDivisionType() != Sequence.PPQ) {
                return Optional.empty();
            }
            TreeMap<Long, Integer> tempo = new TreeMap<>();   // tick -> microseconds per quarter note
            tempo.put(0L, 500_000);
            List<long[]> lyricTicks = new ArrayList<>();
            List<String> lyricTexts = new ArrayList<>();
            List<long[]> textTicks = new ArrayList<>();
            List<String> textTexts = new ArrayList<>();
            for (javax.sound.midi.Track track : seq.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent ev = track.get(i);
                    if (!(ev.getMessage() instanceof MetaMessage meta)) {
                        continue;
                    }
                    byte[] d = meta.getData();
                    if (meta.getType() == 0x51 && d.length == 3) {
                        tempo.put(ev.getTick(), ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF));
                    } else if (meta.getType() == 0x05) {
                        lyricTicks.add(new long[] { ev.getTick() });
                        lyricTexts.add(new String(d, StandardCharsets.ISO_8859_1));
                    } else if (meta.getType() == 0x01) {
                        String t = new String(d, StandardCharsets.ISO_8859_1);
                        if (!t.startsWith("@")) {
                            textTicks.add(new long[] { ev.getTick() });
                            textTexts.add(t);
                        }
                    }
                }
            }
            List<long[]> ticks = lyricTexts.isEmpty() ? textTicks : lyricTicks;
            List<String> texts = lyricTexts.isEmpty() ? textTexts : lyricTexts;
            if (texts.isEmpty()) {
                return Optional.empty();
            }
            List<Lyrics.Line> lines = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            long start = -1;
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i);
                long ms = millis(ticks.get(i)[0], tempo, seq.getResolution());
                for (char c : t.toCharArray()) {
                    if (c == '/' || c == '\\' || c == '\n' || c == '\r') {
                        if (!cur.toString().isBlank()) {
                            lines.add(new Lyrics.Line(Math.max(0, start), cur.toString().strip()));
                        }
                        cur.setLength(0);
                        start = -1;
                    } else {
                        if (start < 0) {
                            start = ms;
                        }
                        cur.append(c);
                    }
                }
            }
            if (!cur.toString().isBlank()) {
                lines.add(new Lyrics.Line(Math.max(0, start), cur.toString().strip()));
            }
            Lyrics l = new Lyrics("kar", true, lines);
            return l.isEmpty() ? Optional.empty() : Optional.of(l);
        } catch (Exception e) {
            LOG.fine("No lyrics in " + file.getFileName().toString().toLowerCase(Locale.ROOT) + ": " + e);
            return Optional.empty();
        }
    }

    private static long millis(long tick, TreeMap<Long, Integer> tempo, int resolution) {
        double micros = 0;
        long prevTick = 0;
        int prevTempo = tempo.firstEntry().getValue();
        for (var e : tempo.entrySet()) {
            if (e.getKey() > tick) {
                break;
            }
            micros += (double) (e.getKey() - prevTick) * prevTempo / resolution;
            prevTick = e.getKey();
            prevTempo = e.getValue();
        }
        micros += (double) (tick - prevTick) * prevTempo / resolution;
        return Math.round(micros / 1000.0);
    }
}
