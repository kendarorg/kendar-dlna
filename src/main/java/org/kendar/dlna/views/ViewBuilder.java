package org.kendar.dlna.views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.StableIds;

/**
 * Builds the container tree of one view over a pool of tracks (plan section 8). Keys are normalized (case,
 * whitespace, accents); the displayed spelling is the most frequent variant. Pure and deterministic.
 */
final class ViewBuilder {

    static final String UNKNOWN_ARTIST = "Unknown Artist";
    static final String UNKNOWN_ALBUM = "Unknown Album";
    static final String UNKNOWN_GENRE = "Unknown Genre";
    static final String UNKNOWN_COMPOSER = "Unknown Composer";

    /** A computed view: its root and every node by id. */
    record Tree(ViewNode root, Map<String, ViewNode> index) {
    }

    private final String directoryId;
    private final int bucketThreshold;
    private final Map<String, ViewNode> index = new HashMap<>();

    ViewBuilder(String directoryId, int bucketThreshold) {
        this.directoryId = directoryId;
        this.bucketThreshold = bucketThreshold;
    }

    static String kindCode(ViewKind kind) {
        return switch (kind) {
            case ALBUM -> "al";
            case ARTIST -> "ar";
            case GENRE -> "ge";
            case TITLE -> "ti";
            case COMPOSER -> "co";
        };
    }

    static ViewKind kindOf(String code) {
        return switch (code) {
            case "al" -> ViewKind.ALBUM;
            case "ar" -> ViewKind.ARTIST;
            case "ge" -> ViewKind.GENRE;
            case "ti" -> ViewKind.TITLE;
            case "co" -> ViewKind.COMPOSER;
            default -> null;
        };
    }

    static String rootId(String directoryId, ViewKind kind) {
        return Views.CONTEXT_PREFIX + directoryId + "." + kindCode(kind);
    }

    static String title(ViewKind kind) {
        return switch (kind) {
            case ALBUM -> "[Albums]";
            case ARTIST -> "[Artists]";
            case GENRE -> "[Genres]";
            case TITLE -> "[Titles]";
            case COMPOSER -> "[Composers]";
        };
    }

    Tree build(ViewKind kind, List<EffectiveTrack> pool) {
        String rootId = rootId(directoryId, kind);
        ViewNode root = switch (kind) {
            case ALBUM -> albums(rootId, pool);
            case ARTIST -> artists(rootId, pool);
            case GENRE -> genres(rootId, pool);
            case TITLE -> titles(rootId, pool);
            case COMPOSER -> composers(rootId, pool);
        };
        return new Tree(root, Map.copyOf(index));
    }

    private ViewNode register(ViewNode n) {
        index.put(n.id(), n);
        return n;
    }

    // ---- Albums ---------------------------------------------------------------------------------------------

    private ViewNode albums(String rootId, List<EffectiveTrack> pool) {
        List<AlbumGroup> groups = albumGroups(pool);
        groups.sort(Comparator.comparing((AlbumGroup g) -> g.title, Normalizer.NATURAL)
                .thenComparing(g -> g.artist == null ? "" : g.artist, Normalizer.NATURAL));
        List<ViewNode> kids = new ArrayList<>();
        for (AlbumGroup g : groups) {
            kids.add(albumNode(rootId, rootId + "." + g.id, g));
        }
        return register(new ViewNode(rootId, null, ViewNode.Type.ROOT, title(ViewKind.ALBUM), null, null, kids,
                List.of(), null));
    }

    private ViewNode albumNode(String parentId, String id, AlbumGroup g) {
        List<Track> tracks = g.tracks.stream().map(EffectiveTrack::track).toList();
        return register(new ViewNode(id, parentId, ViewNode.Type.ALBUM, g.title, g.artist, g.year, List.of(),
                tracks, tracks.get(0)));
    }

    // ---- Artists --------------------------------------------------------------------------------------------

    private ViewNode artists(String rootId, List<EffectiveTrack> pool) {
        return people(rootId, pool, ViewKind.ARTIST, ViewBuilder::artistNames, UNKNOWN_ARTIST, "vr");
    }

