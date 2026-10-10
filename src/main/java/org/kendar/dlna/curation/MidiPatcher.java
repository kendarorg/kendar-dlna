package org.kendar.dlna.curation;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kendar.dlna.ingestion.MidiTagReader;

/**
 * Patches the title, artist and year of a Standard MIDI File directly on its bytes: the header and every chunk and
 * event that is not touched stay byte for byte as they were (no re-serialisation through {@code javax.sound.midi},
 * which would rewrite running status, VLQs and the meta events). The conventions are those of
 * {@link MidiTagReader}:
 * <ul>
 *   <li><b>title</b>: the sequence name (meta 0x03) of the first track; when the file has karaoke {@code @T} lines
 *       the first one is kept consistent with it;</li>
 *   <li><b>artist</b>: the second {@code @T} line (title line first, artist line second);</li>
 *   <li><b>year</b>: the four-digit year of the first copyright notice (meta 0x02).</li>
 * </ul>
 * Replaced events stay where they are (their delta time is kept, the VLQ length and the chunk length are
 * recomputed). Missing events are inserted with delta 0 at the very start of the first track, the new
 * {@code @T} artist line directly after the first {@code @T} line; both positions are in front of a track's first
 * event or right behind a meta event, so running status of the channel events is never disturbed. A removed event
 * hands its delta to the next one so that no later event moves.
 * <p>
 * Text encoding: a replaced event keeps its encoding (the reader decodes UTF-8 first, then ISO-8859-1): an event
 * that was not valid UTF-8 is written as ISO-8859-1 when the new text fits in it; everything else (including new
 * events) is UTF-8, which equals ISO-8859-1 for plain ASCII.
 * <p>
 * Clearing the year removes the year digits of the copyright text; the event itself goes away only when nothing
 * but a bare "(c)"/"copyright" is left (so an owner name survives).
 * <p>
 * Anything unexpected (truncated or inconsistent chunks, wrong header length, no track, a first track without
 * end-of-track, an event the structure does not allow, RIFF-wrapped files) is refused with a
 * {@link TagWriter.TagWriteException}.
 */
final class MidiPatcher {

    private static final int TEXT = 0x01;
    private static final int COPYRIGHT = 0x02;
    private static final int TRACK_NAME = 0x03;
    private static final int END_OF_TRACK = 0x2F;
    private static final int MAX_VLQ = 0x0FFFFFFF;
    private static final Pattern YEAR = Pattern.compile("(?<![\\p{N}])(19\\d{2}|20\\d{2})(?![\\p{N}])");
    private static final Pattern NO_CONTENT = Pattern.compile("(?i)\\(c\\)|©|copyright|[\\p{Punct}\\s]");

    private MidiPatcher() {
    }

    // ---- model ----

    /** One event: delta time and the raw bytes from the status byte on. */
    private static final class Ev {
        byte[] delta;
        long deltaValue;
        byte[] body;
        boolean meta;
        int type;
        byte[] data;
        Chunk owner;

        static Ev metaText(int type, byte[] text, Chunk owner, byte[] delta, long deltaValue) {
            Ev e = new Ev();
            e.meta = true;
            e.type = type;
            e.data = text;
            byte[] len = vlq(text.length);
            byte[] b = new byte[2 + len.length + text.length];
            b[0] = (byte) 0xFF;
            b[1] = (byte) type;
            System.arraycopy(len, 0, b, 2, len.length);
            System.arraycopy(text, 0, b, 2 + len.length, text.length);
            e.body = b;
            e.delta = delta;
            e.deltaValue = deltaValue;
            e.owner = owner;
            return e;
        }

        boolean isMeta(int wanted) {
            return meta && type == wanted;
        }

        /** Decoded like the tag reader does, not stripped. */
        String raw() {
            return MidiTagReader.decode(data);
        }

        String stripped() {
            return raw().strip();
        }
    }

    private static final class Chunk {
        final int start;
        final int end;
        final boolean track;
        List<Ev> events;
        boolean modified;

        Chunk(int start, int end, boolean track) {
            this.start = start;
            this.end = end;
            this.track = track;
        }
    }

    // ---- entry point ----

