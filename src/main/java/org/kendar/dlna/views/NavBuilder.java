package org.kendar.dlna.views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Navigation;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.StableIds;

/**
 * Builds the container tree of one configured {@link Navigation} over a pool of tracks: one level per tag of the
 * navigation's {@code index} (title ends the chain: it is the list of tracks), tracks at the end. Keys are
 * normalized (case, whitespace, accents) and the displayed spelling is the most frequent variant. A level (or a
 * track list) with more than {@code subitems} entries is listed through at most {@code ranges} containers
 * ("A-C", "D-Ha"), split again while still too long. Group ids do not depend on the ranges, so they stay stable.
 * Pure and deterministic.
 */
final class NavBuilder {

    private final String directoryId;
    private final Navigation nav;
    private final List<String> levels;
    private final int subitems;
    private final int ranges;
    private final Map<String, ViewNode> index = new HashMap<>();

    /** One group of a level before it becomes a node. */
    private record Group(String name, String gid, ViewNode.Type type, String artist, Integer year,
            List<EffectiveTrack> pool) {
    }

    /** What lies under a node: child containers or tracks. */
    private record Content(List<ViewNode> nodes, List<Track> tracks) {
    }

    NavBuilder(String directoryId, Navigation nav, int subitems, int ranges) {
        this.directoryId = directoryId;
        this.nav = nav;
        List<String> l = new ArrayList<>();
        for (String tag : nav.index()) {
            l.add(tag);
            if (tag.equalsIgnoreCase("title")) {
                break;   // title is the list of tracks: nothing lies below it
            }
        }
        this.levels = List.copyOf(l);
        this.subitems = nav.subitems() != null ? nav.subitems() : subitems;
        this.ranges = nav.ranges() != null ? nav.ranges() : ranges;
    }

    static String rootId(String directoryId, Navigation nav) {
        return Views.CONTEXT_PREFIX + directoryId + "." + nav.key();
    }

    static String title(Navigation nav) {
        return "[" + nav.name() + "]";
    }

    ViewBuilder.Tree build(List<EffectiveTrack> pool) {
        String rootId = rootId(directoryId, nav);
        Content c = content(rootId, rootId, pool, 0, false);
        ViewNode root = register(new ViewNode(rootId, null, ViewNode.Type.ROOT, title(nav), null, null, c.nodes(),
                c.tracks(), first(c)));
        return new ViewBuilder.Tree(root, Map.copyOf(index));
    }

    private ViewNode register(ViewNode n) {
        index.put(n.id(), n);
        return n;
    }

    private static Track first(Content c) {
        if (!c.tracks().isEmpty()) {
            return c.tracks().get(0);
        }
        return c.nodes().isEmpty() ? null : c.nodes().get(0).artTrack();
    }

    // ---- levels ---------------------------------------------------------------------------------------------

    private Content content(String logical, String visual, List<EffectiveTrack> pool, int depth, boolean albumSeen) {
        if (depth >= levels.size() || levels.get(depth).equalsIgnoreCase("title")) {
            return leaf(visual, pool, albumSeen);
        }
        String tag = levels.get(depth);
        List<Group> groups = groups(tag, pool, depth > 0 && peopleTag(levels.get(depth - 1)));
        List<ViewNode> nodes = place(logical, visual, groups, depth, albumSeen || tag.equalsIgnoreCase("album"));
        return new Content(nodes, List.of());
    }

    private static boolean peopleTag(String tag) {
        return tag.equalsIgnoreCase("artist") || tag.equalsIgnoreCase("albumArtist")
                || tag.equalsIgnoreCase("composer");
    }

    /** Lists the groups under {@code visual}, through range containers when there are too many. */
    private List<ViewNode> place(String logical, String visual, List<Group> groups, int depth, boolean albumSeen) {
        List<Ranges.Group> rs = Ranges.split(groups.stream().map(Group::name).toList(), subitems, ranges);
        if (rs.isEmpty()) {
            List<ViewNode> out = new ArrayList<>();
            for (Group g : groups) {
                out.add(materialize(logical, visual, g, depth, albumSeen));
            }
            return out;
        }
        List<ViewNode> out = new ArrayList<>();
        for (int i = 0; i < rs.size(); i++) {
            Ranges.Group r = rs.get(i);
            String bucketId = visual + ".r" + i;
            List<ViewNode> kids = place(logical, bucketId, groups.subList(r.from(), r.to()), depth, albumSeen);
            out.add(register(new ViewNode(bucketId, visual, ViewNode.Type.BUCKET, r.label(), null, null, kids,
                    List.of(), kids.get(0).artTrack())));
        }
        return out;
    }

    private ViewNode materialize(String logical, String visual, Group g, int depth, boolean albumSeen) {
        String id = logical + "." + g.gid();
        Content c = content(id, id, g.pool(), depth + 1, albumSeen);
        List<ViewNode> nodes = new ArrayList<>(c.nodes());
        String tag = levels.get(depth);
        boolean allTracks = peopleTag(tag) && depth + 1 < levels.size()
                && levels.get(depth + 1).equalsIgnoreCase("album");
        Track art = first(c);
        if (allTracks && !nodes.isEmpty()) {
            List<Track> all = new ArrayList<>();
            collectTracks(nodes, all);
            nodes.add(register(new ViewNode(id + ".all", id, ViewNode.Type.ALL_TRACKS, "All tracks", g.name(), null,
                    List.of(), all, all.get(0))));
        }
        return register(new ViewNode(id, visual, g.type(), g.name(), g.artist(), g.year(), nodes, c.tracks(), art));
    }

