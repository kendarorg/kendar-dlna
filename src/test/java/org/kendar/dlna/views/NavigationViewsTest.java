package org.kendar.dlna.views;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Navigation;
import org.kendar.dlna.catalog.NavigationRegistry;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.UnsupportedFile;

class NavigationViewsTest {

    private static final Navigation ALBUM = new Navigation("Album", null, null, List.of("album", "title"));
    private static final Navigation ARTISTS = new Navigation("Artists", null, null,
            List.of("artist", "album", "title"));
    private static final Navigation GENRES = new Navigation("Genres", null, null,
            List.of("genre", "artist", "album", "title"));
    private static final Navigation SONGS = new Navigation("Songs", null, null, List.of("title"));
    private static final Navigation TYPES = new Navigation("Kinds", null, null, List.of("type", "bitrate", "title"));

    private final Folder lib = folder("lib");
    private final Folder rock = folder("lib/Rock");
    private final List<Track> tracks = new ArrayList<>();

    private Track add(String path, String title, String artist, String album, String genre, int no) {
        Track t = track(path, new TrackTags(title, artist, album, artist, genre, 1999, no, null, null), 0);
        tracks.add(t);
        return t;
    }

    private Snapshot snapshot(Folder... folders) {
        return new Snapshot(5, Instant.EPOCH, List.of(folders), tracks, List.<UnsupportedFile>of(), List.of());
    }

    private Views views(Navigation... navs) {
        return new Views(() -> null).withNavigations(new NavigationRegistry(List.of(navs), List.of()));
    }

    private static DirectorySettings listing(String... names) {
        return new DirectorySettings(null, null, null, 0, null, 0, null, false, null, false, List.of(), null, null,
                List.of(names));
    }

    @Test
    void theSampleNavigationsGroupByTheirTagLevels() {
        add("lib/Rock/a1.mp3", "One", "Ann", "Blue", "Rock", 1);
        add("lib/Rock/a2.mp3", "Two", "Ann", "Blue", "Rock", 2);
        add("lib/Rock/b1.mp3", "Three", "Bob", "Red", "Pop", 1);
        Snapshot snap = snapshot(lib, rock);
        Views v = views(ALBUM, ARTISTS, GENRES, SONGS);

        List<ViewNode> roots = v.navigationRoots(snap, lib);
        assertThat(roots).extracting(ViewNode::title).containsExactly("[Album]", "[Artists]", "[Genres]", "[Songs]");

        ViewNode albums = roots.get(0);
        assertThat(albums.children()).extracting(ViewNode::title).containsExactly("Blue", "Red");
        assertThat(albums.children().get(0).tracks()).extracting(t -> t.tags().title()).containsExactly("One", "Two");

        ViewNode artists = roots.get(1);
        assertThat(artists.children()).extracting(ViewNode::title).containsExactly("Ann", "Bob");
        ViewNode ann = artists.children().get(0);
        assertThat(ann.children()).extracting(ViewNode::title).containsExactly("Blue", "All tracks");
        assertThat(ann.children().get(1).tracks()).hasSize(2);

        ViewNode genres = roots.get(2);
        assertThat(genres.children()).extracting(ViewNode::title).containsExactly("Pop", "Rock");
        ViewNode rockGenre = genres.children().get(1);
        assertThat(rockGenre.children()).extracting(ViewNode::title).containsExactly("Ann");
        assertThat(rockGenre.children().get(0).children()).extracting(ViewNode::title).containsExactly("Blue", "All tracks");

        ViewNode songs = roots.get(3);
        assertThat(songs.tracks()).extracting(t -> t.tags().title()).containsExactly("One", "Three", "Two");
    }

