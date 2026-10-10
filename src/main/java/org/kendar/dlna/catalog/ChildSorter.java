package org.kendar.dlna.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.kendar.dlna.shared.Normalizer;

/**
 * Orders the direct children of one directory per its settings (plan section 4). Non-explicit modes put
 * subdirectories first, then files. Comparisons are case/accent-insensitive with natural number ordering.
 */
public final class ChildSorter {

    /** Sorted children plus {@code order} entries that matched nothing (explicit mode only). */
    public record Result(List<Child> children, List<String> missing) {
    }

    private ChildSorter() {
    }

    public static Result sort(DirectorySettings settings, List<Folder> folders, List<Track> tracks) {
        Comparator<Folder> folderCmp = folderComparator(settings.sort());
        Comparator<Track> trackCmp = trackComparator(settings.sort());
        List<Folder> fs = new ArrayList<>(folders);
        List<Track> ts = new ArrayList<>(tracks);

        if (settings.sort() != SortMode.EXPLICIT) {
            fs.sort(folderCmp);
            ts.sort(trackCmp);
            return new Result(concat(fs, ts), List.of());
        }

        // explicit: listed entries in order (first match wins), the rest: subdirectories then files by name
        List<Child> out = new ArrayList<>();
        Set<Object> used = new HashSet<>();
        List<String> missing = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : settings.order()) {
            if (name == null || !seen.add(name)) {
                continue;
            }
            Child found = null;
            for (Folder f : fs) {
                if (f.ref().name().equals(name)) {
                    found = new Child.FolderChild(f);
                    used.add(f);
                    break;
                }
            }
            if (found == null) {
                for (Track t : ts) {
                    if (t.fileName().equals(name)) {
                        found = new Child.TrackChild(t);
                        used.add(t);
                        break;
                    }
                }
            }
            if (found == null) {
                missing.add(name);
            } else {
                out.add(found);
            }
        }
        List<Folder> restF = fs.stream().filter(f -> !used.contains(f)).sorted(folderComparator(SortMode.FILENAME))
                .toList();
        List<Track> restT = ts.stream().filter(t -> !used.contains(t)).sorted(trackComparator(SortMode.FILENAME))
                .toList();
        out.addAll(concat(restF, restT));
        return new Result(out, missing);
    }

    private static List<Child> concat(List<Folder> fs, List<Track> ts) {
        List<Child> out = new ArrayList<>(fs.size() + ts.size());
        fs.forEach(f -> out.add(new Child.FolderChild(f)));
        ts.forEach(t -> out.add(new Child.TrackChild(t)));
        return out;
    }

    private static Comparator<Folder> folderComparator(SortMode mode) {
        Comparator<Folder> byName = Comparator.comparing((Folder f) -> f.ref().name(), Normalizer.NATURAL);
        return switch (mode) {
            case TITLE, TRACK -> Comparator.comparing(DisplayNames::folderTitle, Normalizer.NATURAL)
                    .thenComparing(byName);
            case MTIME -> Comparator.comparingLong(Folder::mtime).thenComparing(byName);
            default -> byName;
        };
    }

    private static Comparator<Track> trackComparator(SortMode mode) {
        Comparator<Track> byName = Comparator.comparing(Track::fileName, Normalizer.NATURAL);
        return switch (mode) {
            case TITLE -> Comparator.comparing(DisplayNames::trackTitle, Normalizer.NATURAL).thenComparing(byName);
            case TRACK -> Comparator
                    .comparing((Track t) -> t.tags().discNo(), nullsLast())
                    .thenComparing((Track t) -> t.tags().trackNo(), nullsLast())
                    .thenComparing(byName);
            case MTIME -> Comparator.comparingLong(Track::mtime).thenComparing(byName);
            default -> byName;
        };
    }

    private static Comparator<Integer> nullsLast() {
        return Comparator.nullsLast(Comparator.<Integer>naturalOrder());
    }
}
