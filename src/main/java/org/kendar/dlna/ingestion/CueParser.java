package org.kendar.dlna.ingestion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.kendar.dlna.ingestion.CueSheet.CueFile;
import org.kendar.dlna.ingestion.CueSheet.CueTrack;

/**
 * Tolerant cue sheet parser; never throws. Text is UTF-8 (BOM skipped), UTF-16 with BOM, else Windows-1252.
 * Commands are case-insensitive, values may be quoted or not, {@code FILE} names may contain spaces and quotes,
 * several {@code FILE}s are supported, {@code INDEX 00} / {@code INDEX 01} are read (INDEX 01 is the start).
 * Unknown commands, non-audio tracks, tracks without INDEX 01 and tracks whose start does not increase are
 * skipped with a warning. {@code REM GENRE}, {@code REM DATE} and {@code REM DISCNUMBER} are honoured.
 */
public final class CueParser {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private CueParser() {
    }

    public static CueSheet parse(byte[] bytes) {
        return parse(decode(bytes));
    }

    public static CueSheet parse(String text) {
        List<String> warnings = new ArrayList<>();
        String title = null;
        String performer = null;
        String songwriter = null;
        String genre = null;
        Integer year = null;
        Integer disc = null;

        List<CueFile> files = new ArrayList<>();
        String fileName = null;
        boolean haveFile = false;
        List<CueTrack> tracks = new ArrayList<>();

        boolean inTrack = false;
        boolean audio = true;
        int number = 0;
        String tTitle = null;
        String tPerformer = null;
        String tSongwriter = null;
        String tIsrc = null;
        long gap = -1;
        long start = -1;
        int sequence = 0;
        int lineNo = 0;

        for (String raw : text.split("\r\n|\r|\n", -1)) {
            lineNo++;
            String line = raw.strip();
            if (line.startsWith("﻿")) {
                line = line.substring(1).strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            String cmd = word(line);
            String rest = line.substring(cmd.length()).strip();
            switch (cmd.toUpperCase(Locale.ROOT)) {
                case "REM" -> {
                    String key = word(rest);
                    String value = unquote(rest.substring(key.length()).strip());
                    switch (key.toUpperCase(Locale.ROOT)) {
                        case "GENRE" -> genre = blank(value);
                        case "DATE" -> year = leadingInt(value) != null ? leadingInt(value) : year;
                        case "DISCNUMBER" -> disc = leadingInt(value);
                        case "COMPOSER" -> songwriter = songwriter == null ? blank(value) : songwriter;
                        default -> { /* COMMENT, DISCID, REPLAYGAIN_*: not used */ }
                    }
                }
                case "TITLE" -> {
                    if (inTrack) {
                        tTitle = blank(unquote(rest));
                    } else {
                        title = blank(unquote(rest));
                    }
                }
                case "PERFORMER" -> {
                    if (inTrack) {
                        tPerformer = blank(unquote(rest));
                    } else {
                        performer = blank(unquote(rest));
                    }
                }
                case "SONGWRITER" -> {
                    if (inTrack) {
                        tSongwriter = blank(unquote(rest));
                    } else {
                        songwriter = blank(unquote(rest));
                    }
                }
                case "ISRC" -> tIsrc = blank(rest);
                case "FILE" -> {
                    if (inTrack) {
                        addTrack(tracks, audio, number, tTitle, tPerformer, tSongwriter, tIsrc, gap, start, warnings);
                        inTrack = false;
                    }
                    if (haveFile || !tracks.isEmpty()) {
                        files.add(new CueFile(fileName, tracks));
                        tracks = new ArrayList<>();
                    }
                    fileName = fileNameOf(rest);
                    haveFile = true;
                    if (fileName == null) {
                        warnings.add("line " + lineNo + ": FILE without a name");
                    }
                }
                case "TRACK" -> {
                    if (inTrack) {
                        addTrack(tracks, audio, number, tTitle, tPerformer, tSongwriter, tIsrc, gap, start, warnings);
                    }
                    inTrack = true;
                    sequence++;
                    String[] p = rest.split("\\s+");
                    Integer n = p.length > 0 ? leadingInt(p[0]) : null;
                    number = n != null ? n : sequence;
                    audio = p.length < 2 || p[1].equalsIgnoreCase("AUDIO");
                    tTitle = null;
                    tPerformer = null;
                    tSongwriter = null;
                    tIsrc = null;
                    gap = -1;
                    start = -1;
                    if (!haveFile) {
                        warnings.add("line " + lineNo + ": TRACK before any FILE");
                        haveFile = true;
                    }
                }
                case "INDEX" -> {
                    if (!inTrack) {
                        warnings.add("line " + lineNo + ": INDEX outside a TRACK ignored");
                    } else {
                        String[] p = rest.split("\\s+");
                        long micros = p.length >= 2 ? time(p[1]) : -1;
                        Integer idx = p.length >= 1 ? leadingInt(p[0]) : null;
                        if (micros < 0 || idx == null) {
                            warnings.add("line " + lineNo + ": invalid INDEX '" + rest + "' ignored");
                        } else if (idx == 0) {
                            gap = micros;
                        } else if (idx == 1) {
                            start = micros;
                        }
                    }
                }
                case "PREGAP", "POSTGAP", "FLAGS", "CATALOG", "CDTEXTFILE" -> { /* not needed */ }
                default -> warnings.add("line " + lineNo + ": unknown command '" + cmd + "' ignored");
            }
        }
        if (inTrack) {
            addTrack(tracks, audio, number, tTitle, tPerformer, tSongwriter, tIsrc, gap, start, warnings);
        }
        if (haveFile || !tracks.isEmpty()) {
            files.add(new CueFile(fileName, tracks));
        }
        List<CueFile> out = new ArrayList<>();
        for (CueFile f : files) {
            List<CueTrack> ordered = new ArrayList<>();
            long last = -1;
            for (CueTrack t : f.tracks()) {
                if (t.startMicros() <= last) {
                    warnings.add("track " + t.number() + " of '" + f.name()
                            + "' does not start after the previous one, skipped");
                    continue;
                }
                last = t.startMicros();
                ordered.add(t);
            }
            out.add(new CueFile(f.name(), ordered));
        }
        return new CueSheet(title, performer, songwriter, genre, year, disc, out, warnings);
    }

    private static void addTrack(List<CueTrack> tracks, boolean audio, int number, String title, String performer,
            String songwriter, String isrc, long gap, long start, List<String> warnings) {
        if (!audio) {
            warnings.add("track " + number + " is not an audio track, skipped");
        } else if (start < 0) {
            warnings.add("track " + number + " has no INDEX 01, skipped");
        } else {
            tracks.add(new CueTrack(number, title, performer, songwriter, isrc, gap, start));
        }
    }

    /** Decodes the bytes of a cue file. */
    public static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        int off = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, b.length - off))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(b, off, b.length - off, WINDOWS_1252);
        }
    }

    private static String word(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(0, i);
    }

    /** Removes surrounding quotes (first to last quote of the value, inner quotes are kept). */
    static String unquote(String s) {
        String v = s.strip();
        if (v.length() >= 2 && v.charAt(0) == '"') {
            int end = v.lastIndexOf('"');
            if (end > 0) {
                return v.substring(1, end);
            }
        }
        if (v.length() >= 1 && v.charAt(0) == '"') {
            return v.substring(1);   // unterminated quote
        }
        return v;
    }

    /** FILE "name" TYPE | FILE name TYPE | FILE "name" (type missing). */
    static String fileNameOf(String rest) {
        String v = rest.strip();
        if (v.isEmpty()) {
            return null;
        }
        if (v.charAt(0) == '"') {
            int end = v.lastIndexOf('"');
            if (end > 0) {
                // FILE "a "b" c.wav" WAVE: the closing quote is the last one followed only by the type
                String tail = v.substring(end + 1).strip();
                if (tail.isEmpty() || tail.indexOf(' ') < 0) {
                    return blank(v.substring(1, end));
                }
            }
            return blank(end > 0 ? v.substring(1, end) : v.substring(1));
        }
        int sp = v.lastIndexOf(' ');
        if (sp > 0) {
            String type = v.substring(sp + 1).toUpperCase(Locale.ROOT);
            if (type.matches("WAVE|MP3|AIFF|BINARY|MOTOROLA|FLAC|APE|WAV|OGG|M4A|AAC|WV")) {
                return blank(v.substring(0, sp));
            }
        }
        return blank(v);
    }

    /** mm:ss:ff (75 frames per second) or mm:ss.fraction, in microseconds; -1 when invalid. */
    static long time(String s) {
        String[] p = s.split(":");
        try {
            if (p.length == 3) {
                long mm = Long.parseLong(p[0]);
                long ss = Long.parseLong(p[1]);
                long ff = Long.parseLong(p[2]);
                if (mm < 0 || ss < 0 || ss > 59 || ff < 0 || ff > 74) {
                    return -1;
                }
                return ((mm * 60 + ss) * 75 + ff) * 1_000_000L / 75;
            }
            if (p.length == 2) {
                long mm = Long.parseLong(p[0]);
                double ss = Double.parseDouble(p[1]);
                if (mm < 0 || ss < 0 || ss >= 60) {
                    return -1;
                }
                return Math.round((mm * 60 + ss) * 1_000_000d);
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        return -1;
    }

    private static Integer leadingInt(String s) {
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

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
