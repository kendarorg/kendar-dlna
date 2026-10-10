package org.kendar.dlna.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.shared.RootRef;

class SnapshotTest {

    private final Snapshot snap = snapshot(
            List.of(folder("a"), folder("a/x"), folder("a/x/deep"), folder("b")),
            List.of(track("a/t1.mp3"), track("a/x/t2.flac"), track("a/x/deep/t3.mp3")));

    @Test
    void exposesRootsInGivenOrderAndLookups() {
        assertThat(snap.roots()).extracting(f -> f.ref().root()).containsExactly("a", "b");
        assertThat(snap.updateId()).isEqualTo(7);
        Track t = snap.track(RootRef.parse("a/x/t2.flac")).orElseThrow();
        assertThat(snap.trackById(t.id())).contains(t);
        Folder f = snap.folder(RootRef.parse("a/x")).orElseThrow();
        assertThat(snap.folderById(f.id())).contains(f);
        assertThat(snap.trackById("nope")).isEmpty();
    }

    @Test
    void childrenAreSortedSubdirsFirst() {
        assertThat(snap.children(RootRef.parse("a"))).extracting(c -> c.ref().name()).containsExactly("x", "t1.mp3");
        assertThat(snap.children(RootRef.parse("b"))).isEmpty();
        assertThat(snap.children(RootRef.parse("zzz"))).isEmpty();
    }

    @Test
    void subtreeCollectsDescendantTracks() {
        assertThat(snap.subtreeTracks(RootRef.parse("a"))).extracting(t -> t.ref().name())
                .containsExactly("t3.mp3", "t2.flac", "t1.mp3");
    }

    @Test
    void rejectsOrphansAndDuplicates() {
        assertThatThrownBy(() -> snapshot(List.of(folder("a")), List.of(track("a/missing/t.mp3"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(List.of(folder("a"), folder("a")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void holderSwapsAtomically() {
        SnapshotHolder holder = new SnapshotHolder();
        assertThat(holder.current()).isSameAs(Snapshot.empty());
        Snapshot old = holder.commit(snap);
        assertThat(old).isSameAs(Snapshot.empty());
        assertThat(holder.current()).isSameAs(snap);
    }

    @Test
    void settingsErrorsAreListedAndStoreNoneLoadsNothing() {
        Folder bad = new Folder(RootRef.parse("a"), 0, null, "boom", false, false);
        Snapshot s = snapshot(List.of(bad), List.of());
        assertThat(s.foldersWithSettingsErrors()).containsExactly(bad);
        assertThat(CatalogStore.none().load()).isEmpty();
    }
}
