package org.kendar.dlna.views;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.testutil.Fixtures;

class ViewsTest {

    private final Folder root = folder("m", new DirectorySettings(null, null, null, 0,
            List.of(ViewKind.TITLE, ViewKind.ALBUM, ViewKind.ARTIST, ViewKind.GENRE)));
    private final List<Folder> folders = new ArrayList<>(List.of(root, folder("m/A"), folder("m/B"), folder("m/C")));
    private final List<Track> tracks = new ArrayList<>();
    private final AtomicReference<Snapshot> catalog = new AtomicReference<>();

    private static TrackTags tags(String title, String artist, String album, String albumArtist, String genre,
            Integer year, Integer no, Integer disc) {
        return new TrackTags(title, artist, album, albumArtist, genre, year, no, disc, null);
    }

    private Track add(String path, TrackTags t) {
        Track tr = track(path, t, 0);
        tracks.add(tr);
        return tr;
    }

    private Views views(int threshold) {
        catalog.set(Fixtures.snapshot(folders, tracks));
        return new Views(catalog::get, threshold);
    }

    private ViewNode root(Views v, ViewKind kind) {
        return v.root(catalog.get(), catalog.get().folder(root.ref()).orElseThrow(), kind).orElseThrow();
    }

    private static List<String> titles(List<ViewNode> nodes) {
        return nodes.stream().map(ViewNode::title).toList();
    }

    private static List<String> titlesOf(List<Track> ts) {
        return ts.stream().map(t -> t.tags().title()).toList();
    }

    @Test
    void albumsGroupByAlbumArtistAndTitleAndSortTracksByDiscTrackFilename() {
        add("m/A/b.mp3", tags("Second", "Ann", "Blue", "Ann", "Rock", 1999, 2, 1));
        add("m/A/a.mp3", tags("First", "Ann", "Blue", "Ann", "Rock", 1999, 1, 1));
        add("m/A/d2.mp3", tags("Disc two", "Ann", "Blue", "Ann", "Rock", 1999, 1, 2));
        add("m/B/z.mp3", tags("Other", "Bob", "Alpha", "Bob", "Pop", 2005, 1, null));
        Views v = views(500);

        ViewNode albums = root(v, ViewKind.ALBUM);

        assertThat(albums.title()).isEqualTo("[Albums]");
        assertThat(albums.id()).isEqualTo("v." + root.id() + ".al");
        assertThat(titles(albums.children())).containsExactly("Alpha", "Blue");
        ViewNode blue = albums.children().get(1);
        assertThat(blue.type()).isEqualTo(ViewNode.Type.ALBUM);
        assertThat(blue.artist()).isEqualTo("Ann");
        assertThat(blue.year()).isEqualTo(1999);
        assertThat(titlesOf(blue.tracks())).containsExactly("First", "Second", "Disc two");
        assertThat(blue.parentId()).isEqualTo(albums.id());
        assertThat(albums.parentId()).isNull();
    }

    @Test
    void sameAlbumTitleByDifferentArtistsStaysSeparateWithArtistAppended() {
        add("m/A/1.mp3", tags("x", "Ann", "Greatest Hits", "Ann", null, null, 1, null));
        add("m/B/1.mp3", tags("y", "Bob", "Greatest Hits", "Bob", null, null, 1, null));
        add("m/C/1.mp3", tags("z", "Cy", "Solo", "Cy", null, null, 1, null));

        ViewNode albums = root(views(500), ViewKind.ALBUM);

        assertThat(titles(albums.children())).containsExactly("Greatest Hits (Ann)", "Greatest Hits (Bob)", "Solo");
    }

    @Test
    void keysAreNormalizedAndMostFrequentSpellingWins() {
        add("m/A/1.mp3", tags("a", "Beyonce", "Lemonade", "Beyonce", null, null, 1, null));
        add("m/A/2.mp3", tags("b", "Beyoncé", "lemonade", "Beyoncé", null, null, 2, null));
        add("m/A/3.mp3", tags("c", "Beyoncé", "Lemonade ", "Beyoncé", null, null, 3, null));

        ViewNode albums = root(views(500), ViewKind.ALBUM);

        assertThat(albums.children()).hasSize(1);
        assertThat(albums.children().get(0).title()).isEqualTo("Lemonade");
        assertThat(albums.children().get(0).artist()).isEqualTo("Beyoncé");
        assertThat(albums.children().get(0).tracks()).hasSize(3);
    }

