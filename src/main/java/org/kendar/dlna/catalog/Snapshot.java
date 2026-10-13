package org.kendar.dlna.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.kendar.dlna.shared.RootRef;

/**
 * Immutable in-memory view of the whole library: folders, tracks, unsupported files, lookup maps and the sorted
 * children of every directory. Built once, then swapped atomically through {@link SnapshotHolder}.
 */
public final class Snapshot {

    private static final Snapshot EMPTY = new Snapshot(1, Instant.EPOCH, List.of(), List.of(), List.of());

    private final long updateId;
    private final Instant scannedAt;
    private final List<Folder> roots;
    private final List<Track> tracks;
    private final List<UnsupportedFile> unsupported;
    private final Map<RootRef, Folder> folders;
    private final Map<RootRef, Track> trackByRef;
    private final Map<String, Folder> folderById;
    private final Map<String, Track> trackById;
    private final Map<RootRef, List<Child>> children;
    private final Map<RootRef, List<Child>> allChildren;
    private final List<Track> visibleTracks;
    private final Map<RootRef, DirectorySettings> effective;
    private final Map<RootRef, List<String>> missingOrderEntries;
    private final List<ResolvedPlaylist> playlists;
    private final Map<String, ResolvedPlaylist> playlistById;
    private final Map<String, Long> containerUpdateIds;

    /**
     * @param updateId  monotonic version (becomes the DLNA SystemUpdateID)
     * @param folders   all directories, root directories in display order
     * @throws IllegalArgumentException on duplicate refs, orphan entries or id collisions
     */
    public Snapshot(long updateId, Instant scannedAt, List<Folder> folders, List<Track> tracks,
            List<UnsupportedFile> unsupported) {
        this(updateId, scannedAt, folders, tracks, unsupported, List.of());
    }

    /**
     * @param playlists raw playlists; matched against the tracks and unsupported files, in the given order
     */
    public Snapshot(long updateId, Instant scannedAt, List<Folder> folders, List<Track> tracks,
            List<UnsupportedFile> unsupported, List<Playlist> playlists) {
        this(updateId, scannedAt, folders, tracks, unsupported, playlists, Map.of());
    }

