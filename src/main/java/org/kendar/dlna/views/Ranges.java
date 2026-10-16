package org.kendar.dlna.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a long, already ordered list of names into a few contiguous, count-balanced ranges labelled from their
 * first and last name ("A-C", "D-Ha", ...). Pure and deterministic: the same names always give the same ranges.
 */
public final class Ranges {

    /** The entries {@code [from, to)} of the list under one {@code label}; {@code width} characters were compared. */
    public record Group(int from, int to, String label, int width) {
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
        return split(names, subitems, ranges, 1);
    }

    /**
     * Same, for a list whose labels must be at least {@code minWidth} characters long (a list inside a range of
     * width 1 uses 2: "A-D", then "A0-A9", "AA-BC"). The cuts fall only between different prefixes, so no prefix is
     * in two ranges; the prefix grows (past {@code minWidth}) until the names offer at least {@code ranges}
     * different ones, or the names are exhausted. At most {@code ranges} groups, as balanced as the prefixes allow.
     */
    public static List<Group> split(List<String> names, int subitems, int ranges, int minWidth) {
        int n = names.size();
        if (n <= subitems || n < 2 || ranges < 2) {
            return List.of();
        }
        int longest = 1;
        for (String s : names) {
            longest = Math.max(longest, s == null ? 0 : s.strip().codePointCount(0, s.strip().length()));
        }
        int width = Math.max(1, minWidth);
        List<Integer> runs = runStarts(names, width);
        while (runs.size() < Math.min(ranges, n) && width < longest) {
            width++;
            runs = runStarts(names, width);
        }
        if (runs.size() < 2) {
            // identical names: nothing but the position can tell them apart
            runs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                runs.add(i);
            }
        }
        int groups = Math.min(ranges, runs.size());
        List<Integer> cuts = new ArrayList<>();   // index into runs where each group after the first starts
        int prev = 0;
        for (int g = 1; g < groups; g++) {
            double ideal = (double) n * g / groups;
            int best = -1;
            for (int r = prev + 1; r <= runs.size() - (groups - g); r++) {
                if (best < 0 || Math.abs(runs.get(r) - ideal) < Math.abs(runs.get(best) - ideal)) {
                    best = r;
                }
            }
            cuts.add(best);
            prev = best;
        }
        List<Group> out = new ArrayList<>();
        int startRun = 0;
        for (int g = 0; g < groups; g++) {
            int endRun = g < cuts.size() ? cuts.get(g) : runs.size();
            int from = runs.get(startRun);
            int to = endRun < runs.size() ? runs.get(endRun) : n;
            out.add(new Group(from, to, label(names.get(from), names.get(to - 1), width), width));
            startRun = endRun;
        }
        return out;
    }

    /** The indexes where a new prefix of {@code width} characters starts. */
    private static List<Integer> runStarts(List<String> names, int width) {
        List<Integer> out = new ArrayList<>();
        String last = null;
        for (int i = 0; i < names.size(); i++) {
            String h = head(names.get(i), width);
            if (!h.equals(last)) {
                out.add(i);
                last = h;
            }
        }
        return out;
    }

    /** The first {@code width} characters of the first and of the last name, as one label ("AA-BC", or "AA"). */
    static String label(String first, String last, int width) {
        String start = head(first, width);
        String end = head(last, width);
        return start.equals(end) ? start : start + "-" + end;
    }

    private static String head(String name, int width) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            return "#";
        }
        int end = n.offsetByCodePoints(0, Math.min(width, n.codePointCount(0, n.length())));
        return n.substring(0, end).toUpperCase(Locale.ROOT);
    }
}
