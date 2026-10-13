package org.kendar.dlna.shared;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The image files that count as the front or rear art of a directory, in priority order. {@code front.jpg} and
 * {@code rear.jpg} are the names the server writes itself; the other names are the ones other tools leave behind.
 * Names are matched case-insensitively; JPEG goes before PNG, GIF and BMP (all decodable by the thumbnailer).
 */
public final class ArtNames {

    /** Base names of front art, best first. */
    public static final List<String> FRONT = List.of("front", "cover", "folder", "album", "albumart", "albumartlarge");
    /** Base names of rear art, best first. */
    public static final List<String> REAR = List.of("rear", "back");
    /** Extensions, best first. */
    public static final List<String> EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "bmp");

    private ArtNames() {
    }

    /** Lower rank = better; -1 when the file name is not one of {@code bases} with an image extension. */
    static int rank(String fileName, List<String> bases) {
        String n = fileName.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot <= 0) {
            return -1;
        }
        int b = bases.indexOf(n.substring(0, dot));
        int e = EXTENSIONS.indexOf(n.substring(dot + 1));
        return b < 0 || e < 0 ? -1 : b * EXTENSIONS.size() + e;
    }

    public static boolean isFront(String fileName) {
        return rank(fileName, FRONT) >= 0;
    }

    public static boolean isRear(String fileName) {
        return rank(fileName, REAR) >= 0;
    }

    public static Optional<String> pickFront(Iterable<String> fileNames) {
        return pick(fileNames, FRONT);
    }

    public static Optional<String> pickRear(Iterable<String> fileNames) {
        return pick(fileNames, REAR);
    }

    private static Optional<String> pick(Iterable<String> fileNames, List<String> bases) {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (String n : fileNames) {
            int r = rank(n, bases);
            if (r >= 0 && (r < bestRank || (r == bestRank && n.compareTo(best) < 0))) {
                best = n;
                bestRank = r;
            }
        }
        return Optional.ofNullable(best);
    }
}
