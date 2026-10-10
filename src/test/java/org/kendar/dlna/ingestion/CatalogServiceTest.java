package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.CatalogStore;
import org.kendar.dlna.catalog.ChangeSet;
import org.kendar.dlna.catalog.ContainerKeys;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.MutableClock;

class CatalogServiceTest {

    static final class MemoryStore implements CatalogStore {
        Snapshot saved;
        int saves;

        @Override
        public Optional<Snapshot> load() {
            return Optional.ofNullable(saved);
        }

        @Override
        public void save(Snapshot snapshot) {
            saved = snapshot;
            saves++;
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final MutableClock clock = new MutableClock();
    private final List<ChangeSet> events = new ArrayList<>();

    private CatalogService service(Snapshot initial) {
        CatalogService s = new CatalogService(new SnapshotHolder(initial), store, clock);
        s.addListener(events::add);
        return s;
    }

    private static Snapshot snap(long id, Instant at, List<org.kendar.dlna.catalog.Track> tracks,
            List<Playlist> playlists) {
        return new Snapshot(id, at, List.of(folder("m")), tracks, List.of(), playlists);
    }

    @Test
    void changeBumpsSystemIdOnceAndTheChangedContainersOnly() {
        CatalogService s = service(snap(5, Instant.EPOCH, List.of(track("m/a.mp3")), List.of()));
        Set<String> changed = s.commit(snap(1, Instant.EPOCH.plusSeconds(9),
                List.of(track("m/a.mp3"), track("m/b.mp3")), List.of()));

        String key = folder("m").id();
        assertThat(changed).containsExactly(key);
        assertThat(s.current().updateId()).isEqualTo(6);
        assertThat(s.current().containerUpdateId(key)).isEqualTo(6);
        assertThat(s.current().scannedAt()).isEqualTo(Instant.EPOCH.plusSeconds(9));
        assertThat(store.saves).isEqualTo(1);
        assertThat(store.saved).isSameAs(s.current());
        assertThat(events).containsExactly(new ChangeSet(6, Set.of(key)));
    }

    @Test
    void containerIdsAccumulateAcrossCommits() {
        CatalogService s = service(snap(1, Instant.EPOCH, List.of(), List.of()));
        s.commit(snap(0, Instant.EPOCH, List.of(track("m/a.mp3")), List.of()));
        s.commit(snap(0, Instant.EPOCH, List.of(track("m/a.mp3")),
                List.of(new Playlist("p", "P", List.of("m/a.mp3")))));
        assertThat(s.current().updateId()).isEqualTo(3);
        assertThat(s.current().containerUpdateId(folder("m").id())).isEqualTo(2);
        assertThat(s.current().containerUpdateId(ContainerKeys.PLAYLISTS)).isEqualTo(3);
        assertThat(s.current().containerUpdateId(ContainerKeys.playlist(Playlist.idOf("p")))).isEqualTo(3);
    }

    @Test
    void noChangeKeepsIdsAndOnlyMovesTheScanTime() {
        Snapshot initial = snap(5, Instant.EPOCH, List.of(track("m/a.mp3")), List.of());
        CatalogService s = service(initial);
        Snapshot sameTime = snap(5, Instant.EPOCH, List.of(track("m/a.mp3")), List.of());
        assertThat(s.commit(sameTime)).isEmpty();
        assertThat(store.saves).isZero();
        assertThat(s.current()).isSameAs(initial);

        Instant later = Instant.EPOCH.plusSeconds(100);
        assertThat(s.commit(snap(5, later, List.of(track("m/a.mp3")), List.of()))).isEmpty();
        assertThat(s.current().updateId()).isEqualTo(5);
        assertThat(s.current().scannedAt()).isEqualTo(later);
        assertThat(store.saves).isEqualTo(1);
        assertThat(events).isEmpty();
    }

    @Test
    void removeTrackIsCopyOnWriteAndPersisted() {
        Snapshot initial = snap(5, Instant.EPOCH, List.of(track("m/a.mp3"), track("m/b.mp3")),
                List.of(new Playlist("p", "P", List.of("m/a.mp3"))));
        CatalogService s = service(initial);

        assertThat(s.removeTrack(RootRef.parse("m/a.mp3"))).isTrue();

        assertThat(initial.tracks()).hasSize(2);   // old snapshot untouched
        assertThat(s.current().tracks()).extracting(t -> t.ref().qualified()).containsExactly("m/b.mp3");
        assertThat(s.current().updateId()).isEqualTo(6);
        assertThat(s.current().scannedAt()).isEqualTo(Instant.EPOCH);   // not a completed scan
        assertThat(s.current().playlists().get(0).brokenCount()).isEqualTo(1);
        assertThat(s.current().containerUpdateId(ContainerKeys.playlist(Playlist.idOf("p")))).isEqualTo(6);
        assertThat(store.saved).isSameAs(s.current());
        assertThat(s.removeTrack(RootRef.parse("m/a.mp3"))).isFalse();
        assertThat(store.saves).isEqualTo(1);
    }

    @Test
    void failingListenersDoNotBreakTheCommit() {
        CatalogService s = service(snap(1, Instant.EPOCH, List.of(), List.of()));
        s.addListener(c -> {
            throw new IllegalStateException("boom");
        });
        s.commit(snap(0, Instant.EPOCH, List.of(track("m/a.mp3")), List.of()));
        assertThat(s.current().tracks()).hasSize(1);
        assertThat(events).hasSize(1);
    }

    @Test
    void updateAppliesAnEditToTheCurrentSnapshotAtomically() {
        CatalogService s = service(snap(1, Instant.EPOCH, List.of(track("m/a.mp3")), List.of()));
        Set<String> changed = s.update(cur -> snap(0, cur.scannedAt(), List.of(track("m/a.mp3"), track("m/b.mp3")),
                List.of()));
        assertThat(changed).containsExactly(folder("m").id());
        assertThat(s.current().tracks()).hasSize(2);
        assertThat(s.current().updateId()).isEqualTo(2);
        assertThat(store.saves).isEqualTo(1);
    }
}