    private ViewNode composers(String rootId, List<EffectiveTrack> pool) {
        return people(rootId, pool, ViewKind.COMPOSER, ViewBuilder::composerNames, UNKNOWN_COMPOSER, "vc");
    }

    /** Artists and Composers: people, then their albums plus "All tracks", then tracks. */
    private ViewNode people(String rootId, List<EffectiveTrack> pool, ViewKind kind,
            java.util.function.Function<EffectiveTrack, List<String>> names, String unknown, String idPrefix) {
        Map<String, Group> byArtist = new LinkedHashMap<>();
        for (EffectiveTrack et : pool) {
            for (String name : names.apply(et)) {
                Group g = byArtist.computeIfAbsent(Normalizer.normalize(name), k -> new Group());
                if (!name.isBlank()) {
                    g.variants.add(name);
                }
                g.tracks.add(et);
            }
        }
        List<Group> sorted = new ArrayList<>();
        byArtist.forEach((k, g) -> {
            g.key = k;
            sorted.add(g);
        });
        sorted.sort(Comparator.comparing((Group g) -> g.display(unknown), Normalizer.NATURAL));

        boolean bucketed = sorted.size() > bucketThreshold;
        Map<Character, List<ViewNode>> buckets = new TreeMap<>(BUCKET_ORDER);
        List<ViewNode> flat = new ArrayList<>();
        for (Group g : sorted) {
            String name = g.display(unknown);
            char letter = bucketLetter(name);
            String parent = bucketed ? rootId + "." + bucketSegment(letter) : rootId;
            ViewNode artist = artistNode(rootId, parent, name, g, idPrefix);
            if (bucketed) {
                buckets.computeIfAbsent(letter, k -> new ArrayList<>()).add(artist);
            } else {
                flat.add(artist);
            }
        }
        List<ViewNode> kids = bucketed ? bucketNodes(rootId, buckets) : flat;
        return register(new ViewNode(rootId, null, ViewNode.Type.ROOT, title(kind), null, null, kids,
                List.of(), null));
    }

