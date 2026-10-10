package org.kendar.dlna.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.shared.RootRef;

class PlaylistAndNavigationTest {

    private final Track a = track("m/a.mp3");
    private final Track b = track("m/b.mp3");
    private final Track c = track("m/Sub/c.mp3");
    private final Snapshot snap = new Snapshot(1, Instant.EPOCH, List.of(folder("m"), folder("m/Sub")),
            List.of(a, b, c), List.of(new UnsupportedFile(RootRef.parse("m/old.m4a"), 10, "no decoder")),
            List.of(new Playlist("road", "Road trip", List.of("m/b.mp3", "m/gone.mp3", "m/old.m4a", "m/Sub/c.mp3",
                    "../bad", "m/a.mp3")), new Playlist("empty", null, null)));

    @Test
    void playlistEntriesAreFlaggedAndOnlyPlayableOnesAreTracks() {
        ResolvedPlaylist p = snap.playlist(Playlist.idOf("road")).orElseThrow();

        assertThat(p.name()).isEqualTo("Road trip");
        assertThat(p.entries()).extracting(ResolvedPlaylist.Entry::status).containsExactly(
                ResolvedPlaylist.Status.OK, ResolvedPlaylist.Status.MISSING, ResolvedPlaylist.Status.UNSUPPORTED,
                ResolvedPlaylist.Status.OK, ResolvedPlaylist.Status.MISSING, ResolvedPlaylist.Status.OK);
        assertThat(p.tracks()).containsExactly(b, c, a);
        assertThat(p.brokenCount()).isEqualTo(3);
        assertThat(p.entries().get(1).path()).isEqualTo("m/gone.mp3");
    }

    @Test
    void playlistNameFallsBackToKeyAndOrderIsKept() {
        assertThat(snap.playlists()).extracting(ResolvedPlaylist::name).containsExactly("Road trip", "empty");
        assertThat(snap.playlist(Playlist.idOf("empty")).orElseThrow().tracks()).isEmpty();
        assertThat(snap.playlist("nope")).isEmpty();
    }

    @Test
    void playlistNavigationFollowsPlayOrderWithoutWrapAround() {
        CatalogNavigation nav = new CatalogNavigation(() -> snap);
        String ctx = CatalogNavigation.playlistContext(Playlist.idOf("road"));

        assertThat(nav.knows(ctx)).isTrue();
        assertThat(nav.next(ctx, b.id())).contains(c);
        assertThat(nav.next(ctx, c.id())).contains(a);
        assertThat(nav.next(ctx, a.id())).isEmpty();
        assertThat(nav.next(ctx, "unknown")).isEmpty();
        assertThat(nav.knows(CatalogNavigation.playlistContext("zzz"))).isFalse();
    }

    @Test
    void folderNavigationSkipsSubdirectoriesAndStopsAtTheEnd() {
        CatalogNavigation nav = new CatalogNavigation(() -> snap);
        String ctx = Folder.idOf(RootRef.parse("m"));

        assertThat(nav.knows(ctx)).isTrue();
        assertThat(nav.next(ctx, a.id())).contains(b);
        assertThat(nav.next(ctx, b.id())).isEmpty();
        assertThat(nav.knows("nope")).isFalse();
        assertThat(nav.next(null, a.id())).isEmpty();
    }

    @Test
    void multiValueTagsFallBackToSingleValue() {
        TrackTags single = new TrackTags("t", "A", null, null, "Rock", null, null, null, null);
        assertThat(single.allArtists()).containsExactly("A");
        assertThat(single.allGenres()).containsExactly("Rock");
        assertThat(single.allAlbumArtists()).isEmpty();

        TrackTags multi = new TrackTags("t", "A", null, null, null, null, null, null, null, List.of("A", "B"), null,
                null);
        assertThat(multi.allArtists()).containsExactly("A", "B");
    }

    @Test
    void overlayReplacesMultiValueAlbumArtistAndGenre() {
        TrackTags tags = new TrackTags("t", "A", "Alb", "X", "Rock", null, null, null, null, null,
                List.of("X", "Y"), List.of("Rock", "Pop"));
        Track t = track("m/x.mp3", tags, 0);

        EffectiveTrack plain = EffectiveTrack.of(t, DirectorySettings.DEFAULT);
        assertThat(plain.albumArtists()).containsExactly("X", "Y");
        assertThat(plain.genres()).containsExactly("Rock", "Pop");

        DirectorySettings overlay = new DirectorySettings(null, null,
                new AlbumInfo(null, "Over", null, "Jazz", null, null), 0, null);
        EffectiveTrack o = EffectiveTrack.of(t, overlay);
        assertThat(o.albumArtists()).containsExactly("Over");
        assertThat(o.genres()).containsExactly("Jazz");
    }
}
