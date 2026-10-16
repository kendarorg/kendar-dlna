package org.kendar.dlna.libraryquery;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.kendar.dlna.artwork.Art;
import org.kendar.dlna.artwork.ArtSize;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.identity.Access;
import org.kendar.dlna.identity.User;
import org.kendar.dlna.search.Kind;
import org.kendar.dlna.search.Query;
import org.kendar.dlna.search.SearchEngine;
import org.kendar.dlna.search.SearchHit;
import org.kendar.dlna.search.SearchScope;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.views.ViewNode;
import org.kendar.dlna.views.Views;

/**
 * The read facade of the web player (plan section 6). <b>Every</b> result is confined to the caller's allowed roots
 * here and nowhere else: lists, groupings, search, recently added and random are computed over
 * {@link #rootsOf the caller's root set} only, and every explicit location goes through {@link #authorized}
 * ({@code forbidden_root} before the existence of the path is revealed). Results are JSON-ready maps carrying the
 * catalog snapshot version ({@code "snapshot"}).
 */
public final class LibraryQuery {

    public static final String BASE = "/api/v1/library";
    public static final int MAX_RANDOM = 1000;
    /** The id of the top node of a navigation in {@code /nav/{name}/{id}} (an empty segment cannot be addressed). */
    public static final String NAV_ROOT = "~";
    public static final int DEFAULT_RANDOM = 50;
    static final Set<String> DEFAULT_FORMATS = Set.of("mp3", "ogg", "flac", "wav", "opus");

    /** A rendered picture. */
    public record Image(byte[] data, String contentType, String etag, long lastModified) {
    }

    /** The three library-wide groupings. */
    private enum Grouping {
        ALBUMS(ViewKind.ALBUM, "albums"), ARTISTS(ViewKind.ARTIST, "artists"), GENRES(ViewKind.GENRE, "genres");

        final ViewKind kind;
        final String wire;

        Grouping(ViewKind kind, String wire) {
            this.kind = kind;
            this.wire = wire;
        }

        static Optional<Grouping> of(String wire) {
            for (Grouping g : values()) {
                if (g.wire.equals(wire)) {
                    return Optional.of(g);
                }
            }
            return Optional.empty();
        }
    }

    private record Memo(Snapshot snapshot, ConcurrentHashMap<String, Object> values) {
    }

    private final Supplier<Snapshot> catalog;
    private final Views views;
    private final ArtworkService artwork;
    private final SecureRandom seeds = new SecureRandom();
    private volatile Memo memo = new Memo(null, new ConcurrentHashMap<>());

    /** @param artwork may be null (no pictures are served, no {@code art} references are given for folders) */
    public LibraryQuery(Supplier<Snapshot> catalog, Views views, ArtworkService artwork) {
        this.catalog = catalog;
        this.views = views;
        this.artwork = artwork;
    }

    /** A facade with its own views and no artwork service. */
    public static LibraryQuery basic(Supplier<Snapshot> catalog) {
        return new LibraryQuery(catalog, new Views(catalog), null);
    }

    // ---- the single roots filter ----

    /** The library roots (present in the catalog) the caller may see; admins: all. */
    public Set<String> rootsOf(User user) {
        return rootsOf(user, catalog.get());
    }

    private static Set<String> rootsOf(User user, Snapshot snap) {
        Set<String> out = new LinkedHashSet<>();
        for (Folder r : snap.roots()) {
            if (user.mayAccess(r.ref().root())) {
                out.add(r.ref().root());
            }
        }
        return out;
    }

    /** Parses an explicit location and refuses it ({@code forbidden_root}) unless its root is allowed. */
    private static RootRef authorized(User user, String path) {
        RootRef ref;
        try {
            ref = RootRef.parse(path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        Access.requireRoot(user, ref);
        return ref;
    }

    private static String required(Map<String, String> q, String name) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("Missing query parameter: " + name);
        }
        return v;
    }

    // ---- memoization per (snapshot, roots set) ----