    /**
     * @param containerUpdateIds per-container update ids by {@link ContainerKeys} key (containers without an
     *                           entry have update id 0)
     */
    public Snapshot(long updateId, Instant scannedAt, List<Folder> folders, List<Track> tracks,
            List<UnsupportedFile> unsupported, List<Playlist> playlists, Map<String, Long> containerUpdateIds) {
        this.containerUpdateIds = Map.copyOf(containerUpdateIds);
        this.updateId = updateId;
        this.scannedAt = scannedAt;
        this.tracks = List.copyOf(tracks);
        this.unsupported = List.copyOf(unsupported);

        Map<RootRef, Folder> fm = new LinkedHashMap<>();
        Map<String, Folder> fid = new HashMap<>();
        List<Folder> rootList = new ArrayList<>();
        for (Folder f : folders) {
            if (fm.put(f.ref(), f) != null) {
                throw new IllegalArgumentException("Duplicate folder: " + f.ref());
            }
            if (fid.put(f.id(), f) != null) {
                throw new IllegalArgumentException("Folder id collision: " + f.ref());
            }
            if (f.isRoot()) {
                rootList.add(f);
            }
        }
        Map<RootRef, Track> tm = new HashMap<>();
        Map<String, Track> tid = new HashMap<>();
        for (Track t : this.tracks) {
            if (tm.put(t.ref(), t) != null) {
                throw new IllegalArgumentException("Duplicate track: " + t.ref());
            }
            if (tid.put(t.id(), t) != null) {
                throw new IllegalArgumentException("Track id collision: " + t.ref());
            }
        }

        Map<RootRef, List<Folder>> subFolders = new HashMap<>();
        Map<RootRef, List<Track>> subTracks = new HashMap<>();
        for (Folder f : fm.values()) {
            if (!f.isRoot()) {
                requireParent(fm, f.ref());
                subFolders.computeIfAbsent(f.ref().parent(), k -> new ArrayList<>()).add(f);
            }
        }
        for (Track t : this.tracks) {
            requireParentFolder(fm, t.parentRef(), t.ref());
            subTracks.computeIfAbsent(t.parentRef(), k -> new ArrayList<>()).add(t);
        }
        Map<RootRef, DirectorySettings> eff = new HashMap<>();
        for (Folder f : fm.values()) {
            effective(fm, f.ref(), eff);
        }
        this.effective = Collections.unmodifiableMap(eff);
        Map<RootRef, IgnoreRules> rules = new HashMap<>();
        for (Folder f : fm.values()) {
            if (!f.settings().ignore().isEmpty()) {
                rules.put(f.ref(), new IgnoreRules(f.settings().ignore()));
            }
        }
        Map<RootRef, Boolean> ignoredMemo = new HashMap<>();
        java.util.Set<RootRef> ignoredFolders = new java.util.HashSet<>();
        java.util.Set<RootRef> ignoredTracks = new java.util.HashSet<>();
        if (!rules.isEmpty()) {
            for (Folder f : fm.values()) {
                if (!f.isRoot() && ignored(f.ref(), true, rules, ignoredMemo)) {
                    ignoredFolders.add(f.ref());
                }
            }
            for (Track t : this.tracks) {
                if (ignored(t.ref(), false, rules, ignoredMemo)) {
                    ignoredTracks.add(t.ref());
                }
            }
        }
        Map<RootRef, List<Child>> ch = new HashMap<>();
        Map<RootRef, List<Child>> all = new HashMap<>();
        Map<RootRef, List<String>> missing = new HashMap<>();
        for (Folder f : fm.values()) {
            ChildSorter.Result r = ChildSorter.sort(eff.get(f.ref()),
                    subFolders.getOrDefault(f.ref(), List.of()), subTracks.getOrDefault(f.ref(), List.of()));
            all.put(f.ref(), List.copyOf(r.children()));
            ch.put(f.ref(), r.children().stream()
                    .filter(c -> !(c instanceof Child.FolderChild fc
                            && (fc.folder().settings().hidden() || ignoredFolders.contains(fc.folder().ref()))))
                    .filter(c -> !(c instanceof Child.TrackChild tc && ignoredTracks.contains(tc.track().ref())))
                    .toList());
            if (!r.missing().isEmpty()) {
                missing.put(f.ref(), List.copyOf(r.missing()));
            }
        }

        this.folders = Collections.unmodifiableMap(fm);
        this.roots = List.copyOf(rootList);
        this.trackByRef = Collections.unmodifiableMap(tm);
        this.folderById = Collections.unmodifiableMap(fid);
        this.trackById = Collections.unmodifiableMap(tid);
        this.children = Collections.unmodifiableMap(ch);
        this.allChildren = Collections.unmodifiableMap(all);
        Map<RootRef, Boolean> hiddenMemo = new HashMap<>();
        this.visibleTracks = this.tracks.stream()
                .filter(t -> !ignoredTracks.contains(t.ref()) && !hidden(fm, t.parentRef(), hiddenMemo)
                        && !ignoredFolders.contains(t.parentRef()))
                .toList();
        this.missingOrderEntries = Collections.unmodifiableMap(missing);

        java.util.Set<RootRef> unsupportedRefs = new java.util.HashSet<>();
        this.unsupported.forEach(u -> unsupportedRefs.add(u.ref()));
        List<ResolvedPlaylist> resolved = new ArrayList<>();
        Map<String, ResolvedPlaylist> byId = new LinkedHashMap<>();
        for (Playlist p : playlists) {
            ResolvedPlaylist r = resolve(p, tm, unsupportedRefs);
            if (byId.putIfAbsent(r.id(), r) != null) {
                throw new IllegalArgumentException("Duplicate playlist: " + p.key());
            }
            resolved.add(r);
        }
        this.playlists = List.copyOf(resolved);
        this.playlistById = Collections.unmodifiableMap(byId);
    }

