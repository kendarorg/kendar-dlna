package org.kendar.dlna.catalog;

import java.util.Locale;
import java.util.Optional;

/** Virtual views a directory can enable (settings.json {@code views}). */
public enum ViewKind {
    ALBUM, ARTIST, GENRE, TITLE, COMPOSER;

    public static Optional<ViewKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ViewKind k : values()) {
            if (k.name().equals(value.strip().toUpperCase(Locale.ROOT))) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }
}