    @Test
    void missingAlbumAndArtistUseUnknownNames() {
        add("m/A/1.mp3", tags("lonely", null, null, null, null, null, null, null));
        add("m/A/2.mp3", tags("with artist", "Ann", null, null, null, null, null, null));
        Views v = views(500);

        assertThat(titles(root(v, ViewKind.ALBUM).children()))
                .containsExactly("Unknown Album (Ann)", "Unknown Album (Unknown Artist)");
        assertThat(titles(root(v, ViewKind.ARTIST).children())).containsExactly("Ann", "Unknown Artist");
        assertThat(titles(root(v, ViewKind.GENRE).children())).containsExactly("Unknown Genre");
    }

    @Test
    void artistsUseAlbumArtistFallbackTrackArtistSoCompilationsDoNotExplode() {
        add("m/A/1.mp3", tags("t1", "Guest One", "Compilation", "Various", null, 2001, 1, null));
        add("m/A/2.mp3", tags("t2", "Guest Two", "Compilation", "Various", null, 2001, 2, null));
        add("m/B/1.mp3", tags("t3", "Solo Act", "Single", null, null, 2010, 1, null));

        ViewNode artists = root(views(500), ViewKind.ARTIST);

        assertThat(titles(artists.children())).containsExactly("Solo Act", "Various");
    }

    @Test
    void artistLevelsAreAlbumsByYearThenAllTracks() {
        add("m/A/1.mp3", tags("n1", "Ann", "New", "Ann", null, 2020, 1, null));
        add("m/A/2.mp3", tags("o1", "Ann", "Old", "Ann", null, 1990, 1, null));
        add("m/A/3.mp3", tags("o2", "Ann", "Old", "Ann", null, 1990, 2, null));
        add("m/A/4.mp3", tags("u1", "Ann", "Alpha undated", "Ann", null, null, 1, null));
        add("m/B/1.mp3", tags("x", "Bob", "Other", "Bob", null, 2000, 1, null));

        ViewNode artists = root(views(500), ViewKind.ARTIST);
        ViewNode ann = artists.children().get(0);

        assertThat(ann.type()).isEqualTo(ViewNode.Type.ARTIST);
        assertThat(titles(ann.children())).containsExactly("Old", "New", "Alpha undated", "All tracks");
        ViewNode all = ann.children().get(3);
        assertThat(all.type()).isEqualTo(ViewNode.Type.ALL_TRACKS);
        assertThat(titlesOf(all.tracks())).containsExactly("o1", "o2", "n1", "u1");
        assertThat(all.parentId()).isEqualTo(ann.id());
        assertThat(ann.children().get(0).id()).startsWith(ann.id() + ".");
    }

    @Test
    void multiValueTagsSplitIntoSeveralArtistsAndGenresButFreeTextDoesNot() {
        TrackTags duet = new TrackTags("Duet", "Ann", "Together", null, "Rock", null, 1, null, null,
                List.of("Ann", "Bob"), null, List.of("Rock", "Pop"));
        add("m/A/1.mp3", duet);
        add("m/A/2.mp3", tags("Feat", "Cy feat. Dan & Eve", "Free", null, "Pop; Soul", null, 1, null));
        Views v = views(500);

        assertThat(titles(root(v, ViewKind.ARTIST).children())).containsExactly("Ann", "Bob", "Cy feat. Dan & Eve");
        assertThat(titles(root(v, ViewKind.GENRE).children())).containsExactly("Pop", "Pop; Soul", "Rock");
        ViewNode bob = root(v, ViewKind.ARTIST).children().get(1);
        assertThat(titlesOf(bob.children().get(1).tracks())).containsExactly("Duet");
    }