    private Snapshot(Snapshot o, long updateId, Instant scannedAt, Map<String, Long> containerUpdateIds) {
        this.updateId = updateId;
        this.scannedAt = scannedAt;
        this.roots = o.roots;
        this.tracks = o.tracks;
        this.unsupported = o.unsupported;
        this.folders = o.folders;
        this.trackByRef = o.trackByRef;
        this.folderById = o.folderById;
        this.trackById = o.trackById;
        this.children = o.children;
        this.allChildren = o.allChildren;
        this.visibleTracks = o.visibleTracks;
        this.effective = o.effective;
        this.missingOrderEntries = o.missingOrderEntries;
        this.playlists = o.playlists;
        this.playlistById = o.playlistById;
        this.containerUpdateIds = Map.copyOf(containerUpdateIds);
    }

    /** The same content with other versioning: update id, last completed scan time and container update ids. */
    public Snapshot withUpdates(long updateId, Instant scannedAt, Map<String, Long> containerUpdateIds) {
        return new Snapshot(this, updateId, scannedAt, containerUpdateIds);
    }

    /** UpdateID of a container ({@link ContainerKeys}); 0 when it never changed. */
    public long containerUpdateId(String key) {
        return containerUpdateIds.getOrDefault(key, 0L);
    }

    public Map<String, Long> containerUpdateIds() {
        return containerUpdateIds;
    }

    /** The raw playlists (as stored in their files), in display order. */
    public List<Playlist> playlistDefinitions() {
        return playlists.stream().map(ResolvedPlaylist::playlist).toList();
    }

    private static ResolvedPlaylist resolve(Playlist p, Map<RootRef, Track> tracks,
            java.util.Set<RootRef> unsupported) {
        List<ResolvedPlaylist.Entry> entries = new ArrayList<>();
        for (String song : p.songs()) {
            RootRef ref;
            try {
                ref = RootRef.parse(song);
            } catch (IllegalArgumentException e) {
                entries.add(new ResolvedPlaylist.Entry(song, null, ResolvedPlaylist.Status.MISSING));
                continue;
            }
            Track t = tracks.get(ref);
            if (t != null) {
                entries.add(new ResolvedPlaylist.Entry(song, t, ResolvedPlaylist.Status.OK));
            } else if (unsupported.contains(ref)) {
                entries.add(new ResolvedPlaylist.Entry(song, null, ResolvedPlaylist.Status.UNSUPPORTED));
            } else {
                entries.add(new ResolvedPlaylist.Entry(song, null, ResolvedPlaylist.Status.MISSING));
            }
        }
        return new ResolvedPlaylist(p, entries);
    }

    private static void requireParentFolder(Map<RootRef, Folder> fm, RootRef parent, RootRef ref) {
        if (!fm.containsKey(parent)) {
            throw new IllegalArgumentException("Entry without parent folder: " + ref);
        }
    }

    private static void requireParent(Map<RootRef, Folder> fm, RootRef ref) {
        if (!fm.containsKey(ref.parent())) {
            throw new IllegalArgumentException("Entry without parent folder: " + ref);
        }
    }

    public static Snapshot empty() {
        return EMPTY;
    }

    public long updateId() {
        return updateId;
    }

    public Instant scannedAt() {
        return scannedAt;
    }

    /** Library roots in configured order. */
    public List<Folder> roots() {
        return roots;
    }

    public List<Track> tracks() {
        return tracks;
    }

    public Collection<Folder> allFolders() {
        return folders.values();
    }

    public List<UnsupportedFile> unsupported() {
        return unsupported;
    }

    public Optional<Folder> folder(RootRef ref) {
        return Optional.ofNullable(folders.get(ref));
    }

    public Optional<Folder> folderById(String id) {
        return Optional.ofNullable(folderById.get(id));
    }

    public Optional<Track> track(RootRef ref) {
        return Optional.ofNullable(trackByRef.get(ref));
    }

    public Optional<Track> trackById(String id) {
        return Optional.ofNullable(trackById.get(id));
    }

    /** Sorted direct children of a directory (empty for unknown refs). */
    public List<Child> children(RootRef folder) {
        return children.getOrDefault(folder, List.of());
    }

