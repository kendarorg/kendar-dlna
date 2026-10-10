package org.kendar.dlna.artwork;

import java.util.Locale;
import java.util.Optional;

/** Rendition sizes of the web player: small (lists), medium (grids) and large (full screen); longest edge in px. */
public enum ArtSize {
    SMALL(160), MEDIUM(300), LARGE(800);

    private final int pixels;

    ArtSize(int pixels) {
        this.pixels = pixels;
    }

    public int pixels() {
        return pixels;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ArtSize> parse(String s) {
        for (ArtSize a : values()) {
            if (a.wire().equals(s == null ? "" : s.toLowerCase(Locale.ROOT))) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }
}