    private ViewNode artistNode(String rootId, String parentId, String name, Group g, String idPrefix) {
        String id = rootId + "." + StableIds.id(idPrefix, g.key);
        List<AlbumGroup> albums = albumGroups(g.tracks);
        albums.sort(Comparator.comparing((AlbumGroup a) -> a.year, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(a -> a.title, Normalizer.NATURAL));
        List<ViewNode> kids = new ArrayList<>();
        List<Track> all = new ArrayList<>();
        for (AlbumGroup a : albums) {
            ViewNode n = albumNode(id, id + "." + a.id, a);
            kids.add(n);
            all.addAll(n.tracks());
        }
        kids.add(register(new ViewNode(id + ".all", id, ViewNode.Type.ALL_TRACKS, "All tracks", name, null,
                List.of(), all, all.get(0))));
        return register(new ViewNode(id, parentId, ViewNode.Type.ARTIST, name, null, null, kids, List.of(),
                all.get(0)));
    }

    // ---- Genres ---------------------------------------------------------------------------------------------

    private ViewNode genres(String rootId, List<EffectiveTrack> pool) {
        Map<String, Group> byGenre = new LinkedHashMap<>();
        for (EffectiveTrack et : pool) {
            List<String> names = new ArrayList<>(et.genres());
            if (names.isEmpty()) {
                names.add("");
            }
            for (String name : names) {
                Group g = byGenre.computeIfAbsent(Normalizer.normalize(name), k -> new Group());
                if (!name.isBlank()) {
                    g.variants.add(name);
                }
                g.tracks.add(et);
            }
        }
        List<Group> sorted = new ArrayList<>();
        byGenre.forEach((k, g) -> {
            g.key = k;
            sorted.add(g);
        });
        sorted.sort(Comparator.comparing((Group g) -> g.display(UNKNOWN_GENRE), Normalizer.NATURAL));
        List<ViewNode> kids = new ArrayList<>();
        for (Group g : sorted) {
            String id = rootId + "." + StableIds.id("vg", g.key);
            List<AlbumGroup> albums = albumGroups(g.tracks);
            albums.sort(Comparator.comparing((AlbumGroup a) -> a.title, Normalizer.NATURAL)
                    .thenComparing(a -> a.artist == null ? "" : a.artist, Normalizer.NATURAL));
            List<ViewNode> albumNodes = new ArrayList<>();
            for (AlbumGroup a : albums) {
                albumNodes.add(albumNode(id, id + "." + a.id, a));
            }
            kids.add(register(new ViewNode(id, rootId, ViewNode.Type.GENRE, g.display(UNKNOWN_GENRE), null, null,
                    albumNodes, List.of(), albumNodes.get(0).artTrack())));
        }
        return register(new ViewNode(rootId, null, ViewNode.Type.ROOT, title(ViewKind.GENRE), null, null, kids,
                List.of(), null));
    }

    // ---- Titles ---------------------------------------------------------------------------------------------

    private ViewNode titles(String rootId, List<EffectiveTrack> pool) {
        List<Track> tracks = new ArrayList<>(pool.stream().map(EffectiveTrack::track).toList());
        tracks.sort(Comparator.comparing(DisplayNames::trackTitle, Normalizer.NATURAL).thenComparing(Track::ref));
        if (tracks.size() <= bucketThreshold) {
            return register(new ViewNode(rootId, null, ViewNode.Type.ROOT, title(ViewKind.TITLE), null, null,
                    List.of(), tracks, tracks.isEmpty() ? null : tracks.get(0)));
        }
        Map<Character, List<Track>> byLetter = new TreeMap<>(BUCKET_ORDER);
        for (Track t : tracks) {
            byLetter.computeIfAbsent(bucketLetter(DisplayNames.trackTitle(t)), k -> new ArrayList<>()).add(t);
        }
        List<ViewNode> kids = new ArrayList<>();
        byLetter.forEach((letter, list) -> kids.add(register(new ViewNode(rootId + "." + bucketSegment(letter),
                rootId, ViewNode.Type.BUCKET, String.valueOf(letter), null, null, List.of(), list, list.get(0)))));
        return register(new ViewNode(rootId, null, ViewNode.Type.ROOT, title(ViewKind.TITLE), null, null, kids,
                List.of(), null));
    }

    // ---- Buckets --------------------------------------------------------------------------------------------

    /** A-Z first, '#' (everything else) last. */
    private static final Comparator<Character> BUCKET_ORDER = Comparator
            .comparing((Character c) -> c == '#').thenComparing(Comparator.naturalOrder());

    static char bucketLetter(String name) {
        String n = Normalizer.normalize(name);
        char c = n.isEmpty() ? '#' : n.charAt(0);
        return c >= 'a' && c <= 'z' ? Character.toUpperCase(c) : '#';
    }

    static String bucketSegment(char letter) {
        return "b" + (letter == '#' ? '0' : letter);
    }

    private List<ViewNode> bucketNodes(String rootId, Map<Character, List<ViewNode>> buckets) {
        List<ViewNode> out = new ArrayList<>();
        buckets.forEach((letter, kids) -> out.add(register(new ViewNode(rootId + "." + bucketSegment(letter),
                rootId, ViewNode.Type.BUCKET, String.valueOf(letter), null, null, kids, List.of(),
                kids.get(0).artTrack()))));
        return out;
    }

    // ---- Album grouping ---------------------------------------------------------------------------------------

    /** Counts of display variants of one normalized key. */
    private static final class Variants {
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        void add(String display) {
            counts.merge(display.strip(), 1, Integer::sum);
        }

        /** Most frequent variant; ties go to the first in natural order. */
        String best() {
            String best = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > bestCount || (e.getValue() == bestCount && Normalizer.compareNatural(e.getKey(), best) < 0)) {
                    best = e.getKey();
                    bestCount = e.getValue();
                }
            }
            return best;
        }

