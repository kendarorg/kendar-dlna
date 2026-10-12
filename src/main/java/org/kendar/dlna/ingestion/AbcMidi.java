package org.kendar.dlna.ingestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts one ABC tune (its header lines, then the tune lines) into a {@link Sequence}. A pragmatic subset of ABC
 * 2.1 is understood: notes with accidentals, octaves and lengths, rests, chords, ties, broken rhythm, tuplets,
 * key signatures and modes, meter, default length, tempo ({@code Q:}, also inline), repeats with first / second
 * endings, several voices ({@code V:}) on separate channels and {@code %%MIDI program}. Chord symbols, decorations,
 * grace notes, slurs, lyrics and layout directives are skipped.
 */
final class AbcMidi {

    static final int PPQ = 480;
    private static final int VELOCITY = 80;
    private static final int MAX_NOTES = 400_000;
    private static final int[] SEMITONE = { 9, 11, 0, 2, 4, 5, 7 };   // A B C D E F G
    private static final String SHARPS = "FCGDAEB";
    private static final String FLATS = "BEADGCF";

    /** What a conversion found; {@code sequence} is null when the tune has no note at all. */
    record Result(Sequence sequence, String title, String composer, String transcriber, String book) {
    }

    private AbcMidi() {
    }

    private enum Kind { NOTES, REST, TEMPO, REPEAT_START, REPEAT_END, ENDING }

    private static final class Item {
        final Kind kind;
        long ticks;
        int[] pitches = new int[0];
        boolean[] tied = new boolean[0];
        int number;   // ENDING: the ending's number; TEMPO: microseconds per quarter

        Item(Kind kind, long ticks) {
            this.kind = kind;
            this.ticks = ticks;
        }
    }

    private static final class Voice {
        final List<Item> items = new ArrayList<>();
        int program;
    }

    private static final class Ctx {
        String title;
        String composer;
        String transcriber;
        String book;
        int meterNum = 4;
        int meterDen = 4;
        long unitNum = 1;
        long unitDen = 8;
        boolean unitSet;
        boolean inBody;
        int initialMicros = 500_000;
        final List<long[]> tempoNotes = new ArrayList<>();
        final int[] keyAcc = new int[7];
        final Map<String, Integer> barAcc = new HashMap<>();
        final Map<String, Voice> voices = new LinkedHashMap<>();
        String voiceId = "";
        Item last;               // last note or rest, for broken rhythm
        double pendingFactor = 1;
        int tupletLeft;
        double tupletFactor = 1;
        int notes;

        Voice voice() {
            return voices.computeIfAbsent(voiceId, k -> new Voice());
        }

        void selectVoice(String id) {
            if (!voices.containsKey(id) && voices.size() == 1 && voices.containsKey("") && voices.get("").items.isEmpty()) {
                voices.put(id, voices.remove(""));
            }
            voiceId = id;
            voice();
            last = null;
            pendingFactor = 1;
        }

        long measureTicks() {
            return Math.round(PPQ * 4.0 * meterNum / meterDen);
        }

        void applyDefaultUnit() {
            if (!unitSet) {
                unitNum = 1;
                unitDen = (double) meterNum / meterDen < 0.75 ? 16 : 8;
            }
        }
    }

    static Result convert(List<String> lines) throws InvalidMidiDataException {
        Ctx c = new Ctx();
        c.voice();
        for (String raw : lines) {
            line(c, raw);
        }
        if (c.notes == 0) {
            return new Result(null, c.title, c.composer, c.transcriber, c.book);
        }
        return new Result(build(c), c.title, c.composer, c.transcriber, c.book);
    }

    // ---- lines and fields ----