    @Test
    void genresListAlbumsThenTracks() {
        add("m/A/1.mp3", tags("r1", "Ann", "Blue", "Ann", "Rock", 1999, 1, null));
        add("m/A/2.mp3", tags("r2", "Ann", "Blue", "Ann", "rock", 1999, 2, null));
        add("m/B/1.mp3", tags("p1", "Bob", "Dance", "Bob", "Pop", 2005, 1, null));

        ViewNode genres = root(views(500), ViewKind.GENRE);

        assertThat(titles(genres.children())).containsExactly("Pop", "Rock");
        ViewNode rock = genres.children().get(1);
        assertThat(rock.type()).isEqualTo(ViewNode.Type.GENRE);
        assertThat(titles(rock.children())).containsExactly("Blue");
        assertThat(titlesOf(rock.children().get(0).tracks())).containsExactly("r1", "r2");
    }

    @Test
    void albumOverlayOfTheDirectoryIsAppliedToTheViews() {
        Folder overlaid = folder("m/A", new DirectorySettings(null, null,
                new AlbumInfo("Overlay Album", "Overlay Artist", 1980, "Jazz", null, null), 0, null));
        folders.set(1, overlaid);
        add("m/A/1.mp3", tags("t", "Tag Artist", "Tag Album", "Tag AA", "Rock", 1999, 1, null));
        Views v = views(500);

        assertThat(titles(root(v, ViewKind.ALBUM).children())).containsExactly("Overlay Album");
        assertThat(titles(root(v, ViewKind.ARTIST).children())).containsExactly("Overlay Artist");
        assertThat(titles(root(v, ViewKind.GENRE).children())).containsExactly("Jazz");
    }

    @Test
    void titlesAreSortedByTitleAndBucketedAboveTheThreshold() {
        add("m/A/1.mp3", tags("banana", null, null, null, null, null, null, null));
        add("m/A/2.mp3", tags("Apple", null, null, null, null, null, null, null));
        add("m/A/3.mp3", tags("Avocado", null, null, null, null, null, null, null));
        add("m/A/4.mp3", tags("7 rings", null, null, null, null, null, null, null));
        add("m/A/5.mp3", tags("Élan", null, null, null, null, null, null, null));

        Views flat = views(5);
        assertThat(titlesOf(root(flat, ViewKind.TITLE).tracks()))
                .containsExactly("7 rings", "Apple", "Avocado", "banana", "Élan");

        Views bucketed = views(4);
        ViewNode titles = root(bucketed, ViewKind.TITLE);
        assertThat(titles.tracks()).isEmpty();
        assertThat(titles(titles.children())).containsExactly("A", "B", "E", "#");
        assertThat(titlesOf(titles.children().get(0).tracks())).containsExactly("Apple", "Avocado");
        assertThat(titles.children().get(0).type()).isEqualTo(ViewNode.Type.BUCKET);
        assertThat(titles.children().get(3).id()).endsWith(".b0");
    }

    @Test
    void artistsAreBucketedAboveTheThreshold() {
        add("m/A/1.mp3", tags("t1", "Adele", "A", "Adele", null, null, 1, null));
        add("m/A/2.mp3", tags("t2", "Abba", "B", "Abba", null, null, 1, null));
        add("m/A/3.mp3", tags("t3", "Bob", "C", "Bob", null, null, 1, null));
        add("m/A/4.mp3", tags("t4", "50 Cent", "D", "50 Cent", null, null, 1, null));

        ViewNode artists = root(views(3), ViewKind.ARTIST);

        assertThat(titles(artists.children())).containsExactly("A", "B", "#");
        ViewNode a = artists.children().get(0);
        assertThat(titles(a.children())).containsExactly("Abba", "Adele");
        assertThat(a.children().get(0).parentId()).isEqualTo(a.id());
        assertThat(a.parentId()).isEqualTo(artists.id());
    }

