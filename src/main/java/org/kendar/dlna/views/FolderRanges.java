package org.kendar.dlna.views;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SortMode;

/**
 * The letter-range layout of a directory's real children: when the directory is name-sorted and has more
 * sub-directories (or more tracks) than {@code subitems}, they are listed through at most {@code ranges}
 * containers ("A-C", "D-Ha", ...), folders first. Each kind is grouped on its own and only when it alone exceeds
 * the threshold; a range that is still too long is split again. Ids are derived from the directory id and the
 * position, so they are stable while the content does not change.
 */
public final class FolderRanges {

    /** An entry of the layout: a real child or a range container. */
    public sealed interface Entry {
    }

    /** A real sub-directory or track listed directly. */
    public record Real(Child child) implements Entry {
    }

    /** A range container; {@code parentId} is the id of the enclosing range, or null for the directory itself. */
    public record Range(String id, String parentId, String label, List<Entry> entries) implements Entry {
        public Range {
            entries = List.copyOf(entries);
        }
    }

    /** The top-level entries of a directory and every range by id. */
    public record Layout(List<Entry> top, Map<String, Range> index) {
        public boolean grouped() {
            return !index.isEmpty();
        }
    }

    /** Context prefix of the range containers: {@code g.<dirId>.<f|t><n>[.<n>...]}. */
    public static final String CONTEXT_PREFIX = "g.";

    private FolderRanges() {
    }

    /** Whether a sort mode orders by name (letter ranges only make sense then). */
    static boolean nameSorted(SortMode sort) {
        return sort == SortMode.FILENAME || sort == SortMode.TITLE;
    }

    /**
     * @param subitems default threshold (a directory's own, inherited value wins)
     * @param ranges   default most ranges
     */
    public static Layout layout(Snapshot snap, Folder folder, int subitems, int ranges) {
        List<Child> children = snap.children(folder.ref());
        DirectorySettings eff = snap.effectiveSettings(folder.ref());
        int sub = eff.subitems() != null ? eff.subitems() : subitems;
        int rng = eff.ranges() != null ? eff.ranges() : ranges;
        if (folder.isVirtual() || !nameSorted(eff.sort()) || children.size() <= sub) {
            return plain(children);
        }
        List<Child> folders = new ArrayList<>();
        List<Child> tracks = new ArrayList<>();
        for (Child c : children) {
            (c instanceof Child.FolderChild ? folders : tracks).add(c);
        }
        Map<String, Range> index = new HashMap<>();
        List<Entry> top = new ArrayList<>();
        group(folder.id(), "f", folders, sub, rng, null, top, index, 1);
        group(folder.id(), "t", tracks, sub, rng, null, top, index, 1);
        return new Layout(List.copyOf(top), Map.copyOf(index));
    }

    private static Layout plain(List<Child> children) {
        return new Layout(children.stream().<Entry>map(Real::new).toList(), Map.of());
    }

    /** Adds {@code items} to {@code out}: directly when short, else through range containers. */
    private static void group(String dirId, String path, List<Child> items, int sub, int rng, String parentId,
            List<Entry> out, Map<String, Range> index, int minWidth) {
        List<Ranges.Group> groups = Ranges.split(items.stream().map(FolderRanges::name).toList(), sub, rng, minWidth);
        if (groups.isEmpty()) {
            items.forEach(c -> out.add(new Real(c)));
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            Ranges.Group g = groups.get(i);
            String id = CONTEXT_PREFIX + dirId + "." + path + i;
            List<Entry> inner = new ArrayList<>();
            // path of the next level continues after a dot so ids stay unambiguous
            group(dirId, path + i + ".", items.subList(g.from(), g.to()), sub, rng, id, inner, index, g.width() + 1);
            Range r = new Range(id, parentId, g.label(), inner);
            index.put(id, r);
            out.add(r);
        }
    }

    private static String name(Child c) {
        return switch (c) {
            case Child.FolderChild f -> DisplayNames.folderTitle(f.folder());
            case Child.TrackChild t -> DisplayNames.trackTitle(t.track());
        };
    }

    /** The directory id of a {@code g.<dirId>.<...>} context, or null. */
    public static String directoryOf(String context) {
        if (context == null || !context.startsWith(CONTEXT_PREFIX)) {
            return null;
        }
        String[] parts = context.split("\\.");
        return parts.length >= 3 ? parts[1] : null;
    }
}
