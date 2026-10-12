package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Sequence;

/**
 * An ABC notation file ({@code .abc}): the playable tunes it holds, in file order. A tune starts at its {@code X:}
 * line and ends at the next blank line or {@code X:} line; the lines before the first tune (the file header) apply
 * to all of them. A tune without a single note is left out, so the position of a tune (from 1) is the same
 * wherever the file is read: the scanner, the tag reader and the renderer all use {@link #parse(Path)}.
 */
public final class AbcFile {

    /** Files larger than this are not read (a tune book of a few thousand tunes is well below). */
    public static final long MAX_BYTES = 8L * 1024 * 1024;

    /** One playable tune; {@code position} counts from 1 among the playable tunes of the file. */
    public record Tune(int position, int number, String title, String composer, String transcriber, String book,
            long durationMillis, List<String> lines) {

        /** The tune as MIDI (a new sequence each time). */
        public Sequence sequence() throws InvalidMidiDataException {
            Sequence seq = AbcMidi.convert(lines).sequence();
            if (seq == null) {
                throw new InvalidMidiDataException("tune " + number + " has no notes");
            }
            return seq;
        }
    }

    private AbcFile() {
    }

    /** All playable tunes of the file. */
    public static List<Tune> parse(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("ABC file is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        return parse(decode(Files.readAllBytes(file)));
    }

    public static List<Tune> parse(String text) {
        List<String> header = new ArrayList<>();
        List<List<String>> blocks = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        List<String> current = null;
        boolean keySeen = false;
        for (String line : text.split("\r\n|\n|\r", -1)) {
            if (line.startsWith("X:")) {
                current = new ArrayList<>();
                blocks.add(current);
                numbers.add(number(line));
                keySeen = false;
            } else if (current == null) {
                if (line.length() > 1 && line.charAt(1) == ':' || line.startsWith("%%")) {
                    header.add(line);
                }
                continue;
            } else if (line.isBlank() && keySeen) {
                current = null;
                continue;
            } else if (line.startsWith("K:")) {
                keySeen = true;
            }
            if (current != null) {
                current.add(line);
            }
        }
        List<Tune> tunes = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            List<String> lines = new ArrayList<>(header);
            lines.addAll(blocks.get(i));
            try {
                AbcMidi.Result r = AbcMidi.convert(lines);
                if (r.sequence() == null) {
                    continue;
                }
                long millis = r.sequence().getMicrosecondLength() / 1000;
                tunes.add(new Tune(tunes.size() + 1, numbers.get(i), r.title(), r.composer(), r.transcriber(),
                        r.book(), millis, List.copyOf(lines)));
            } catch (InvalidMidiDataException | RuntimeException e) {
                // an unplayable tune is skipped
            }
        }
        return tunes;
    }

    private static int number(String xLine) {
        try {
            return Integer.parseInt(xLine.substring(2).strip().replaceAll("[^0-9].*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** UTF-8 when valid, else ISO-8859-1; a byte order mark is dropped. */
    static String decode(byte[] data) {
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            s = new String(data, StandardCharsets.ISO_8859_1);
        }
        return s.startsWith("﻿") ? s.substring(1) : s;
    }
}
