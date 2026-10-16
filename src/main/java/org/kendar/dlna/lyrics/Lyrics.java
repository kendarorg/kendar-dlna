package org.kendar.dlna.lyrics;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The lyrics of a track: either time-synced lines ({@code synced}, each line has a start in milliseconds) or plain
 * text lines. {@code source} says where they came from ({@code lrc}, {@code txt}, {@code tag}, {@code kar} or a
 * provider id).
 */
public record Lyrics(String source, boolean synced, List<Line> lines) {

    /** One line; {@code startMillis} is -1 for plain lyrics. */
    public record Line(long startMillis, String text) {
    }

    public Lyrics {
        lines = List.copyOf(lines);
    }

    public boolean isEmpty() {
        return lines.stream().allMatch(l -> l.text().isBlank());
    }

    /** The plain text, one line per line. */
    public String text() {
        return lines.stream().map(Line::text).collect(Collectors.joining("\n"));
    }

    /** The {@code .lrc} form of synced lyrics (plain lyrics: just the text). */
    public String toLrc() {
        if (!synced) {
            return text() + "\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            long ms = Math.max(0, l.startMillis());
            sb.append(String.format(Locale.ROOT, "[%02d:%02d.%02d]", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10))
                    .append(l.text()).append('\n');
        }
        return sb.toString();
    }
}
