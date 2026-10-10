package org.kendar.dlna.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SnapshotDiffTest {

    private static final DirectorySettings RANDOM = new DirectorySettings(null, null, null, 5, null);

    private static Snapshot snap(List<Folder> folders, List<Track> tracks, List<Playlist> playlists) {
        return new Snapshot(1, Instant.EPOCH, folders, tracks, List.of(), playlists);
    }

    private static String key(String qualified) {
        return folder(qualified).id();
    }

    @Test
    void identicalSnapshotsHaveNoChange() {
        Snapshot a = snap(List.of(folder("m"), folder("m/a")), List.of(track("m/a/x.mp3")), List.of());
        Snapshot b = snap(List.of(folder("m"), folder("m/a")), List.of(track("m/a/x.mp3")), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).isEmpty();
    }

    @Test
    void addedTrackChangesOnlyItsFolder() {
        Snapshot a = snap(List.of(folder("m"), folder("m/a"), folder("m/b")), List.of(track("m/a/x.mp3")), List.of());
        Snapshot b = snap(List.of(folder("m"), folder("m/a"), folder("m/b")),
                List.of(track("m/a/x.mp3"), track("m/a/y.mp3")), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).containsExactly(key("m/a"));
    }

    @Test
    void changedTrackMetadataChangesItsFolder() {
        Snapshot a = snap(List.of(folder("m")), List.of(track("m/x.mp3", TrackTags.EMPTY, 1)), List.of());
        Snapshot b = snap(List.of(folder("m")), List.of(track("m/x.mp3", TrackTags.EMPTY, 2)), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).containsExactly(key("m"));
    }

    @Test
    void newAndRemovedSubfoldersChangeTheParent() {
        Snapshot a = snap(List.of(folder("m")), List.of(), List.of());
        Snapshot b = snap(List.of(folder("m"), folder("m/n")), List.of(), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).containsExactlyInAnyOrder(key("m"), key("m/n"));
        assertThat(SnapshotDiff.changedContainers(b, a)).containsExactlyInAnyOrder(key("m"), key("m/n"));
    }

    @Test
    void settingsChangeMarksFolderAndParent() {
        Snapshot a = snap(List.of(folder("m"), folder("m/a")), List.of(), List.of());
        Snapshot b = snap(
                List.of(folder("m"), folder("m/a", new DirectorySettings(SortMode.TITLE, null, null, 0, null))),
                List.of(), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).containsExactlyInAnyOrder(key("m"), key("m/a"));
    }

    @Test
    void displayNameChangeMarksFolderAndParent() {
        Snapshot a = snap(List.of(folder("m"), folder("m/a")), List.of(), List.of());
        Snapshot b = snap(List.of(folder("m"),
                folder("m/a", new DirectorySettings(null, null, null, 0, null, 0, "Shown"))), List.of(), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).containsExactlyInAnyOrder(key("m"), key("m/a"));
        assertThat(SnapshotDiff.changedContainers(b, a)).containsExactlyInAnyOrder(key("m"), key("m/a"));
    }

    @Test
    void rootDisplayNameChangeMarksTheFoldersList() {
        Snapshot a = snap(List.of(folder("m")), List.of(), List.of());
        Snapshot b = snap(List.of(folder("m", new DirectorySettings(null, null, null, 0, null, 0, "Shown"))),
                List.of(), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).containsExactlyInAnyOrder(key("m"), ContainerKeys.FOLDERS);
    }

    @Test
    void directoryMtimeAloneIsNoise() {
        Folder f1 = new Folder(folder("m").ref(), 1, null, null, false, false);
        Folder f2 = new Folder(folder("m").ref(), 2, null, null, false, false);
        assertThat(SnapshotDiff.changedContainers(snap(List.of(f1), List.of(), List.of()),
                snap(List.of(f2), List.of(), List.of()))).isEmpty();
    }

    @Test
    void recentlyAddedCountsAsAVirtualContainer() {
        List<Folder> folders = List.of(folder("m", new DirectorySettings(null, null, null, 0, null, 3)), folder("m/a"));
        Snapshot a = snap(folders, List.of(), List.of());
        Snapshot b = snap(folders, List.of(track("m/a/x.mp3")), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).containsExactlyInAnyOrder(key("m/a"),
                ContainerKeys.virtual(key("m")));
    }

    @Test
    void aChangedAddedAtChangesTheFolderButNotUnrelatedContainers() {
        List<Folder> folders = List.of(folder("m"), folder("m/a"), folder("m/b"));
        Snapshot a = snap(folders, List.of(track("m/a/x.mp3").withAddedAt(1)), List.of());
        Snapshot b = snap(folders, List.of(track("m/a/x.mp3").withAddedAt(2)), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).containsExactly(key("m/a"));
    }

    @Test
    void virtualContainersOfAncestorsChangeWhenTheSubtreePoolChanges() {
        List<Folder> folders = List.of(folder("m", RANDOM), folder("m/a"), folder("m/a/b"));
        Snapshot a = snap(folders, List.of(), List.of());
        Snapshot b = snap(folders, List.of(track("m/a/b/x.mp3")), List.of());
        Set<String> changed = SnapshotDiff.changedContainers(a, b);
        assertThat(changed).containsExactlyInAnyOrder(key("m/a/b"), ContainerKeys.virtual(key("m")));
    }

    @Test
    void playlistChangesAreTracked() {
        Playlist p = new Playlist("pl", "PL", List.of("m/x.mp3"));
        Snapshot a = snap(List.of(folder("m")), List.of(), List.of(p));
        Snapshot b = snap(List.of(folder("m")), List.of(track("m/x.mp3")), List.of(p));
        Set<String> changed = SnapshotDiff.changedContainers(a, b);
        assertThat(changed).contains(ContainerKeys.playlist(p.id()), key("m"));
        assertThat(changed).doesNotContain(ContainerKeys.PLAYLISTS);

        Snapshot c = snap(List.of(folder("m")), List.of(track("m/x.mp3")),
                List.of(p, new Playlist("q", "Q", List.of())));
        assertThat(SnapshotDiff.changedContainers(b, c)).contains(ContainerKeys.PLAYLISTS);
    }

    @Test
    void rootListChangeMarksFoldersContainer() {
        Snapshot a = snap(List.of(folder("m")), List.of(), List.of());
        Snapshot b = snap(List.of(folder("m"), folder("n")), List.of(), List.of());
        assertThat(SnapshotDiff.changedContainers(a, b)).contains(ContainerKeys.FOLDERS);
    }
}