    private static void collectTracks(List<ViewNode> nodes, List<Track> out) {
        for (ViewNode n : nodes) {
            if (n.type() == ViewNode.Type.ALL_TRACKS) {
                continue;
            }
            out.addAll(n.tracks());
            collectTracks(n.children(), out);
        }
    }

    // ---- leaf: the tracks -------------------------------------------------------------------------------------

    private Content leaf(String visual, List<EffectiveTrack> pool, boolean albumSeen) {
        List<EffectiveTrack> sorted = new ArrayList<>(pool);
        if (albumSeen) {
            sorted.sort(ViewBuilder.ALBUM_ORDER);
        } else {
            sorted.sort(Comparator.comparing((EffectiveTrack e) -> org.kendar.dlna.catalog.DisplayNames
                    .trackTitle(e.track()), Normalizer.NATURAL).thenComparing(e -> e.track().ref()));
        }
        List<Track> tracks = sorted.stream().map(EffectiveTrack::track).toList();
        return trackList(visual, tracks);
    }

    private Content trackList(String visual, List<Track> tracks) {
        List<Ranges.Group> rs = Ranges.split(tracks.stream().map(org.kendar.dlna.catalog.DisplayNames::trackTitle)
                .toList(), subitems, ranges);
        if (rs.isEmpty()) {
            return new Content(List.of(), tracks);
        }
        List<ViewNode> out = new ArrayList<>();
        for (int i = 0; i < rs.size(); i++) {
            Ranges.Group r = rs.get(i);
            String bucketId = visual + ".r" + i;
            Content inner = trackList(bucketId, tracks.subList(r.from(), r.to()));
            out.add(register(new ViewNode(bucketId, visual, ViewNode.Type.BUCKET, r.label(), null, null,
                    inner.nodes(), inner.tracks(), first(inner))));
        }
        return new Content(out, List.of());
    }

    // ---- grouping ---------------------------------------------------------------------------------------------

    private List<Group> groups(String tag, List<EffectiveTrack> pool, boolean underPeople) {
        if (tag.equalsIgnoreCase("album")) {
            List<ViewBuilder.AlbumGroup> albums = ViewBuilder.albumGroups(pool);
            if (underPeople) {
                albums.sort(Comparator.comparing((ViewBuilder.AlbumGroup a) -> a.year,
                        Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                        .thenComparing(a -> a.title, Normalizer.NATURAL));
            } else {
                albums.sort(Comparator.comparing((ViewBuilder.AlbumGroup a) -> a.title, Normalizer.NATURAL)
                        .thenComparing(a -> a.artist == null ? "" : a.artist, Normalizer.NATURAL));
            }
            List<Group> out = new ArrayList<>();
            for (ViewBuilder.AlbumGroup a : albums) {
                out.add(new Group(a.title, a.id, ViewNode.Type.ALBUM, a.artist, a.year, a.tracks));
            }
            return out;
        }
        Map<String, Acc> byKey = new LinkedHashMap<>();
        for (EffectiveTrack et : pool) {
            List<String> values = TagValues.of(et, tag);
            if (values.isEmpty()) {
                values = List.of("");
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String v : values) {
                String key = Normalizer.normalize(v);
                if (!seen.add(key)) {
                    continue;   // the same value twice on one track counts once
                }
                Acc acc = byKey.computeIfAbsent(key, k -> new Acc());
                if (!v.isBlank()) {
                    acc.variants.add(v);
                }
                acc.pool.add(et);
            }
        }
        String unknown = "Unknown " + Character.toUpperCase(tag.charAt(0)) + tag.substring(1);
        ViewNode.Type type = typeOf(tag);
        List<Group> out = new ArrayList<>();
        byKey.forEach((key, acc) -> out.add(new Group(acc.variants.isEmpty() ? unknown : acc.variants.best(),
                StableIds.id("vg", tag.toLowerCase(Locale.ROOT), key), type, null, null, acc.pool)));
        // real values first (bitrate bands by quality, the rest naturally), the "Unknown ..." group last
        out.sort(Comparator.comparing((Group g) -> g.name().equals(unknown))
                .thenComparingInt((Group g) -> TagValues.rank(tag, g.name()))
                .thenComparing(Group::name, Normalizer.NATURAL));
        return out;
    }

    private static ViewNode.Type typeOf(String tag) {
        if (peopleTag(tag)) {
            return ViewNode.Type.ARTIST;
        }
        if (tag.equalsIgnoreCase("genre")) {
            return ViewNode.Type.GENRE;
        }
        return ViewNode.Type.GROUP;
    }

    private static final class Acc {
        final ViewBuilder.Variants variants = new ViewBuilder.Variants();
        final List<EffectiveTrack> pool = new ArrayList<>();
    }
}
