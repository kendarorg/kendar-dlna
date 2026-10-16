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
import org.kendar.dlna.catalog.Navigation;
import org.kendar.dlna.catalog.NavigationRegistry;
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
    private record Memo(Snapshot snapshot, long navVersion, ConcurrentMap<String, ViewBuilder.Tree> trees,
            ConcurrentMap<String, FolderRanges.Layout> layouts) {
    }

    private final Supplier<Snapshot> catalog;
    private final int bucketThreshold;
    private volatile Memo memo;
    private volatile NavigationRegistry navigations = NavigationRegistry.empty();

    /** The configured navigations this instance builds trees for (replaceable at run time). */
    public Views withNavigations(NavigationRegistry registry) {
        this.navigations = registry;
        return this;
    }

    public NavigationRegistry navigations() {
        return navigations;
    }

    public Views(Supplier<Snapshot> catalog) {
        this(catalog, DEFAULT_BUCKET_THRESHOLD);
    }

    /** @param bucketThreshold lists longer than this (Titles, Artists) are split into A-Z / # containers */
    public Views(Supplier<Snapshot> catalog, int bucketThreshold) {
        this.catalog = catalog;
        this.bucketThreshold = bucketThreshold;
        this.memo = new Memo(null, 0, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    /** Global defaults of the letter ranges (a directory's own, inherited values win). */
    public record RangeDefaults(int subitems, int ranges) {
    }

    private volatile RangeDefaults rangeDefaults = new RangeDefaults(50, 10);

    /** Sets the global {@code subitems} / {@code ranges} defaults (config {@code views.subitems / views.ranges}). */
    public Views withRangeDefaults(int subitems, int ranges) {
        this.rangeDefaults = new RangeDefaults(subitems, ranges);
        return this;
    }

    public RangeDefaults rangeDefaults() {
        return rangeDefaults;
    }

    /** The letter-range layout of a directory's real children (memoized per catalog snapshot). */
    public FolderRanges.Layout folderLayout(Snapshot snap, Folder folder) {
        RangeDefaults d = rangeDefaults;
        return memoFor(snap).layouts().computeIfAbsent(folder.id(),
                k -> FolderRanges.layout(snap, folder, d.subitems(), d.ranges()));
    }

    /** A range container by its {@code g.<dirId>.<...>} context id. */
    public Optional<FolderRanges.Range> rangeNode(Snapshot snap, String contextId) {
        String dirId = FolderRanges.directoryOf(contextId);
        if (dirId == null) {
            return Optional.empty();
        }
        return snap.folderById(dirId).map(f -> folderLayout(snap, f).index().get(contextId));
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
        if (folder.isEmpty()) {
            return Optional.empty();
        }
        if (kind == null) {
            return navigations.state().byKey(parts[2])
                    .map(n -> navigationTree(snap, folder.get(), n).index().get(contextId));
        }
        return Optional.ofNullable(tree(snap, folder.get(), kind).index().get(contextId));
    }

    // ---- configured navigations ----

    /**
     * The navigations a directory lists: its own (or inherited) {@code navigations} setting, matched by name;
     * unset everywhere, a library root lists every configured navigation and other directories none.
     */
    public List<Navigation> navigationsOf(Snapshot snap, Folder folder) {
        if (folder.isVirtual()) {
            return List.of();
        }
        NavigationRegistry.State st = navigations.state();
        List<String> names = snap.effectiveSettings(folder.ref()).navigations();
        if (names == null) {
            return folder.isRoot() ? st.navigations() : List.of();
        }
        return names.stream().map(st::byName).flatMap(Optional::stream).distinct().toList();
    }

    /** The navigation a root container {@code v.<dirId>.<navKey>} belongs to; empty for any other node. */
    public Optional<Navigation> navigationOf(Snapshot snap, ViewNode node) {
        if (node.type() != ViewNode.Type.ROOT || !node.id().startsWith(CONTEXT_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = node.id().split("\\.");
        return parts.length == 3 ? navigations.state().byKey(parts[2]) : Optional.empty();
    }

    /** The navigation containers of a directory (those that have something to show), in settings order. */
    public List<ViewNode> navigationRoots(Snapshot snap, Folder folder) {
        return navigationsOf(snap, folder).stream().map(n -> navigationTree(snap, folder, n).root())
                .filter(r -> r.childCount() > 0).toList();
    }

    /** The id of a navigation's container below a directory. */
    public static String navigationRootId(Folder folder, Navigation nav) {
        return NavBuilder.rootId(folder.id(), nav);
    }

    private ViewBuilder.Tree navigationTree(Snapshot snap, Folder folder, Navigation nav) {
        return memoFor(snap).trees().computeIfAbsent(folder.id() + "." + nav.key(), k -> {
            List<EffectiveTrack> pool = snap.subtreeTracks(folder.ref()).stream()
                    .map(t -> EffectiveTrack.of(t, snap.folder(t.parentRef()).map(Folder::settings)
                            .orElse(DirectorySettings.DEFAULT)))
                    .toList();
            DirectorySettings eff = snap.effectiveSettings(folder.ref());
            RangeDefaults d = rangeDefaults;
            return new NavBuilder(folder.id(), nav, eff.subitems() != null ? eff.subitems() : d.subitems(),
                    eff.ranges() != null ? eff.ranges() : d.ranges()).build(pool);
        });
    }

    /** A node of a navigation over the given roots (the whole library of a user), for the web API. */
    public Optional<ViewNode> libraryNavigation(Snapshot snap, java.util.Set<String> roots, Navigation nav,
            String suffix) {
        String scope = new java.util.TreeSet<>(roots).toString();
        String scopeId = "lib" + Integer.toHexString(scope.hashCode());
        ViewBuilder.Tree tree = memoFor(snap).trees().computeIfAbsent(scopeId + "|" + scope + "." + nav.key(), k -> {
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
            RangeDefaults d = rangeDefaults;
            return new NavBuilder(scopeId, nav, d.subitems(), d.ranges()).build(pool);
        });
        if (suffix == null || suffix.isEmpty()) {
            return Optional.of(tree.root());
        }
        return Optional.ofNullable(tree.index().get(tree.root().id() + "." + suffix));
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
            Integer own = snap.effectiveSettings(folder.ref()).bucketThreshold();
            return new ViewBuilder(folder.id(), own != null ? own : bucketThreshold).build(kind, pool);
        });
    }

    private Memo memoFor(Snapshot snap) {
        Memo m = memo;
        long version = navigations.state().version();
        if (m.snapshot() == snap && m.navVersion() == version) {
            return m;
        }
        synchronized (this) {
            if (memo.snapshot() != snap || memo.navVersion() != version) {
                memo = new Memo(snap, version, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
            }
            return memo;
        }
    }
}