        boolean isEmpty() {
            return counts.isEmpty();
        }
    }

    /** Tracks sharing a normalized artist or genre key. */
    private static final class Group {
        String key = "";
        final Variants variants = new Variants();
        final List<EffectiveTrack> tracks = new ArrayList<>();

        String display(String unknown) {
            return variants.isEmpty() ? unknown : variants.best();
        }
    }

    private static final class AlbumGroup {
        String id;
        String title;
        String artist;
        Integer year;
        List<EffectiveTrack> tracks;
    }

    private static final Comparator<EffectiveTrack> ALBUM_ORDER = Comparator
            .comparing((EffectiveTrack e) -> e.discNo(), Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
            .thenComparing(e -> e.track().tags().trackNo(), Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
            .thenComparing(e -> e.track().fileName(), Normalizer.NATURAL);

    /**
     * Groups tracks into albums by (album artist, album title). The artist is appended to the title of albums
     * whose titles collide. Tracks without an album title are grouped by track artist as "Unknown Album".
     */
    static List<AlbumGroup> albumGroups(List<EffectiveTrack> pool) {
        record Acc(Variants titles, Variants artists, List<EffectiveTrack> tracks, String keyArtist, String keyTitle) {
        }
        Map<String, Acc> byKey = new LinkedHashMap<>();
        for (EffectiveTrack et : pool) {
            String title = et.album() == null || et.album().isBlank() ? null : et.album().strip();
            String artist = !et.albumArtists().isEmpty() ? String.join(" & ", et.albumArtists()) : null;
            if (artist == null && title == null && !et.artists().isEmpty()) {
                artist = String.join(" & ", et.artists());
            }
            String keyArtist = Normalizer.normalize(artist);
            String keyTitle = Normalizer.normalize(title);
            Acc acc = byKey.computeIfAbsent(keyArtist + "\0" + keyTitle,
                    k -> new Acc(new Variants(), new Variants(), new ArrayList<>(), keyArtist, keyTitle));
            if (title != null) {
                acc.titles.add(title);
            }
            if (artist != null) {
                acc.artists.add(artist);
            }
            acc.tracks.add(et);
        }
        Map<String, Integer> titleCounts = new HashMap<>();
        byKey.values().forEach(a -> titleCounts.merge(a.keyTitle, 1, Integer::sum));
        List<AlbumGroup> out = new ArrayList<>();
        for (Acc a : byKey.values()) {
            AlbumGroup g = new AlbumGroup();
            g.id = StableIds.id("va", a.keyArtist, a.keyTitle);
            String title = a.titles.isEmpty() ? UNKNOWN_ALBUM : a.titles.best();
            g.artist = a.artists.isEmpty() ? null : a.artists.best();
            g.title = titleCounts.get(a.keyTitle) > 1
                    ? title + " (" + (g.artist == null ? UNKNOWN_ARTIST : g.artist) + ")" : title;
            g.year = a.tracks.stream().map(EffectiveTrack::year).filter(y -> y != null && y > 0)
                    .min(Comparator.naturalOrder()).orElse(null);
            g.tracks = new ArrayList<>(a.tracks);
            g.tracks.sort(ALBUM_ORDER);
            out.add(g);
        }
        return out;
    }

    /** Artist names of a track for the Artists view: album artist(s), else track artist(s), else unknown. */
    private static List<String> artistNames(EffectiveTrack et) {
        List<String> names = !et.albumArtists().isEmpty() ? et.albumArtists() : et.artists();
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String n : names) {
            if (n != null && !n.isBlank() && seen.add(Normalizer.normalize(n))) {
                out.add(n.strip());
            }
        }
        return out.isEmpty() ? List.of(UNKNOWN_ARTIST_MARK) : out;
    }

    /** Composer names of a track for the Composers view, else the empty marker (Unknown Composer). */
    private static List<String> composerNames(EffectiveTrack et) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String n : et.composers()) {
            if (n != null && !n.isBlank() && seen.add(Normalizer.normalize(n))) {
                out.add(n.strip());
            }
        }
        return out.isEmpty() ? List.of(UNKNOWN_ARTIST_MARK) : out;
    }

    /** Marker for tracks without artist: normalizes to the empty key, displays as "Unknown Artist". */
    private static final String UNKNOWN_ARTIST_MARK = "";
}