    /**
     * @param smf  the file
     * @param edit only title, artist and year are looked at ({@code null} = unchanged, empty = clear)
     * @return the patched file
     */
    static byte[] patch(byte[] smf, TagEdit edit) throws TagWriter.TagWriteException {
        List<Chunk> chunks = parse(smf);
        List<Chunk> tracks = chunks.stream().filter(c -> c.track).toList();
        Chunk first = tracks.get(0);

        List<Ev> names = new ArrayList<>();
        for (Ev e : first.events) {
            if (e.isMeta(TRACK_NAME) && !e.stripped().isEmpty()) {
                names.add(e);
            }
        }
        List<Ev> lines = new ArrayList<>();
        Ev copyright = null;
        for (Chunk t : tracks) {
            for (Ev e : t.events) {
                if (e.isMeta(TEXT) && e.stripped().startsWith("@T") && !lineText(e).isEmpty()) {
                    lines.add(e);
                } else if (e.isMeta(COPYRIGHT) && copyright == null && !e.stripped().isEmpty()) {
                    copyright = e;
                }
            }
        }
        String currentTitle = !names.isEmpty() ? names.get(0).stripped()
                : !lines.isEmpty() ? lineText(lines.get(0)) : null;
        String currentArtist = lines.size() > 1 ? lineText(lines.get(1)) : null;

        String title = edit.title();
        String artist = edit.artist();
        String year = edit.year();
        String finalTitle = title == null ? currentTitle : title.isEmpty() ? null : title;
        String finalArtist = artist == null ? currentArtist : artist.isEmpty() ? null : artist;
        if ((title != null || artist != null) && finalTitle == null && finalArtist != null) {
            throw refuse("a MIDI file stores the artist as the second karaoke line after the title, so an artist "
                    + "needs a title");
        }
        if (year != null && !year.isEmpty()) {
            int y = year.matches("\\d{1,4}") ? Integer.parseInt(year) : -1;
            if (y < 1900 || y > 2099) {
                throw refuse("a MIDI year must be between 1900 and 2099");
            }
        }

        List<Ev> atStart = new ArrayList<>();

        if (title != null) {
            if (title.isEmpty()) {
                for (Ev n : names) {
                    remove(n);
                }
                if (!lines.isEmpty()) {
                    remove(lines.remove(0));
                }
            } else {
                if (!names.isEmpty()) {
                    replaceText(names.get(0), title);
                } else {
                    atStart.add(newText(first, TRACK_NAME, title, null));
                }
                if (!lines.isEmpty()) {
                    lines.set(0, replaceText(lines.get(0), "@T" + title));
                }
            }
        }
        if (artist != null) {
            if (artist.isEmpty()) {
                if (lines.size() > 1) {
                    remove(lines.remove(1));
                }
            } else if (lines.size() > 1) {
                replaceText(lines.get(1), "@T" + artist);
            } else if (lines.size() == 1) {
                insertAfter(lines.get(0), newText(lines.get(0).owner, TEXT, "@T" + artist, lines.get(0).data));
            } else {
                atStart.add(newText(first, TEXT, "@T" + finalTitle, null));
                atStart.add(newText(first, TEXT, "@T" + artist, null));
            }
        }
        if (year != null) {
            if (year.isEmpty()) {
                if (copyright != null) {
                    clearYear(copyright);
                }
            } else if (copyright != null) {
                setYear(copyright, year);
            } else {
                atStart.add(newText(first, COPYRIGHT, "(c) " + year, null));
            }
        }
        if (!atStart.isEmpty()) {
            first.events.addAll(0, atStart);
            first.modified = true;
        }
        return write(smf, chunks);
    }

    private static String lineText(Ev line) {
        return line.stripped().substring(2).strip();
    }

    // ---- edits ----

    private static void setYear(Ev copyright, String year) {
        String text = copyright.raw();
        Matcher m = YEAR.matcher(text);
        String neu = m.find() ? text.substring(0, m.start()) + year + text.substring(m.end())
                : "(c) " + year + " " + text.strip();
        replaceText(copyright, neu);
    }

    private static void clearYear(Ev copyright) throws TagWriter.TagWriteException {
        String text = copyright.raw();
        if (!YEAR.matcher(text).find()) {
            return;
        }
        String rest = YEAR.matcher(text).replaceAll("").replaceAll("[ \\t]{2,}", " ").strip();
        if (NO_CONTENT.matcher(rest).replaceAll("").isEmpty()) {
            remove(copyright);
        } else {
            replaceText(copyright, rest);
        }
    }

