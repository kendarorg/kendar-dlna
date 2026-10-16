package org.kendar.dlna.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code .lrc} text (time-tagged lines, several tags per line, an {@code [offset:]} tag) or plain text. */
public final class LrcParser {

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern META = Pattern.compile("^\\[([a-zA-Z]+):(.*)]\\s*$");
    private static final int MAX_LINES = 5000;

    private LrcParser() {
    }

    /** Synced lyrics when at least one line carries a time tag, else plain text lines. */
    public static Lyrics parse(String text, String source) {
        List<Lyrics.Line> synced = new ArrayList<>();
        List<Lyrics.Line> plain = new ArrayList<>();
        long offset = 0;
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (synced.size() >= MAX_LINES || plain.size() >= MAX_LINES) {
                break;
            }
            String line = raw.strip();
            Matcher meta = META.matcher(line);
            if (meta.matches() && !TIME.matcher(line).lookingAt()) {
                if (meta.group(1).equalsIgnoreCase("offset")) {
                    try {
                        offset = Long.parseLong(meta.group(2).strip().replace("+", ""));
                    } catch (NumberFormatException e) {
                        offset = 0;
                    }
                }
                continue;
            }
            Matcher m = TIME.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                stamps.add(toMillis(m.group(1), m.group(2), m.group(3)));
                end = m.end();
            }
            String content = line.substring(end).strip();
            if (stamps.isEmpty()) {
                plain.add(new Lyrics.Line(-1, raw.stripTrailing()));
            } else {
                for (long s : stamps) {
                    synced.add(new Lyrics.Line(Math.max(0, s - offset), content));
                }
            }
        }
        if (!synced.isEmpty()) {
            synced.sort(Comparator.comparingLong(Lyrics.Line::startMillis));
            return new Lyrics(source, true, synced);
        }
        while (!plain.isEmpty() && plain.get(plain.size() - 1).text().isBlank()) {
            plain.remove(plain.size() - 1);
        }
        return new Lyrics(source, false, plain);
    }

    private static long toMillis(String min, String sec, String frac) {
        long ms = Long.parseLong(min) * 60_000 + Long.parseLong(sec) * 1000;
        if (frac != null) {
            String f = (frac + "00").substring(0, 3);   // .5 = 500 ms, .55 = 550 ms, .555 = 555 ms
            ms += Long.parseLong(f);
        }
        return ms;
    }
}