    private static void line(Ctx c, String raw) {
        String s = raw;
        if (s.startsWith("%%")) {
            directive(c, s.substring(2).strip());
            return;
        }
        int pct = s.indexOf('%');
        if (pct >= 0) {
            s = s.substring(0, pct);
        }
        s = s.stripTrailing();
        if (s.isBlank()) {
            return;
        }
        if (s.length() > 1 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0))
                && (s.length() == 2 || s.charAt(2) != ':' && s.charAt(2) != '|')) {
            field(c, s.charAt(0), s.substring(2).strip());
            return;
        }
        if (!c.inBody) {
            return;
        }
        body(c, s);
    }

    private static void directive(Ctx c, String d) {
        String[] w = d.split("\\s+");
        if (w.length >= 3 && w[0].equalsIgnoreCase("MIDI") && w[1].equalsIgnoreCase("program")) {
            try {
                c.voice().program = Math.max(0, Math.min(127, Integer.parseInt(w[w.length - 1])));
            } catch (NumberFormatException e) {
                // ignored
            }
        }
    }

    private static void field(Ctx c, char f, String v) {
        switch (f) {
            case 'T' -> {
                if (c.title == null && !v.isEmpty()) {
                    c.title = v;
                }
            }
            case 'C' -> {
                if (c.composer == null && !v.isEmpty()) {
                    c.composer = v;
                }
            }
            case 'Z' -> {
                if (c.transcriber == null && !v.isEmpty()) {
                    c.transcriber = v;
                }
            }
            case 'B' -> {
                if (c.book == null && !v.isEmpty()) {
                    c.book = v;
                }
            }
            case 'M' -> meter(c, v);
            case 'L' -> unit(c, v);
            case 'Q' -> tempo(c, v);
            case 'K' -> {
                key(c, v);
                if (!c.inBody) {
                    c.inBody = true;
                    c.applyDefaultUnit();
                }
            }
            case 'V' -> {
                String[] w = v.split("\\s+");
                if (w.length > 0 && !w[0].isEmpty()) {
                    c.selectVoice(w[0]);
                }
            }
            default -> {
                // X, P, N, R, O, S, W, w, ... : no effect on the sound
            }
        }
    }

    private static void meter(Ctx c, String v) {
        String m = v.strip();
        if (m.equals("C")) {
            c.meterNum = 4;
            c.meterDen = 4;
        } else if (m.equals("C|")) {
            c.meterNum = 2;
            c.meterDen = 2;
        } else {
            int slash = m.indexOf('/');
            if (slash > 0) {
                try {
                    int num = 0;
                    for (String part : m.substring(0, slash).replaceAll("[()]", "").split("\\+")) {
                        num += Integer.parseInt(part.strip());
                    }
                    int den = Integer.parseInt(m.substring(slash + 1).replaceAll("[^0-9].*", ""));
                    if (num > 0 && den > 0) {
                        c.meterNum = num;
                        c.meterDen = den;
                    }
                } catch (NumberFormatException e) {
                    // keep the previous meter
                }
            }
        }
        if (!c.inBody) {
            c.applyDefaultUnit();
        }
    }

    private static void unit(Ctx c, String v) {
        int slash = v.indexOf('/');
        try {
            if (slash > 0) {
                long n = Long.parseLong(v.substring(0, slash).strip());
                long d = Long.parseLong(v.substring(slash + 1).strip());
                if (n > 0 && d > 0) {
                    c.unitNum = n;
                    c.unitDen = d;
                    c.unitSet = true;
                }
            }
        } catch (NumberFormatException e) {
            // keep the default
        }
    }

    private static void tempo(Ctx c, String v) {
        String s = v.replaceAll("\"[^\"]*\"", " ").strip();
        double unitWhole = (double) (c.unitSet ? c.unitNum : 1) / (c.unitSet ? c.unitDen : (double) c.meterNum / c.meterDen < 0.75 ? 16 : 8);
        double bpm;
        try {
            int eq = s.indexOf('=');
            if (eq >= 0) {
                String left = s.substring(0, eq).strip().split("\\s+")[0];
                bpm = Double.parseDouble(s.substring(eq + 1).strip().split("\\s+")[0]);
                int slash = left.indexOf('/');
                if (slash > 0) {
                    unitWhole = Double.parseDouble(left.substring(0, slash)) / Double.parseDouble(left.substring(slash + 1));
                } else if (left.equals("C")) {
                    unitWhole = 0.25;
                }
            } else {
                bpm = Double.parseDouble(s.split("\\s+")[0]);
            }
        } catch (RuntimeException e) {
            return;
        }
        double qpm = bpm * unitWhole * 4;
        if (qpm < 20 || qpm > 600) {
            return;
        }
        int micros = (int) Math.round(60_000_000.0 / qpm);
        if (!c.inBody) {
            c.initialMicros = micros;
        } else {
            Item t = new Item(Kind.TEMPO, 0);
            t.number = micros;
            c.voice().items.add(t);
        }
    }

    private static void key(Ctx c, String v) {
        java.util.Arrays.fill(c.keyAcc, 0);
        String s = v.strip();
        if (s.isEmpty() || s.equalsIgnoreCase("none")) {
            return;
        }
        if (s.startsWith("HP") || s.startsWith("Hp")) {
            c.keyAcc[5] = 1;   // F#
            c.keyAcc[2] = 1;   // C#
            return;
        }
        char t = s.charAt(0);
        if (t < 'A' || t > 'G') {
            return;
        }
        int i = 1;
        int fifths = "FCGDAEB".indexOf(t) - 1;
        if (i < s.length() && s.charAt(i) == '#') {
            fifths += 7;
            i++;
        } else if (i < s.length() && s.charAt(i) == 'b') {
            fifths -= 7;
            i++;
        }
        String mode = s.substring(i).strip().toLowerCase();
        if (mode.startsWith("m") && !mode.startsWith("maj") && !mode.startsWith("mix")) {
            fifths -= 3;
        } else if (mode.startsWith("mix")) {
            fifths -= 1;
        } else if (mode.startsWith("dor")) {
            fifths -= 2;
        } else if (mode.startsWith("phr")) {
            fifths -= 4;
        } else if (mode.startsWith("lyd")) {
            fifths += 1;
        } else if (mode.startsWith("loc")) {
            fifths -= 5;
        } else if (mode.startsWith("aeo")) {
            fifths -= 3;
        }
        fifths = Math.max(-7, Math.min(7, fifths));
        for (int k = 0; k < Math.abs(fifths); k++) {
            char letter = fifths > 0 ? SHARPS.charAt(k) : FLATS.charAt(k);
            c.keyAcc[letter - 'A'] = fifths > 0 ? 1 : -1;
        }
    }

    // ---- tune body ----

    private static void body(Ctx c, String s) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            char ch = s.charAt(i);
            switch (ch) {
                case ' ', '\t', '\\', '`', '*', '&', '$', '#', 'y', '.', '~', 'H', 'L', 'M', 'O', 'P', 'S', 'T', 'u', 'v', ')' -> i++;
                case '"' -> i = skipTo(s, i + 1, '"');
                case '!' -> i = skipTo(s, i + 1, '!');
                case '+' -> {
                    int close = s.indexOf('+', i + 1);
                    i = close < 0 ? i + 1 : close + 1;
                }
                case '{' -> i = skipTo(s, i + 1, '}');
                case '(' -> i = tuplet(c, s, i);
                case '-' -> {
                    tieLast(c);
                    i++;
                }
                case '>', '<' -> i = broken(c, s, i);
                case '|', ':' -> i = bar(c, s, i);
                case '[' -> i = bracket(c, s, i);
                case 'z', 'x' -> i = rest(c, s, i, false);
                case 'Z' -> i = rest(c, s, i, true);
                default -> {
                    if (ch == '^' || ch == '_' || ch == '=' || (ch >= 'A' && ch <= 'G') || (ch >= 'a' && ch <= 'g')) {
                        i = notes(c, s, i);
                    } else {
                        i++;
                    }
                }
            }
        }
    }

    private static int skipTo(String s, int from, char end) {
        int close = s.indexOf(end, from);
        return close < 0 ? s.length() : close + 1;
    }

    private static int tuplet(Ctx c, String s, int i) {
        int j = i + 1;
        int[] v = new int[3];
        int count = 0;
        while (j < s.length() && count < 3) {
            int start = j;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            if (j == start) {
                break;
            }
            v[count++] = Integer.parseInt(s.substring(start, j));
            if (j < s.length() && s.charAt(j) == ':' && count < 3) {
                j++;
            } else {
                break;
            }
        }
        if (count == 0 || v[0] < 2) {
            return i + 1;   // a slur
        }
        int p = v[0];
        boolean compound = c.meterDen == 8 && c.meterNum % 3 == 0;
        int q = count > 1 && v[1] > 0 ? v[1] : switch (p) {
            case 2 -> 3;
            case 3 -> 2;
            case 4 -> 3;
            case 6 -> 2;
            case 8 -> 3;
            case 5, 7, 9 -> compound ? 3 : 2;
            default -> 2;
        };
        c.tupletLeft = count > 2 && v[2] > 0 ? v[2] : p;
        c.tupletFactor = (double) q / p;
        return j;
    }

    private static int broken(Ctx c, String s, int i) {
        char ch = s.charAt(i);
        int k = 0;
        while (i < s.length() && s.charAt(i) == ch) {
            k++;
            i++;
        }
        double small = Math.pow(2, -k);
        double big = 2 - small;
        if (c.last != null) {
            c.last.ticks = Math.max(1, Math.round(c.last.ticks * (ch == '>' ? big : small)));
        }
        c.pendingFactor = ch == '>' ? small : big;
        return i;
    }

    private static int bar(Ctx c, String s, int i) {
        int j = i;
        while (j < s.length() && "|:]".indexOf(s.charAt(j)) >= 0) {
            j++;
        }
        String run = s.substring(i, j);
        c.barAcc.clear();
        boolean end = run.startsWith(":");
        boolean start = run.endsWith(":") && run.length() > 1 || run.equals("::");
        if (run.equals("::")) {
            end = true;
            start = true;
        }
        if (run.equals(":")) {
            return j;   // a lone colon means nothing
        }
        if (end) {
            c.voice().items.add(new Item(Kind.REPEAT_END, 0));
        }
        if (start) {
            c.voice().items.add(new Item(Kind.REPEAT_START, 0));
        }
        int d = j;
        while (d < s.length() && Character.isDigit(s.charAt(d))) {
            d++;
        }
        if (d > j) {
            addEnding(c, s.substring(j, j + 1));
            j = d;
        }
        c.last = null;
        return j;
    }

    private static void addEnding(Ctx c, String digit) {
        Item e = new Item(Kind.ENDING, 0);
        e.number = Integer.parseInt(digit);
        c.voice().items.add(e);
    }

    private static int bracket(Ctx c, String s, int i) {
        if (i + 1 >= s.length()) {
            return i + 1;
        }
        char nx = s.charAt(i + 1);
        if (Character.isDigit(nx)) {
            addEnding(c, String.valueOf(nx));
            int j = i + 2;
            while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == ',' || s.charAt(j) == '-')) {
                j++;
            }
            return j;
        }
        if (nx == '|') {
            return bar(c, s, i + 1);
        }
        if (Character.isLetter(nx) && i + 2 < s.length() && s.charAt(i + 2) == ':') {
            int close = s.indexOf(']', i);
            int end = close < 0 ? s.length() : close;
            field(c, nx, s.substring(i + 3, end).strip());
            return close < 0 ? s.length() : close + 1;
        }
        return chord(c, s, i);
    }

    private static void tieLast(Ctx c) {
        Item last = c.last;
        if (last != null && last.kind == Kind.NOTES) {
            java.util.Arrays.fill(last.tied, true);
        }
    }

    /** One note head from {@code i}: its MIDI pitch (or -1) and the index after the pitch and octave marks. */
    private static int[] pitch(Ctx c, String s, int i) {
        int acc = Integer.MIN_VALUE;
        int j = i;
        while (j < s.length() && "^_=".indexOf(s.charAt(j)) >= 0) {
            char a = s.charAt(j);
            int step = a == '^' ? 1 : a == '_' ? -1 : 0;
            acc = acc == Integer.MIN_VALUE || a == '=' ? step : acc + step;
            j++;
        }
        if (j >= s.length()) {
            return new int[] { -1, j };
        }
        char letter = s.charAt(j);
        boolean upper = letter >= 'A' && letter <= 'G';
        if (!upper && !(letter >= 'a' && letter <= 'g')) {
            return new int[] { -1, j };
        }
        j++;
        int octave = upper ? 0 : 1;
        while (j < s.length() && (s.charAt(j) == '\'' || s.charAt(j) == ',')) {
            octave += s.charAt(j) == '\'' ? 1 : -1;
            j++;
        }
        int idx = Character.toUpperCase(letter) - 'A';
        String key = idx + ":" + octave;
        if (acc != Integer.MIN_VALUE) {
            c.barAcc.put(key, acc);
        }
        int offset = c.barAcc.getOrDefault(key, c.keyAcc[idx]);
        return new int[] { 60 + SEMITONE[idx] + 12 * octave + offset, j };
    }

    /** The length multiplier at {@code i} as {num, den, nextIndex}. */
    private static long[] length(String s, int i) {
        int j = i;
        long num = 1;
        long den = 1;
        int d = j;
        while (d < s.length() && Character.isDigit(s.charAt(d))) {
            d++;
        }
        if (d > j && d - j < 6) {
            num = Long.parseLong(s.substring(j, d));
            j = d;
        }
        if (j < s.length() && s.charAt(j) == '/') {
            int slashes = 0;
            while (j < s.length() && s.charAt(j) == '/') {
                slashes++;
                j++;
            }
            int e = j;
            while (e < s.length() && Character.isDigit(s.charAt(e))) {
                e++;
            }
            if (slashes == 1 && e > j && e - j < 6) {
                den = Long.parseLong(s.substring(j, e));
                j = e;
            } else {
                den = 1L << Math.min(slashes, 10);
            }
        }
        return new long[] { num, Math.max(1, den), j };
    }

    private static long ticks(Ctx c, long num, long den) {
        double t = PPQ * 4.0 * c.unitNum * num / (c.unitDen * den);
        t *= c.pendingFactor;
        c.pendingFactor = 1;
        if (c.tupletLeft > 0) {
            t *= c.tupletFactor;
            c.tupletLeft--;
        }
        return Math.max(1, Math.round(t));
    }

    private static int notes(Ctx c, String s, int i) {
        int[] p = pitch(c, s, i);
        if (p[0] < 0) {
            return Math.max(p[1], i + 1);
        }
        long[] len = length(s, p[1]);
        Item it = new Item(Kind.NOTES, ticks(c, len[0], len[1]));
        it.pitches = new int[] { p[0] };
        it.tied = new boolean[] { false };
        add(c, it);
        return (int) len[2];
    }

    private static int chord(Ctx c, String s, int i) {
        int close = s.indexOf(']', i);
        if (close < 0) {
            return i + 1;
        }
        List<Integer> pitches = new ArrayList<>();
        List<Boolean> ties = new ArrayList<>();
        long num = 1;
        long den = 1;
        boolean first = true;
        int j = i + 1;
        while (j < close) {
            char ch = s.charAt(j);
            if (ch == '"') {
                j = skipTo(s, j + 1, '"');
                continue;
            }
            if (ch == '!') {
                j = skipTo(s, j + 1, '!');
                continue;
            }
            if (ch == '-' && !ties.isEmpty()) {
                ties.set(ties.size() - 1, true);
                j++;
                continue;
            }
            int[] p = pitch(c, s, j);
            if (p[0] < 0) {
                j = Math.max(p[1], j + 1);
                continue;
            }
            long[] len = length(s, p[1]);
            if (first) {
                num = len[0];
                den = len[1];
                first = false;
            }
            pitches.add(p[0]);
            ties.add(false);
            j = (int) len[2];
        }
        long[] outer = length(s, close + 1);
        if (pitches.isEmpty()) {
            return (int) outer[2];
        }
        Item it = new Item(Kind.NOTES, ticks(c, num * outer[0], den * outer[1]));
        it.pitches = pitches.stream().mapToInt(Integer::intValue).toArray();
        it.tied = new boolean[it.pitches.length];
        for (int k = 0; k < it.tied.length; k++) {
            it.tied[k] = ties.get(k);
        }
        add(c, it);
        int after = (int) outer[2];
        if (after < s.length() && s.charAt(after) == '-') {
            java.util.Arrays.fill(it.tied, true);
            after++;
        }
        return after;
    }

    private static int rest(Ctx c, String s, int i, boolean measures) {
        long[] len = length(s, i + 1);
        Item it;
        if (measures) {
            it = new Item(Kind.REST, c.measureTicks() * len[0] / len[1]);
        } else {
            it = new Item(Kind.REST, ticks(c, len[0], len[1]));
        }
        c.voice().items.add(it);
        c.last = it;
        return (int) len[2];
    }

    private static void add(Ctx c, Item it) {
        c.voice().items.add(it);
        c.last = it;
        c.notes++;
    }

    // ---- timeline ----

    private record Played(long start, long dur, int pitch) {
    }

    private static Sequence build(Ctx c) throws InvalidMidiDataException {
        Sequence seq = new Sequence(Sequence.PPQ, PPQ);
        Track conductor = seq.createTrack();
        conductor.add(new MidiEvent(tempo(c.initialMicros), 0));
        int den = Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(1, c.meterDen)));
        conductor.add(new MidiEvent(new MetaMessage(0x58,
                new byte[] { (byte) Math.min(255, c.meterNum), (byte) den, 24, 8 }, 4), 0));
        int channelIndex = 0;
        int voiceIndex = 0;
        int budget = MAX_NOTES;
        for (Voice v : c.voices.values()) {
            if (v.items.stream().noneMatch(x -> x.kind == Kind.NOTES)) {
                voiceIndex++;
                continue;
            }
            int channel = channelIndex++ % 15;
            if (channel >= 9) {
                channel++;
            }
            Track track = seq.createTrack();
            track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, v.program, 0), 0));
            long[] end = new long[1];
            List<Played> played = expand(v.items, voiceIndex == 0 ? conductor : null, end);
            track.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), end[0]));
            for (Played p : played) {
                if (--budget < 0) {
                    throw new InvalidMidiDataException("the tune is too long");
                }
                long off = p.start + Math.max(1, p.dur - Math.max(1, p.dur / 16));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, channel, clamp(p.pitch), VELOCITY), p.start));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, channel, clamp(p.pitch), 0), off));
            }
            voiceIndex++;
        }
        return seq;
    }

    private static int clamp(int pitch) {
        return Math.max(0, Math.min(127, pitch));
    }

    private static MetaMessage tempo(int micros) throws InvalidMidiDataException {
        return new MetaMessage(0x51, new byte[] { (byte) (micros >> 16), (byte) (micros >> 8), (byte) micros }, 3);
    }

    /** Plays the items with repeats and endings expanded; ties are merged; tempo items go to the conductor. */
    private static List<Played> expand(List<Item> items, Track conductor, long[] endOut) throws InvalidMidiDataException {
        List<Played> out = new ArrayList<>();
        Map<Integer, Integer> open = new HashMap<>();   // pitch -> index in out of a note waiting for its tie
        int n = items.size();
        int i = 0;
        int start = 0;
        boolean second = false;
        long tick = 0;
        int guard = 0;
        while (i < n && guard++ < 1_000_000) {
            Item it = items.get(i);
            switch (it.kind) {
                case NOTES -> {
                    for (int k = 0; k < it.pitches.length; k++) {
                        int pitch = it.pitches[k];
                        Integer idx = open.get(pitch);
                        if (idx != null && out.get(idx).start + out.get(idx).dur == tick) {
                            Played o = out.get(idx);
                            out.set(idx, new Played(o.start, o.dur + it.ticks, o.pitch));
                            if (!it.tied[k]) {
                                open.remove(pitch);
                            }
                        } else {
                            out.add(new Played(tick, it.ticks, pitch));
                            if (it.tied[k]) {
                                open.put(pitch, out.size() - 1);
                            } else {
                                open.remove(pitch);
                            }
                        }
                    }
                    tick += it.ticks;
                    i++;
                }
                case REST -> {
                    tick += it.ticks;
                    i++;
                }
                case TEMPO -> {
                    if (conductor != null) {
                        conductor.add(new MidiEvent(tempo(it.number), tick));
                    }
                    i++;
                }
                case REPEAT_START -> {
                    start = i + 1;
                    second = false;
                    i++;
                }
                case ENDING -> {
                    if (second && it.number == 1) {
                        int j = i + 1;
                        while (j < n && !(items.get(j).kind == Kind.ENDING && items.get(j).number != 1)
                                && items.get(j).kind != Kind.REPEAT_START) {
                            j++;
                        }
                        i = j;
                    } else {
                        if (second && it.number >= 2) {
                            second = false;
                            start = i + 1;
                        }
                        i++;
                    }
                }
                case REPEAT_END -> {
                    if (!second) {
                        second = true;
                        i = start;
                    } else {
                        second = false;
                        start = i + 1;
                        i++;
                    }
                }
            }
        }
        endOut[0] = tick;
        return out;
    }
}