    /** Replaces the event in place (delta kept); returns the new event. */
    private static Ev replaceText(Ev old, String text) {
        Ev neu = Ev.metaText(old.type, encode(text, old.data), old.owner, old.delta, old.deltaValue);
        List<Ev> list = old.owner.events;
        list.set(list.indexOf(old), neu);
        old.owner.modified = true;
        return neu;
    }

    private static Ev newText(Chunk owner, int type, String text, byte[] likeData) {
        return Ev.metaText(type, encode(text, likeData), owner, new byte[] { 0 }, 0);
    }

    private static void insertAfter(Ev ref, Ev neu) {
        List<Ev> list = ref.owner.events;
        list.add(list.indexOf(ref) + 1, neu);
        ref.owner.modified = true;
    }

    /** Removes a meta event; its delta moves to the next event so that every later event keeps its tick. */
    private static void remove(Ev e) throws TagWriter.TagWriteException {
        List<Ev> list = e.owner.events;
        int i = list.indexOf(e);
        Ev next = list.get(i + 1);   // the end-of-track event is last and is never removed
        if (e.deltaValue > 0) {
            long sum = e.deltaValue + next.deltaValue;
            if (sum > MAX_VLQ) {
                throw refuse("delta time overflow while removing an event");
            }
            next.deltaValue = sum;
            next.delta = vlq((int) sum);
        }
        list.remove(i);
        e.owner.modified = true;
    }