    @Test
    void nodesAreFoundByIdAtEveryLevelAndNavigationFollowsTheLeafOrder() {
        add("m/A/1.mp3", tags("one", "Ann", "Blue", "Ann", "Rock", 1999, 1, null));
        Track two = add("m/A/2.mp3", tags("two", "Ann", "Blue", "Ann", "Rock", 1999, 2, null));
        Track one = tracks.get(0);
        Views v = views(500);
        ViewNode album = root(v, ViewKind.ALBUM).children().get(0);
        ViewNode annAlbum = root(v, ViewKind.ARTIST).children().get(0).children().get(0);

        assertThat(v.node(catalog.get(), album.id())).contains(album);
        assertThat(v.node(catalog.get(), annAlbum.id())).isPresent();
        assertThat(v.node(catalog.get(), "v." + root.id() + ".al.nonexistent")).isEmpty();
        assertThat(v.node(catalog.get(), "v.zzz.al")).isEmpty();
        assertThat(v.node(catalog.get(), "v." + root.id() + ".xx")).isEmpty();
        assertThat(v.node(catalog.get(), "garbage")).isEmpty();
        assertThat(v.knows(album.id())).isTrue();
        assertThat(v.knows("v.x")).isFalse();
        assertThat(v.next(album.id(), one.id())).contains(two);
        assertThat(v.next(album.id(), two.id())).isEmpty();
        assertThat(v.next(annAlbum.id(), one.id())).contains(two);
    }

    @Test
    void rootsFollowTheSettingsOrderAndOnlyEnabledViews() {
        add("m/A/1.mp3", tags("t", "Ann", "Blue", "Ann", "Rock", 1999, 1, null));
        Views v = views(500);

        assertThat(titles(v.roots(catalog.get(), root))).containsExactly("[Titles]", "[Albums]", "[Artists]", "[Genres]");
        assertThat(v.roots(catalog.get(), catalog.get().folder(folders.get(1).ref()).orElseThrow())).isEmpty();
    }

    @Test
    void treesAreMemoizedAndRecomputedWhenTheSnapshotIsSwapped() {
        add("m/A/1.mp3", tags("t", "Ann", "Blue", "Ann", "Rock", 1999, 1, null));
        Views v = views(500);

        ViewNode first = root(v, ViewKind.ALBUM);
        assertThat(root(v, ViewKind.ALBUM)).isSameAs(first);

        add("m/A/2.mp3", tags("t2", "Bob", "Red", "Bob", "Rock", 1999, 1, null));
        catalog.set(Fixtures.snapshot(folders, tracks));

        ViewNode second = root(v, ViewKind.ALBUM);
        assertThat(second).isNotSameAs(first);
        assertThat(titles(second.children())).containsExactly("Blue", "Red");
    }

    @Test
    void idsAreStableAcrossRebuilds() {
        add("m/A/1.mp3", tags("t", "Ann", "Blue", "Ann", "Rock", 1999, 1, null));
        String id1 = root(views(500), ViewKind.ALBUM).children().get(0).id();
        String id2 = root(views(500), ViewKind.ALBUM).children().get(0).id();
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void subtreeOfADirectoryOnlyContainsItsOwnTracks() {
        add("m/A/1.mp3", tags("in A", "Ann", "Blue", "Ann", null, null, 1, null));
        add("m/B/1.mp3", tags("in B", "Bob", "Red", "Bob", null, null, 1, null));
        Views v = views(500);
        Folder a = catalog.get().folder(folders.get(1).ref()).orElseThrow();
        Folder withViews = new Folder(a.ref(), 0, new DirectorySettings(null, null, null, 0,
                List.of(ViewKind.TITLE)), null, false, false);
        folders.set(1, withViews);
        catalog.set(Fixtures.snapshot(folders, tracks));

        ViewNode titles = v.root(catalog.get(), withViews, ViewKind.TITLE).orElseThrow();

        assertThat(titlesOf(titles.tracks())).containsExactly("in A");
    }

    @Test
    void emptyPoolGivesEmptyViews() {
        Views v = views(500);
        for (ViewKind k : ViewKind.values()) {
            assertThat(root(v, k).childCount()).isZero();
        }
    }
}
