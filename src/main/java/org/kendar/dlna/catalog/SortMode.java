package org.kendar.dlna.catalog;

import java.util.Locale;
import java.util.Optional;

/** Child ordering of a directory (settings.json {@code sort}). */
public enum SortMode {
    FILENAME, TITLE, TRACK, MTIME, EXPLICIT;

    public static Optional<SortMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (SortMode m : values()) {
            if (m.name().equals(value.strip().toUpperCase(Locale.ROOT))) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