    /** Direct children including the directories hidden by their settings (for the admin). */
    public List<Child> allChildren(RootRef folder) {
        return allChildren.getOrDefault(folder, List.of());
    }

    /** The tracks outside every hidden directory. */
    public List<Track> visibleTracks() {
        return visibleTracks;
    }

    /**
     * The settings in force for a directory: its own, completed with what it inherits from its parent
     * ({@link DirectorySettings#inheritFrom}). {@code Folder.settings()} stays the directory's own file content.
     */
    public DirectorySettings effectiveSettings(RootRef folder) {
        DirectorySettings s = effective.get(folder);
        return s != null ? s : DirectorySettings.DEFAULT;
    }

    private static DirectorySettings effective(Map<RootRef, Folder> fm, RootRef ref,
            Map<RootRef, DirectorySettings> memo) {
        DirectorySettings known = memo.get(ref);
        if (known != null) {
            return known;
        }
        Folder f = fm.get(ref);
        DirectorySettings s = f.settings();
        if (!f.isRoot() && fm.containsKey(ref.parent())) {
            s = s.inheritFrom(effective(fm, ref.parent(), memo));
        }
        memo.put(ref, s);
        return s;
    }

    /** True when a hide pattern of this entry's directory or of an ancestor, or an ignored ancestor, hides it. */
    private static boolean ignored(RootRef ref, boolean isDir, Map<RootRef, IgnoreRules> rules,
            Map<RootRef, Boolean> memo) {
        RootRef memoKey = isDir ? ref : null;
        if (memoKey != null && memo.containsKey(memoKey)) {
            return memo.get(memoKey);
        }
        RootRef parent = ref.parent();
        boolean result = false;
        if (!parent.isRoot() && ignored(parent, true, rules, memo)) {
            result = true;
        } else {
            Boolean state = null;
            List<RootRef> chain = new ArrayList<>();
            for (RootRef a = parent;; a = a.parent()) {
                chain.add(0, a);
                if (a.isRoot()) {
                    break;
                }
            }
            for (RootRef a : chain) {
                IgnoreRules r = rules.get(a);
                if (r == null) {
                    continue;
                }
                String rel = a.isRoot() ? ref.rel() : ref.rel().substring(a.rel().length() + 1);
                Boolean m = r.match(rel, isDir);
                if (m != null) {
                    state = m;
                }
            }
            result = Boolean.TRUE.equals(state);
        }
        if (memoKey != null) {
            memo.put(memoKey, result);
        }
        return result;
    }

    private static boolean hidden(Map<RootRef, Folder> fm, RootRef ref, Map<RootRef, Boolean> memo) {
        Boolean known = memo.get(ref);
        if (known != null) {
            return known;
        }
        Folder f = fm.get(ref);
        boolean h = f != null && (f.settings().hidden() || !f.isRoot() && hidden(fm, ref.parent(), memo));
        memo.put(ref, h);
        return h;
    }

    /** Entries of the folder's explicit {@code order} that matched no child. */
    public List<String> missingOrderEntries(RootRef folder) {
        return missingOrderEntries.getOrDefault(folder, List.of());
    }

    /** All playlists in display order. */
    public List<ResolvedPlaylist> playlists() {
        return playlists;
    }

    public Optional<ResolvedPlaylist> playlist(String id) {
        return Optional.ofNullable(playlistById.get(id));
    }

    /** Directories whose settings.json could not be parsed. */
    public List<Folder> foldersWithSettingsErrors() {
        return folders.values().stream().filter(f -> f.settingsError() != null).toList();
    }

    /** Tracks of the folder's subtree (the folder itself and all descendants), in display order. */
    public List<Track> subtreeTracks(RootRef folder) {
        List<Track> out = new ArrayList<>();
        collect(folder, out);
        return out;
    }

    private void collect(RootRef folder, List<Track> out) {
        for (Child c : children(folder)) {
            switch (c) {
                case Child.TrackChild t -> out.add(t.track());
                case Child.FolderChild f -> collect(f.folder().ref(), out);
            }
        }
    }

}
