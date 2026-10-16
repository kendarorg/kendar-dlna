package org.kendar.dlna.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The tag names a navigation can use: file tags, virtual tags computed from the track, and configured extra tags. */
public final class TagNames {

    /** Tags every track can have. */
    public static final List<String> TAGS = List.of("title", "artist", "album", "albumArtist", "genre", "year",
            "composer");
    /** Computed from the track itself (nothing is stored): the kind of file, its format and its bitrate band. */
    public static final List<String> VIRTUAL = List.of("type", "format", "bitrate");

    private TagNames() {
    }

    /** The canonical spelling of {@code name} among the known tags, virtual tags and {@code extra}, if any. */
    public static Optional<String> canonical(String name, Collection<String> extra) {
        if (name == null) {
            return Optional.empty();
        }
        String n = name.strip();
        for (String t : all(extra)) {
            if (t.equalsIgnoreCase(n)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Every usable tag name, in picker order. */
    public static List<String> all(Collection<String> extra) {
        List<String> out = new ArrayList<>(TAGS);
        out.addAll(VIRTUAL);
        if (extra != null) {
            for (String e : extra) {
                if (e != null && !e.isBlank() && out.stream().noneMatch(x -> x.equalsIgnoreCase(e.strip()))) {
                    out.add(e.strip());
                }
            }
        }
        return out;
    }

    public static boolean isVirtual(String name) {
        return VIRTUAL.stream().anyMatch(v -> v.equalsIgnoreCase(name));
    }
}
