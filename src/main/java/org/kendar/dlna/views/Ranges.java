package org.kendar.dlna.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a long, already ordered list of names into a few contiguous, count-balanced ranges labelled from their
 * first and last name ("A-C", "D-Ha", ...). Pure and deterministic: the same names always give the same ranges.
 */
public final class Ranges {

    /** The entries {@code [from, to)} of the list under one {@code label}. */
    public record Group(int from, int to, String label) {
        public int size() {
            return to - from;
        }
    }

    private Ranges() {
    }

    /**
     * @param names    display names in list order
     * @param subitems the list is split only when it has more entries than this
     * @param ranges   the most groups to make (at least 2)
     * @return the groups, or an empty list when the list is short enough (or {@code ranges < 2})
     */
    public static List<Group> split(List<String> names, int subitems, int ranges) {
        int n = names.size();
        if (n <= subitems || n < 2 || ranges < 2) {
            return List.of();
        }
        int groups = Math.min(ranges, n);
        int base = n / groups;
        int extra = n % groups;
        List<int[]> bounds = new ArrayList<>();
        int at = 0;
        for (int g = 0; g < groups; g++) {
            int size = base + (g < extra ? 1 : 0);
            bounds.add(new int[] { at, at + size });
            at += size;
        }
        List<Group> out = new ArrayList<>();
        for (int g = 0; g < groups; g++) {
            int[] b = bounds.get(g);
            String first = names.get(b[0]);
            String last = names.get(b[1] - 1);
            String prevLast = g > 0 ? names.get(bounds.get(g - 1)[1] - 1) : null;
            String nextFirst = g < groups - 1 ? names.get(bounds.get(g + 1)[0]) : null;
            out.add(new Group(b[0], b[1], label(first, last, prevLast, nextFirst)));
        }
        return out;
    }

    /** The shortest prefixes that tell the range's first and last name apart from its neighbours. */
    static String label(String first, String last, String prevLast, String nextFirst) {
        String start = prefix(first, prevLast);
        String end = prefix(last, nextFirst);
        return start.equalsIgnoreCase(end) ? start : start + "-" + end;
    }

    /** The shortest prefix of {@code name} that differs from the same-length prefix of {@code other}. */
    private static String prefix(String name, String other) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            return "#";
        }
        if (other == null) {
            return head(n, 1);
        }
        String o = other.strip().toLowerCase(Locale.ROOT);
        String nl = n.toLowerCase(Locale.ROOT);
        int k = 1;
        while (k < n.length() && k <= o.length() && nl.regionMatches(0, o, 0, k)) {
            k++;
        }
        return head(n, Math.min(k, n.length()));
    }

    private static String head(String s, int codePoints) {
        int end = s.offsetByCodePoints(0, Math.min(codePoints, s.codePointCount(0, s.length())));
        String h = s.substring(0, end);
        return h.substring(0, h.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT)
                + h.substring(h.offsetByCodePoints(0, 1));
    }
}