    /** UTF-8, or ISO-8859-1 when the replaced text was not valid UTF-8 and the new one fits. */
    private static byte[] encode(String text, byte[] like) {
        if (like != null && !validUtf8(like) && StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) {
            return text.getBytes(StandardCharsets.ISO_8859_1);
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean validUtf8(byte[] data) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    // ---- parse ----

    private static TagWriter.TagWriteException refuse(String why) {
        return new TagWriter.TagWriteException("Cannot edit this MIDI file: " + why, null);
    }

    private static int u32(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static boolean tag(byte[] b, int p, String id) {
        return b[p] == id.charAt(0) && b[p + 1] == id.charAt(1) && b[p + 2] == id.charAt(2) && b[p + 3] == id.charAt(3);
    }

    private static List<Chunk> parse(byte[] b) throws TagWriter.TagWriteException {
        if (b.length >= 4 && tag(b, 0, "RIFF")) {
            throw refuse("RIFF-wrapped MIDI (RMID) files cannot be edited");
        }
        if (b.length < 14 || !tag(b, 0, "MThd")) {
            throw refuse("not a Standard MIDI File (no MThd header)");
        }
        if (u32(b, 4) != 6) {
            throw refuse("unexpected MThd length " + u32(b, 4));
        }
        int format = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
        int ntrks = ((b[10] & 0xFF) << 8) | (b[11] & 0xFF);
        if (format > 2) {
            throw refuse("unknown MIDI file format " + format);
        }
        List<Chunk> chunks = new ArrayList<>();
        int p = 14;
        int trackIndex = 0;
        while (p < b.length) {
            if (b.length - p < 8) {
                throw refuse("truncated chunk header at byte " + p);
            }
            long len = u32(b, p + 4) & 0xFFFFFFFFL;
            int body = p + 8;
            if (len > b.length - body) {
                throw refuse("chunk at byte " + p + " is longer than the file");
            }
            boolean isTrack = tag(b, p, "MTrk");
            Chunk c = new Chunk(p, body + (int) len, isTrack);
            if (isTrack) {
                c.events = parseTrack(b, body, c.end, c, trackIndex++);
            }
            chunks.add(c);
            p = c.end;
        }
        if (trackIndex == 0) {
            throw refuse("no MTrk chunk");
        }
        if (trackIndex != ntrks) {
            throw refuse("the header announces " + ntrks + " tracks but the file has " + trackIndex);
        }
        List<Ev> firstEvents = chunks.stream().filter(c -> c.track).findFirst().orElseThrow().events;
        if (firstEvents.isEmpty() || !firstEvents.get(firstEvents.size() - 1).isMeta(END_OF_TRACK)) {
            throw refuse("the first track has no end-of-track event");
        }
        return chunks;
    }

    private static List<Ev> parseTrack(byte[] b, int start, int end, Chunk owner, int index)
            throws TagWriter.TagWriteException {
        List<Ev> out = new ArrayList<>();
        int p = start;
        int running = -1;
        while (p < end) {
            int ds = p;
            long delta = 0;
            int n = 0;
            int c;
            do {
                if (p >= end || ++n > 4) {
                    throw refuse("bad delta time in track " + index);
                }
                c = b[p++] & 0xFF;
                delta = (delta << 7) | (c & 0x7F);
            } while ((c & 0x80) != 0);
            if (p >= end) {
                throw refuse("track " + index + " ends after a delta time");
            }
            Ev e = new Ev();
            e.owner = owner;
            e.delta = Arrays.copyOfRange(b, ds, p);
            e.deltaValue = delta;
            int bs = p;
            int st = b[p] & 0xFF;
            if (st == 0xFF) {
                if (p + 2 > end) {
                    throw refuse("truncated meta event in track " + index);
                }
                e.meta = true;
                e.type = b[p + 1] & 0xFF;
                p += 2;
                long[] len = readVlq(b, p, end, index);
                p = (int) len[1];
                int size = checkLen(len[0], p, end, index);
                e.data = Arrays.copyOfRange(b, p, p + size);
                p += size;
            } else if (st == 0xF0 || st == 0xF7) {
                p++;
                long[] len = readVlq(b, p, end, index);
                p = (int) len[1];
                p += checkLen(len[0], p, end, index);
            } else if (st >= 0xF1) {
                throw refuse("unexpected status byte " + Integer.toHexString(st) + " in track " + index);
            } else {
                if (st >= 0x80) {
                    running = st;
                    p++;
                } else if (running < 0) {
                    throw refuse("running status without a status byte in track " + index);
                }
                int dataBytes = (running & 0xF0) == 0xC0 || (running & 0xF0) == 0xD0 ? 1 : 2;
                if (p + dataBytes > end) {
                    throw refuse("truncated channel event in track " + index);
                }
                for (int i = 0; i < dataBytes; i++) {
                    if ((b[p + i] & 0x80) != 0) {
                        throw refuse("bad channel event data in track " + index);
                    }
                }
                p += dataBytes;
            }
            e.body = Arrays.copyOfRange(b, bs, p);
            out.add(e);
            if (e.isMeta(END_OF_TRACK) && p != end) {
                throw refuse("data after the end-of-track event of track " + index);
            }
        }
        return out;
    }

    private static long[] readVlq(byte[] b, int p, int end, int index) throws TagWriter.TagWriteException {
        long v = 0;
        int n = 0;
        int c;
        do {
            if (p >= end || ++n > 4) {
                throw refuse("bad event length in track " + index);
            }
            c = b[p++] & 0xFF;
            v = (v << 7) | (c & 0x7F);
        } while ((c & 0x80) != 0);
        return new long[] { v, p };
    }

    private static int checkLen(long len, int p, int end, int index) throws TagWriter.TagWriteException {
        if (len > end - p) {
            throw refuse("event longer than its track " + index);
        }
        return (int) len;
    }

    // ---- write ----

    private static byte[] vlq(int value) {
        if (value < 0 || value > MAX_VLQ) {
            throw new IllegalArgumentException("VLQ out of range: " + value);
        }
        byte[] tmp = new byte[4];
        int n = 0;
        int v = value;
        tmp[n++] = (byte) (v & 0x7F);
        while ((v >>>= 7) > 0) {
            tmp[n++] = (byte) ((v & 0x7F) | 0x80);
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = tmp[n - 1 - i];
        }
        return out;
    }

    private static byte[] write(byte[] smf, List<Chunk> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(smf.length + 64);
        out.write(smf, 0, 14);
        for (Chunk c : chunks) {
            if (!c.modified) {
                out.write(smf, c.start, c.end - c.start);
                continue;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (Ev e : c.events) {
                body.writeBytes(e.delta);
                body.writeBytes(e.body);
            }
            int len = body.size();
            out.write('M');
            out.write('T');
            out.write('r');
            out.write('k');
            out.write(len >>> 24);
            out.write(len >>> 16);
            out.write(len >>> 8);
            out.write(len);
            out.writeBytes(body.toByteArray());
        }
        return out.toByteArray();
    }
}