    @SuppressWarnings("unchecked")
    private <T> T memoized(Snapshot snap, String key, Supplier<T> compute) {
        Memo m = memo;
        if (m.snapshot() != snap) {
            synchronized (this) {
                if (memo.snapshot() != snap) {
                    memo = new Memo(snap, new ConcurrentHashMap<>());
                }
                m = memo;
            }
        }
        return (T) m.values().computeIfAbsent(key, k -> compute.get());
    }

    private static String scopeKey(Set<String> roots) {
        return new TreeSet<>(roots).toString();
    }

    // ---- JSON helpers ----

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> envelope(Snapshot snap) {
        return obj("snapshot", snap.updateId());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> pageOf(Map<String, Object> m, String key, int total, Paging p, List<?> items) {
        m.put("total", total);
        m.put("offset", p.offset());
        m.put("limit", p.limit());
        m.put(key, items);
        return m;
    }

    private String trackArt(Snapshot snap, Track t) {
        if (t == null) {
            return null;
        }
        boolean has = snap.folder(t.parentRef()).map(Folder::hasFront).orElse(false) || t.embeddedArt();
        return has ? BASE + "/artwork?kind=track&path=" + enc(t.ref().qualified()) : null;
    }

    private String folderArt(Snapshot snap, Folder f) {
        boolean has = artwork != null ? artwork.folderHasArt(snap, f) : f.hasFront();
        return has ? BASE + "/artwork?kind=folder&path=" + enc(f.ref().qualified()) : null;
    }

    private Map<String, Object> folderRef(Snapshot snap, Folder f) {
        return obj("type", "folder", "path", f.ref().qualified(), "name", f.ref().name(), "displayName",
                DisplayNames.folderTitle(f), "virtual", f.isVirtual(), "art", folderArt(snap, f));
    }

    private static Set<String> formats(Map<String, String> q) {
        String v = q.get("formats");
        if (v == null || v.isBlank()) {
            return DEFAULT_FORMATS;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : v.split(",")) {
            if (!s.isBlank()) {
                out.add(s.strip().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * A track: tags (the folder's album overlay applied to the album-level fields), duration, format, playability
     * ({@code native} when its format is among {@code formats} and not MIDI, else the MP3 transcode) and art ref.
     */
    private Map<String, Object> trackJson(Snapshot snap, Track t, Set<String> formats) {
        EffectiveTrack e = EffectiveTrack.of(t, snap.folder(t.parentRef())
                .map(Folder::settings).orElse(org.kendar.dlna.catalog.DirectorySettings.DEFAULT));
        String format = lower(t.format());
        boolean nativeOk = !t.format().rendered() && !t.isSegment() && formats.contains(format);
        return obj("path", t.ref().qualified(), "name", t.fileName(), "title", DisplayNames.trackTitle(t),
                "artist", t.tags().artist(), "album", e.album(), "albumArtist", e.albumArtist(), "genre", e.genre(),
                "composer", t.tags().composer(), "transcriber", t.tags().transcriber(), "year", e.year(), "trackNo", t.tags().trackNo(), "discNo", e.discNo(),
                "discTotal", e.discTotal(), "format", format, "durationMillis", t.durationMillis(),
                "bitrateKbps", t.bitrateKbps(), "sampleRate", t.sampleRate(), "channels", t.channels(),
                "size", t.size(), "addedAt", t.addedAt(), "playability", nativeOk ? "native" : "transcoded",
                "streamFormat", nativeOk ? format : "mp3", "art", trackArt(snap, t),
                "segment", segmentJson(t));
    }

    /** The cue sheet / chapter range of a virtual track, null for a normal track. */
    private static Map<String, Object> segmentJson(Track t) {
        if (t.segment() == null) {
            return null;
        }
        return obj("source", t.fileRef().qualified(), "index", t.segment().index(), "origin", t.segment().origin(),
                "startMillis", t.segment().startMillis(), "endMillis", t.segment().endMillis());
    }

    private List<Object> trackJson(Snapshot snap, List<Track> tracks, Set<String> formats) {
        List<Object> out = new ArrayList<>(tracks.size());
        for (Track t : tracks) {
            out.add(trackJson(snap, t, formats));
        }
        return out;
    }

    // ---- roots, folders ----

    public Map<String, Object> roots(User user) {
        Snapshot snap = catalog.get();
        List<Object> roots = new ArrayList<>();
        for (Folder r : snap.roots()) {
            if (user.mayAccess(r.ref().root())) {
                Map<String, Object> m = folderRef(snap, r);
                m.remove("type");
                roots.add(m);
            }
        }
        Map<String, Object> m = envelope(snap);
        m.put("roots", roots);
        return m;
    }

    /** Params: {@code path}, {@code offset}, {@code limit}, {@code formats}. */
    public Map<String, Object> folder(User user, Map<String, String> q) {
        RootRef ref = authorized(user, required(q, "path"));
        Snapshot snap = catalog.get();
        Folder f = snap.folder(ref).orElseThrow(() -> QueryException.notFound("Unknown directory: " + ref));
        Paging p = Paging.of(q);
        Set<String> formats = formats(q);
        List<Child> kids = snap.children(ref);
        int folders = 0;
        for (Child c : kids) {
            if (c instanceof Child.FolderChild) {
                folders++;
            }
        }
        List<Object> items = new ArrayList<>();
        for (Child c : p.slice(kids)) {
            switch (c) {
                case Child.FolderChild fc -> items.add(folderRef(snap, fc.folder()));
                case Child.TrackChild tc -> items.add(obj("type", "track", "path", tc.track().ref().qualified(),
                        "track", trackJson(snap, tc.track(), formats)));
            }
        }
        AlbumInfo a = f.settings().album();
        Map<String, Object> m = envelope(snap);
        m.put("path", ref.qualified());
        m.put("name", ref.name());
        m.put("displayName", DisplayNames.folderTitle(f));
        m.put("virtual", f.isVirtual());
        m.put("isRoot", f.isRoot());
        m.put("parent", f.isRoot() ? null : ref.parent().qualified());
        m.put("sort", lower(f.settings().sort()));
        m.put("album", obj("title", a.title(), "albumArtist", a.albumArtist(), "year", a.year(), "genre", a.genre(),
                "discNumber", a.discNumber(), "discTotal", a.discTotal()));
        m.put("random", f.settings().random());
        m.put("recent", f.settings().recent());
        m.put("views", f.settings().views().stream().map(LibraryQuery::lower).toList());
        m.put("art", folderArt(snap, f));
        m.put("trackCount", kids.size() - folders);
        m.put("folderCount", folders);
        return pageOf(m, "children", kids.size(), p, items);
    }

    // ---- library-wide albums / artists / genres / songs ----

    /** {@code GET /albums|artists|genres}: params {@code offset}, {@code limit}. */
    public Map<String, Object> groups(User user, String view, Map<String, String> q) {
        Grouping g = Grouping.of(view).orElseThrow(() -> QueryException.notFound("Unknown view: " + view));
        Snapshot snap = catalog.get();
        ViewNode root = views.libraryNode(snap, rootsOf(user, snap), g.kind, "").orElseThrow();
        Paging p = Paging.of(q);
        List<Object> items = new ArrayList<>();
        for (ViewNode n : p.slice(root.children())) {
            items.add(groupItem(snap, g, root, n));
        }
        return pageOf(envelope(snap), "items", root.children().size(), p, items);
    }

    /**
     * {@code GET /albums|artists|genres/{id}}: an album/"all tracks" node lists its tracks, an artist or genre its
     * albums. With {@code tracksOnly} ({@code .../{id}/tracks}) the tracks of any node.
     */
    public Map<String, Object> group(User user, String view, String id, boolean tracksOnly, Map<String, String> q) {
        Grouping g = Grouping.of(view).orElseThrow(() -> QueryException.notFound("Unknown view: " + view));
        Snapshot snap = catalog.get();
        Set<String> roots = rootsOf(user, snap);
        ViewNode root = views.libraryNode(snap, roots, g.kind, "").orElseThrow();
        ViewNode n = views.libraryNode(snap, roots, g.kind, id)
                .orElseThrow(() -> QueryException.notFound("Unknown " + view + " entry: " + id));
        Paging p = Paging.of(q);
        Set<String> formats = formats(q);
        Map<String, Object> m = envelope(snap);
        m.put("item", groupItem(snap, g, root, n));
        boolean hasTracks = n.type() == ViewNode.Type.ALBUM || n.type() == ViewNode.Type.ALL_TRACKS;
        if (tracksOnly || hasTracks) {
            List<Track> all = tracksOf(n);
            return pageOf(m, "tracks", all.size(), p, trackJson(snap, p.slice(all), formats));
        }
        List<ViewNode> albums = n.children().stream().filter(c -> c.type() == ViewNode.Type.ALBUM).toList();
        n.children().stream().filter(c -> c.type() == ViewNode.Type.ALL_TRACKS).findFirst()
                .ifPresent(all -> m.put("allTracks", groupItem(snap, g, root, all)));
        List<Object> items = new ArrayList<>();
        for (ViewNode c : p.slice(albums)) {
            items.add(groupItem(snap, g, root, c));
        }
        return pageOf(m, "albums", albums.size(), p, items);
    }

    /** {@code GET /songs}: all tracks of the caller's roots, by title. */
    public Map<String, Object> songs(User user, Map<String, String> q) {
        Snapshot snap = catalog.get();
        ViewNode root = views.libraryNode(snap, rootsOf(user, snap), ViewKind.TITLE, "").orElseThrow();
        Paging p = Paging.of(q);
        return pageOf(envelope(snap), "items", root.tracks().size(), p,
                trackJson(snap, p.slice(root.tracks()), formats(q)));
    }

    private static List<Track> tracksOf(ViewNode n) {
        return switch (n.type()) {
            case ARTIST -> n.children().stream().filter(c -> c.type() == ViewNode.Type.ALL_TRACKS)
                    .flatMap(c -> c.tracks().stream()).toList();
            case GENRE -> n.children().stream().flatMap(c -> c.tracks().stream()).toList();
            default -> n.tracks();
        };
    }

    private Map<String, Object> groupItem(Snapshot snap, Grouping g, ViewNode root, ViewNode n) {
        String id = n.id().equals(root.id()) ? "" : n.id().substring(root.id().length() + 1);
        List<Track> tracks = tracksOf(n);
        long duration = 0;
        for (Track t : tracks) {
            duration += t.durationMillis();
        }
        Map<String, Object> m = obj("id", id, "view", g.wire, "type", lower(n.type()), "title", n.title(), "artist",
                n.artist(), "year", n.year(), "trackCount", tracks.size(), "durationMillis", duration);
        if (n.type() == ViewNode.Type.ARTIST || n.type() == ViewNode.Type.GENRE) {
            m.put("albumCount", n.children().stream().filter(c -> c.type() == ViewNode.Type.ALBUM).count());
        }
        m.put("art", trackArt(snap, n.artTrack()));
        return m;
    }

    // ---- configured navigations (whole library of the caller) ----

    /** {@code GET /navigations}: the configured navigations, in order (the web app builds its pages from this). */
    public Map<String, Object> navigations() {
        List<Object> items = new ArrayList<>();
        for (org.kendar.dlna.catalog.Navigation n : views.navigations().state().navigations()) {
            items.add(obj("name", n.name(), "key", n.key(), "index", n.index()));
        }
        return obj("items", items, "version", views.navigations().state().version());
    }

    /**
     * {@code GET /nav/{name}[?node=<id>]}: one node of a navigation over the caller's roots. {@code node}
     * is the id returned by the parent listing (empty = the navigation itself). The answer has the node, its
     * ancestors ({@code path}) and either its child containers ({@code groups}) or its tracks ({@code tracks}),
     * paged with {@code offset} / {@code limit}.
     */
    public Map<String, Object> navigation(User user, String name, Map<String, String> q) {
        return navigation(user, name, q.getOrDefault("node", ""), false, q);
    }

    /** Every track below a node, in tree order, without the "All tracks" duplicates. */
    private static void allTracks(ViewNode n, List<Track> out) {
        out.addAll(n.tracks());
        for (ViewNode c : n.children()) {
            if (c.type() != ViewNode.Type.ALL_TRACKS) {
                allTracks(c, out);
            }
        }
    }

    /**
     * {@code GET /nav/{name}[/{id}[/tracks]]}: like {@link #navigation(User, String, Map)}; with {@code tracksOnly}
     * the tracks of the whole subtree of the node (to play it).
     */
    public Map<String, Object> navigation(User user, String nodeName, String nodeId, boolean tracksOnly,
            Map<String, String> q) {
        String name = nodeName;
        String id = NAV_ROOT.equals(nodeId) ? "" : nodeId;
        org.kendar.dlna.catalog.Navigation nav = views.navigations().state().byKey(name)
                .or(() -> views.navigations().state().byName(name))
                .orElseThrow(() -> QueryException.notFound("Unknown navigation: " + name));
        Snapshot snap = catalog.get();
        Set<String> roots = rootsOf(user, snap);
        ViewNode root = views.libraryNavigation(snap, roots, nav, "").orElseThrow();
        ViewNode n = views.libraryNavigation(snap, roots, nav, id)
                .orElseThrow(() -> QueryException.notFound("Unknown " + name + " entry: " + id));
        Paging p = Paging.of(q);
        Map<String, Object> m = envelope(snap);
        m.put("navigation", nav.name());
        m.put("item", navItem(snap, nav, root, n));
        m.put("node", m.get("item"));
        List<Object> path = new ArrayList<>();
        for (String pid = n.parentId(); pid != null; ) {
            ViewNode parent = views.libraryNavigation(snap, roots, nav,
                    pid.equals(root.id()) ? "" : pid.substring(root.id().length() + 1)).orElse(null);
            if (parent == null) {
                break;
            }
            path.add(0, navItem(snap, nav, root, parent));
            pid = parent.parentId();
        }
        m.put("path", path);
        if (tracksOnly) {
            List<Track> all = new ArrayList<>();
            allTracks(n, all);
            return pageOf(m, "tracks", all.size(), p, trackJson(snap, p.slice(all), formats(q)));
        }
        if (!n.children().isEmpty()) {
            List<Object> groups = new ArrayList<>();
            for (ViewNode c : p.slice(n.children())) {
                groups.add(navItem(snap, nav, root, c));
            }
            return pageOf(m, "groups", n.children().size(), p, groups);
        }
        return pageOf(m, "tracks", n.tracks().size(), p, trackJson(snap, p.slice(n.tracks()), formats(q)));
    }

    private Map<String, Object> navItem(Snapshot snap, org.kendar.dlna.catalog.Navigation nav, ViewNode root,
            ViewNode n) {
        String id = n.id().equals(root.id()) ? NAV_ROOT : n.id().substring(root.id().length() + 1);
        int count = countTracks(n);
        return obj("id", id, "view", "nav/" + nav.key(), "type", lower(n.type()), "title", n.title(), "artist",
                n.artist(), "year", n.year(), "trackCount", count, "childCount", n.childCount(),
                "art", trackArt(snap, n.artTrack()));
    }
    private static int countTracks(ViewNode n) {
        if (n.type() == ViewNode.Type.ALL_TRACKS) {
            return n.tracks().size();
        }
        int total = n.tracks().size();
        for (ViewNode c : n.children()) {
            if (c.type() != ViewNode.Type.ALL_TRACKS) {
                total += countTracks(c);
            }
        }
        return total;
    }

    // ---- tracks ----

    /** Params: {@code path}, {@code formats}. */
    public Map<String, Object> track(User user, Map<String, String> q) {
        RootRef ref = authorized(user, required(q, "path"));
        Snapshot snap = catalog.get();
        Track t = snap.track(ref).orElseThrow(() -> QueryException.notFound("Unknown track: " + ref));
        Map<String, Object> m = envelope(snap);
        m.put("track", trackJson(snap, t, formats(q)));
        return m;
    }

    // ---- search ----

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String w : Normalizer.normalize(text).split(" ")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    private static boolean matchesAll(String haystack, List<String> words) {
        String h = Normalizer.normalize(haystack);
        for (String w : words) {
            if (!h.contains(w)) {
                return false;
            }
        }
        return true;
    }

    private static boolean under(Track t, RootRef scope) {
        return scope == null || (t.ref().root().equals(scope.root())
                && (scope.isRoot() || t.ref().rel().startsWith(scope.rel() + "/")));
    }

    private List<ViewNode> matchingGroups(Snapshot snap, Set<String> roots, ViewKind kind, List<String> words,
            RootRef scope) {
        ViewNode root = views.libraryNode(snap, roots, kind, "").orElseThrow();
        List<ViewNode> out = new ArrayList<>();
        for (ViewNode n : root.children()) {
            String text = n.title() + (n.artist() == null ? "" : " " + n.artist());
            if (matchesAll(text, words) && (scope == null || tracksOf(n).stream().anyMatch(t -> under(t, scope)))) {
                out.add(n);
            }
        }
        return out;
    }

    private List<SearchHit> hits(Snapshot snap, Set<String> roots, RootRef scope, String text, Kind kind) {
        SearchScope ss = scope == null ? SearchScope.ofRoots(roots) : SearchScope.below(scope, roots);
        Query query = new Query.And(List.of(Query.text(text), new Query.Kinds(EnumSet.of(kind))));
        return SearchEngine.search(snap, ss, query);
    }

    /**
     * {@code GET /search}: params {@code q} (required), {@code kind} ({@code all} = every group, first
     * {@code limit} of each, default 10; or {@code track|album|artist|folder} with {@code offset}/{@code limit}),
     * {@code scope} (a directory path). Groups: {@code tracks}, {@code albums}, {@code artists}, {@code folders}.
     */
    public Map<String, Object> search(User user, Map<String, String> q) {
        String text = q.getOrDefault("q", "");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Missing query parameter: q");
        }
        String kind = q.getOrDefault("kind", "all");
        if (!List.of("all", "track", "album", "artist", "folder").contains(kind)) {
            throw new IllegalArgumentException("kind must be all, track, album, artist or folder");
        }
        RootRef scope = q.get("scope") == null || q.get("scope").isBlank() ? null : authorized(user, q.get("scope"));
        Snapshot snap = catalog.get();
        Set<String> roots = rootsOf(user, snap);
        boolean all = kind.equals("all");
        Paging p = all ? new Paging(0, Math.min(Paging.of(q, 10).limit(), 50)) : Paging.of(q);
        Set<String> formats = formats(q);
        List<String> words = words(text);
        Map<String, Object> m = envelope(snap);
        m.put("q", text);
        if (all || kind.equals("track")) {
            List<SearchHit> hits = hits(snap, roots, scope, text, Kind.TRACK);
            List<Object> items = new ArrayList<>();
            for (SearchHit h : p.slice(hits)) {
                items.add(trackJson(snap, ((SearchHit.TrackHit) h).track(), formats));
            }
            m.put("tracks", pageOf(obj(), "items", hits.size(), p, items));
        }
        if (all || kind.equals("album")) {
            m.put("albums", groupPage(snap, roots, Grouping.ALBUMS, matchingGroups(snap, roots, ViewKind.ALBUM, words, scope),
                    p));
        }
        if (all || kind.equals("artist")) {
            m.put("artists", groupPage(snap, roots, Grouping.ARTISTS,
                    matchingGroups(snap, roots, ViewKind.ARTIST, words, scope), p));
        }
        if (all || kind.equals("folder")) {
            List<SearchHit> hits = hits(snap, roots, scope, text, Kind.FOLDER);
            List<Object> items = new ArrayList<>();
            for (SearchHit h : p.slice(hits)) {
                items.add(folderRef(snap, ((SearchHit.FolderHit) h).folder()));
            }
            m.put("folders", pageOf(obj(), "items", hits.size(), p, items));
        }
        return m;
    }

    private Map<String, Object> groupPage(Snapshot snap, Set<String> roots, Grouping g, List<ViewNode> nodes,
            Paging p) {
        ViewNode root = views.libraryNode(snap, roots, g.kind, "").orElseThrow();
        List<Object> items = new ArrayList<>();
        for (ViewNode n : p.slice(nodes)) {
            items.add(groupItem(snap, g, root, n));
        }
        return pageOf(obj(), "items", nodes.size(), p, items);
    }

    /**
     * {@code GET /search/suggest}: typeahead. Params {@code q}, {@code limit} (default 8, max 20). Artists, albums,
     * tracks and folders, prefix matches first. A blank {@code q} gives no suggestions.
     */
    public Map<String, Object> suggest(User user, Map<String, String> q) {
        Snapshot snap = catalog.get();
        Set<String> roots = rootsOf(user, snap);
        String text = q.getOrDefault("q", "");
        int limit = Math.max(1, Math.min(Paging.of(q, 8).limit(), 20));
        List<Object> out = new ArrayList<>();
        List<String> words = words(text);
        if (!words.isEmpty()) {
            String prefix = Normalizer.normalize(text);
            Comparator<ViewNode> prefixFirst = Comparator
                    .comparing((ViewNode n) -> Normalizer.normalize(n.title()).startsWith(prefix) ? 0 : 1);
            for (Grouping g : List.of(Grouping.ARTISTS, Grouping.ALBUMS)) {
                List<ViewNode> nodes = new ArrayList<>(matchingGroups(snap, roots, g.kind, words, null));
                nodes.sort(prefixFirst);
                ViewNode root = views.libraryNode(snap, roots, g.kind, "").orElseThrow();
                for (ViewNode n : nodes) {
                    if (out.size() >= limit) {
                        break;
                    }
                    out.add(obj("type", g == Grouping.ARTISTS ? "artist" : "album", "label", n.title(), "detail",
                            n.artist(), "view", g.wire, "id", n.id().substring(root.id().length() + 1), "art",
                            trackArt(snap, n.artTrack())));
                }
            }
            for (SearchHit h : hits(snap, roots, null, text, Kind.TRACK)) {
                if (out.size() >= limit) {
                    break;
                }
                Track t = ((SearchHit.TrackHit) h).track();
                out.add(obj("type", "track", "label", DisplayNames.trackTitle(t), "detail", t.tags().artist(), "path",
                        t.ref().qualified(), "art", trackArt(snap, t)));
            }
            for (SearchHit h : hits(snap, roots, null, text, Kind.FOLDER)) {
                if (out.size() >= limit) {
                    break;
                }
                Folder f = ((SearchHit.FolderHit) h).folder();
                out.add(obj("type", "folder", "label", DisplayNames.folderTitle(f), "detail", null, "path",
                        f.ref().qualified(), "art", folderArt(snap, f)));
            }
        }
        Map<String, Object> m = envelope(snap);
        m.put("q", text);
        m.put("suggestions", out);
        return m;
    }

    // ---- recently added, random ----

    /** {@code GET /recent}: newest first by first-seen timestamp. Params {@code offset}, {@code limit}, {@code since} (epoch ms). */
    public Map<String, Object> recent(User user, Map<String, String> q) {
        Snapshot snap = catalog.get();
        Set<String> roots = rootsOf(user, snap);
        List<Track> sorted = memoized(snap, "recent|" + scopeKey(roots), () -> {
            List<Track> l = new ArrayList<>();
            for (Track t : snap.visibleTracks()) {
                if (roots.contains(t.ref().root())) {
                    l.add(t);
                }
            }
            l.sort(Comparator.comparingLong(Track::addedAt).reversed()
                    .thenComparing(Comparator.comparingLong(Track::mtime).reversed()).thenComparing(Track::ref));
            return List.copyOf(l);
        });
        List<Track> list = sorted;
        if (q.get("since") != null && !q.get("since").isBlank()) {
            long since = Long.parseLong(q.get("since").strip());
            list = sorted.stream().takeWhile(t -> t.addedAt() >= since).toList();
        }
        Paging p = Paging.of(q);
        return pageOf(envelope(snap), "items", list.size(), p, trackJson(snap, p.slice(list), formats(q)));
    }

    /**
     * {@code GET /random}: seeded random tracks. Params {@code path} (optional directory: its subtree, else all of
     * the caller's roots), {@code count} (default: the directory's Random N, else 50; max 1000), {@code seed}
     * (default: a new one, returned), {@code offset}/{@code limit} to page through the picked tracks. The same seed
     * gives the same tracks again for the same user and catalog.
     */
    public Map<String, Object> random(User user, Map<String, String> q) {
        Snapshot snap = catalog.get();
        Set<String> roots = rootsOf(user, snap);
        RootRef folder = null;
        int dflt = DEFAULT_RANDOM;
        if (q.get("path") != null && !q.get("path").isBlank()) {
            folder = authorized(user, q.get("path"));
            Folder f = snap.folder(folder)
                    .orElseThrow(() -> QueryException.notFound("Unknown directory: " + q.get("path")));
            dflt = f.settings().random() > 0 ? f.settings().random() : DEFAULT_RANDOM;
        }
        int count = q.get("count") == null || q.get("count").isBlank() ? dflt : Integer.parseInt(q.get("count").strip());
        if (count < 1 || count > MAX_RANDOM) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_RANDOM);
        }
        long seed = q.get("seed") == null || q.get("seed").isBlank() ? seeds.nextLong()
                : Long.parseLong(q.get("seed").strip());
        List<Track> picked = org.kendar.dlna.shuffle.SimilarTitles.distinct(
                Shuffle.pick(snap, roots, folder, user.id(), seed), count);
        if (picked.size() > count) {
            picked = picked.subList(0, count);
        }
        Paging p = Paging.of(q, count);
        Map<String, Object> m = envelope(snap);
        m.put("seed", seed);
        m.put("count", count);
        return pageOf(m, "items", picked.size(), p, trackJson(snap, p.slice(picked), formats(q)));
    }

    // ---- artwork ----

    /**
     * {@code GET /artwork}: params {@code kind} ({@code track|folder}), {@code path}, {@code size}
     * ({@code small} 160 px, {@code medium} 300 px (default), {@code large} 800 px; longest edge, never upscaled).
     */
    public Image artwork(User user, Map<String, String> q) {
        String kind = required(q, "kind");
        RootRef ref = authorized(user, required(q, "path"));
        ArtSize size = ArtSize.parse(q.getOrDefault("size", "medium"))
                .orElseThrow(() -> new IllegalArgumentException("size must be small, medium or large"));
        if (!kind.equals("track") && !kind.equals("folder")) {
            throw new IllegalArgumentException("kind must be track or folder");
        }
        Snapshot snap = catalog.get();
        Optional<Art> art = Optional.empty();
        if (artwork != null) {
            art = kind.equals("track") ? snap.track(ref).flatMap(t -> artwork.trackArt(t.id(), size))
                    : snap.folder(ref).flatMap(f -> artwork.folderArt(f.id(), size));
        }
        Art a = art.orElseThrow(() -> QueryException.notFound("No artwork for " + ref));
        return new Image(a.data(), a.contentType(), a.etag(), a.lastModified());
    }

}