    @Test
    void libraryRootsListEveryNavigationAndSubdirectoriesOnlyWhatTheyListOrInherit() {
        add("lib/Rock/a1.mp3", "One", "Ann", "Blue", "Rock", 1);
        Folder listed = folder("lib/Listing", listing("songs"));
        tracks.add(track("lib/Listing/x.mp3", new TrackTags("X", null, null, null, null, null, null, null, null), 0));
        Folder inheriting = folder("lib/Listing/Child");
        tracks.add(track("lib/Listing/Child/y.mp3",
                new TrackTags("Y", null, null, null, null, null, null, null, null), 0));
        Snapshot snap = snapshot(lib, rock, listed, inheriting);
        Views v = views(ALBUM, SONGS);

        assertThat(v.navigationsOf(snap, lib)).containsExactly(ALBUM, SONGS);
        assertThat(v.navigationsOf(snap, rock)).isEmpty();
        assertThat(v.navigationsOf(snap, listed)).containsExactly(SONGS);
        assertThat(v.navigationsOf(snap, inheriting)).as("inherited from the parent directory").containsExactly(SONGS);
        assertThat(v.navigationRoots(snap, listed)).hasSize(1);
        assertThat(v.navigationRoots(snap, listed).get(0).tracks()).as("only its own subtree").hasSize(2);
    }

    @Test
    void virtualTagsGroupBySynthesizedVersusRecordedAndBitrateBand() {
        add("lib/Rock/a1.mp3", "One", "Ann", "Blue", "Rock", 1);
        tracks.add(track("lib/Rock/song.mid", new TrackTags("Midi", null, null, null, null, null, null, null, null), 0));
        Snapshot snap = snapshot(lib, rock);

        ViewNode kinds = views(TYPES).navigationRoots(snap, lib).get(0);

        assertThat(kinds.children()).extracting(ViewNode::title).containsExactly("analog", "digital");
        assertThat(kinds.children().get(0).children()).extracting(ViewNode::title).containsExactly("320 kbps");
        assertThat(kinds.children().get(1).children()).extracting(ViewNode::title).containsExactly("n/a");
    }

    @Test
    void longLevelsAreSplitIntoRangesWithStableIds() {
        for (int i = 0; i < 120; i++) {
            add(String.format("lib/Rock/s%03d.mp3", i), String.format("Song %03d", i), "Ann", "Blue", "Rock", i);
        }
        Snapshot snap = snapshot(lib, rock);
        Views v = views(SONGS);

        ViewNode songs = v.navigationRoots(snap, lib).get(0);
        assertThat(songs.tracks()).isEmpty();
        assertThat(songs.children()).hasSize(10).allSatisfy(c -> {
            assertThat(c.type()).isEqualTo(ViewNode.Type.BUCKET);
            assertThat(c.tracks()).isNotEmpty();
            assertThat(c.parentId()).isEqualTo(songs.id());
        });
        assertThat(songs.children().stream().mapToInt(c -> c.tracks().size()).sum()).isEqualTo(120);
        assertThat(v.navigationRoots(snap, lib).get(0).children().get(0).id()).isEqualTo(songs.children().get(0).id());
        // a node resolves by its id through the snapshot
        assertThat(v.node(snap, songs.children().get(3).id())).isPresent();
    }

    @Test
    void aNavigationOverridesTheGlobalThresholds() {
        for (int i = 0; i < 30; i++) {
            add(String.format("lib/Rock/s%03d.mp3", i), String.format("Song %03d", i), "Ann", "Blue", "Rock", i);
        }
        Snapshot snap = snapshot(lib, rock);
        Navigation small = new Navigation("Small", 10, 3, List.of("title"));

        ViewNode root = views(small).navigationRoots(snap, lib).get(0);

        assertThat(root.children()).hasSize(3);
    }

    @Test
    void theRegistryChangeDropsTheBuiltTrees() {
        add("lib/Rock/a1.mp3", "One", "Ann", "Blue", "Rock", 1);
        Snapshot snap = snapshot(lib, rock);
        NavigationRegistry reg = new NavigationRegistry(List.of(SONGS), List.of());
        Views v = new Views(() -> snap).withNavigations(reg);
        assertThat(v.navigationRoots(snap, lib)).extracting(ViewNode::title).containsExactly("[Songs]");

        reg.set(List.of(ALBUM), List.of());

        assertThat(v.navigationRoots(snap, lib)).extracting(ViewNode::title).containsExactly("[Album]");
    }
}
