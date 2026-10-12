package org.kendar.dlna.shuffle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.Normalizer;

/**
 * Tells whether two tracks are probably the same song (a live or remastered copy, a duplicate in another album), so
 * a random pick does not play it twice. Titles are normalized to letters and digits; two titles are alike when they
 * are equal, when the shorter one lies inside the other, or when they differ by a few characters (edit distance, the
 * Hamming distance of same-length titles included). Titles that differ in their digits ("Track 1", "Track 2", but "Imagine" is inside "Imagine 2010") and
 * very short titles only match when equal.
 */
public final class SimilarTitles {

    static final int MIN_FUZZY_LENGTH = 5;
    /** The shorter title must be at least this share of the longer one to count as "inside" it. */
    static final double MIN_CONTAINED_SHARE = 0.3;
    /** Share of the longer title that may differ. */
    static final double MAX_DISTANCE_SHARE = 0.15;

    private SimilarTitles() {
    }

    /** The comparison key of a track: its title tag, else the file name without extension. */
    public static String key(Track t) {
        String title = t.tags().title();
        if (title == null || title.isBlank()) {
            String n = t.fileName();
            int dot = n.lastIndexOf('.');
            title = dot > 0 ? n.substring(0, dot) : n;
        }
        return key(title);
    }

    static String key(String title) {
        StringBuilder sb = new StringBuilder();
        for (char c : Normalizer.normalize(title).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** True when the two keys (see {@link #key}) are probably the same song. */
    public static boolean similar(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        if (shorter.length() < MIN_FUZZY_LENGTH) {
            return false;
        }
        String ds = digits(shorter);
        boolean sameDigits = ds.equals(digits(longer));
        if ((sameDigits || ds.isEmpty()) && shorter.length() >= longer.length() * MIN_CONTAINED_SHARE
                && longer.contains(shorter)) {
            return true;
        }
        if (!sameDigits) {
            return false;
        }
        int max = Math.max(1, (int) (longer.length() * MAX_DISTANCE_SHARE));
        return longer.length() - shorter.length() <= max && distance(shorter, longer, max) <= max;
    }

    private static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    /** Edit distance; same-length strings of a single substitution run give their Hamming distance at most. */
    static int distance(String a, String b, int cap) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > cap) {
                return cap + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /**
     * The first {@code limit} tracks of {@code ordered} that are not alike, in order. When fewer than {@code limit}
     * distinct songs exist, the skipped look-alikes fill the rest (in their order), so the count is kept.
     */
    public static List<Track> distinct(List<Track> ordered, int limit) {
        List<Track> out = new ArrayList<>();
        List<Track> skipped = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        Set<String> exact = new HashSet<>();
        for (Track t : ordered) {
            if (out.size() >= limit) {
                break;
            }
            String k = key(t);
            boolean dup = !k.isEmpty() && (exact.contains(k) || keys.stream().anyMatch(o -> similar(k, o)));
            if (dup) {
                skipped.add(t);
            } else {
                out.add(t);
                keys.add(k);
                exact.add(k);
            }
        }
        for (Track t : skipped) {
            if (out.size() >= limit) {
                break;
            }
            out.add(t);
        }
        return out;
    }
}
