package org.kendar.dlna.views;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.kendar.dlna.catalog.CatalogNavigation;
import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.ViewKind;

/**
 * Albums / Artists / Genres / Titles views over a directory's subtree (plan section 8). Trees are computed
 * lazily per (directory, view), memoized, and discarded when the catalog snapshot is swapped. Also the
 * {@link ContainerNavigation} of the {@code v.<dirId>.<view>...} contexts.
 */
public final class Views implements ContainerNavigation {

    public static final String CONTEXT_PREFIX = "v.";
    /** Context prefix of the "Recently added" container: {@code n.<dirId>}. */
    public static final String RECENT_PREFIX = "n.";
    public static final String RECENT_TITLE = "[Recently added]";
    public static final int DEFAULT_BUCKET_THRESHOLD = 500;

    /** Memoized trees of one catalog snapshot. */
    private record Memo(Snapshot snapshot, ConcurrentMap<String, ViewBuilder.Tree> trees) {
    }

    private final Supplier<Snapshot> catalog;
    private final int bucketThreshold;
    private volatile Memo memo;

    public Views(Supplier<Snapshot> catalog) {
        this(catalog, DEFAULT_BUCKET_THRESHOLD);
    }

    /** @param bucketThreshold lists longer than this (Titles, Artists) are split into A-Z / # containers */
    public Views(Supplier<Snapshot> catalog, int bucketThreshold) {
        this.catalog = catalog;
        this.bucketThreshold = bucketThreshold;
        this.memo = new Memo(null, new ConcurrentHashMap<>());
    }

    /** The id of the view container below a directory. */
    public static String rootId(Folder folder, ViewKind kind) {
        return ViewBuilder.rootId(folder.id(), kind);
    }

    /** The id of the "Recently added" container below a directory. */
    public static String recentId(Folder folder) {
        return RECENT_PREFIX + folder.id();
    }

    /**
     * The "Recently added" container of a directory: its N most recently added tracks (subtree, newest first); empty
     * when the directory does not enable it.
     */
    public Optional<ViewNode> recentRoot(Snapshot snap, Folder folder) {
        int n = folder.settings().recent();
        if (n <= 0) {
            return Optional.empty();
        }
        ViewBuilder.Tree t = memoFor(snap).trees().computeIfAbsent(folder.id() + ".new", k -> {
            List<Track> newest = snap.subtreeTracks(folder.ref()).stream().sorted(NEWEST_FIRST).limit(n).toList();
            String id = recentId(folder);
            ViewNode root = new ViewNode(id, null, ViewNode.Type.RECENT, RECENT_TITLE, null, null, List.of(), newest,
                    newest.isEmpty() ? null : newest.get(0));
            return new ViewBuilder.Tree(root, java.util.Map.of(id, root));
        });
        return Optional.of(t.root());
    }

    private static final java.util.Comparator<Track> NEWEST_FIRST = java.util.Comparator
            .comparingLong(Track::addedAt).reversed()
            .thenComparing(java.util.Comparator.comparingLong(Track::mtime).reversed())
            .thenComparing(Track::ref);

    /** The view containers enabled for a directory, in settings order. */
    public List<ViewNode> roots(Snapshot snap, Folder folder) {
        return folder.settings().views().stream().map(k -> root(snap, folder, k)).flatMap(Optional::stream)
                .toList();
    }

    public Optional<ViewNode> root(Snapshot snap, Folder folder, ViewKind kind) {
        return Optional.of(tree(snap, folder, kind).root());
    }

    /** A node by context id (any level), or empty when unknown. */
    public Optional<ViewNode> node(Snapshot snap, String contextId) {
        if (contextId != null && contextId.startsWith(RECENT_PREFIX)) {
            return snap.folderById(contextId.substring(RECENT_PREFIX.length())).flatMap(f -> recentRoot(snap, f));
        }
        if (contextId == null || !contextId.startsWith(CONTEXT_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = contextId.split("\\.");
        if (parts.length < 3) {
            return Optional.empty();
        }
        ViewKind kind = ViewBuilder.kindOf(parts[2]);
        Optional<Folder> folder = snap.folderById(parts[1]);
        if (kind == null || folder.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tree(snap, folder.get(), kind).index().get(contextId));
    }

    @Override
    public boolean knows(String containerId) {
        return node(catalog.get(), containerId).isPresent();
    }

    @Override
    public Optional<Track> next(String containerId, String itemId) {
        return node(catalog.get(), containerId).flatMap(n -> CatalogNavigation.after(n.tracks(), itemId));
    }


    // ---- library-wide views over a set of roots (web API) ----

    /** Library-wide lists are never split into A-Z buckets. */
    private static final int NO_BUCKETS = Integer.MAX_VALUE;

    /**
     * A node of the library-wide view of {@code kind} over the given roots (grouping rules of the directory views,
     * independent of {@code settings.json} views): the view root for an empty {@code suffix}, else the node whose
     * id is the root id plus {@code "." + suffix}. Trees are memoized per (snapshot, roots set, kind). The tracks
     * of unlisted roots are never part of the tree.
     */
    public Optional<ViewNode> libraryNode(Snapshot snap, java.util.Set<String> roots, ViewKind kind, String suffix) {
        String scope = new java.util.TreeSet<>(roots).toString();
        String scopeId = "lib" + Integer.toHexString(scope.hashCode());
        ViewBuilder.Tree tree = memoFor(snap).trees().computeIfAbsent(scopeId + "|" + scope + "." + ViewBuilder.kindCode(kind),
                k -> {
                    List<EffectiveTrack> pool = new java.util.ArrayList<>();
                    for (Folder root : snap.roots()) {
                        if (!roots.contains(root.ref().root())) {
                            continue;
                        }
                        for (Track t : snap.subtreeTracks(root.ref())) {
                            pool.add(EffectiveTrack.of(t, snap.folder(t.parentRef()).map(Folder::settings)
                                    .orElse(DirectorySettings.DEFAULT)));
                        }
                    }
                    return new ViewBuilder(scopeId, NO_BUCKETS).build(kind, pool);
                });
        if (suffix == null || suffix.isEmpty()) {
            return Optional.of(tree.root());
        }
        return Optional.ofNullable(tree.index().get(tree.root().id() + "." + suffix));
    }
    private ViewBuilder.Tree tree(Snapshot snap, Folder folder, ViewKind kind) {
        Memo m = memoFor(snap);
        return m.trees().computeIfAbsent(folder.id() + "." + ViewBuilder.kindCode(kind), k -> {
            List<EffectiveTrack> pool = snap.subtreeTracks(folder.ref()).stream()
                    .map(t -> EffectiveTrack.of(t, snap.folder(t.parentRef()).map(Folder::settings)
                            .orElse(DirectorySettings.DEFAULT)))
                    .toList();
            return new ViewBuilder(folder.id(), bucketThreshold).build(kind, pool);
        });
    }

    private Memo memoFor(Snapshot snap) {
        Memo m = memo;
        if (m.snapshot() == snap) {
            return m;
        }
        synchronized (this) {
            if (memo.snapshot() != snap) {
                memo = new Memo(snap, new ConcurrentHashMap<>());
            }
            return memo;
        }
    }
}
