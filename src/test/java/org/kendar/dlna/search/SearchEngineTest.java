package org.kendar.dlna.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.search.Query.Op;
import org.kendar.dlna.shared.RootRef;

class SearchEngineTest {

    private static TrackTags tags(String title, String artist, String album, String albumArtist, String genre) {
        return new TrackTags(title, artist, album, albumArtist, genre, null, null, null, null);
    }

    private final Track cafe = track("music/Jazz/Cafe del Mar.mp3", tags("Café del Mar", "Énergie", "Chill", null, "Ambient"), 0);
    private final Track blue = track("music/Jazz/Blue.flac", tags("Blue in Green", "Miles Davis", "Kind of Blue",
            "Miles Davis", "Jazz"), 0);
    private final Track untagged = track("music/Jazz/Live/untagged take.ogg");
    private final Track rock = track("music/Rock/song.mp3", tags("Thunder", "AC/DC", "Back in Black", null, "Rock"), 0);
    private final Track other = track("other/Misc/Thunderstruck.mp3", tags(null, null, null, null, null), 0);

    private final Snapshot snap = snapshot(List.of(
            folder("music"),
            folder("music/Jazz", new DirectorySettings(null, null,
                    new AlbumInfo("Jazz Overlay", "Various Overlay", null, "Overlay Genre", null, null), 0, null)),
            folder("music/Jazz/Live"), folder("music/Rock"), folder("other"), folder("other/Misc")),
            List.of(cafe, blue, untagged, rock, other));

    private List<String> names(Query q, SearchScope scope) {
        return SearchEngine.search(snap, scope, q).stream().map(h -> switch (h) {
            case SearchHit.TrackHit t -> "T:" + t.track().ref().qualified();
            case SearchHit.FolderHit f -> "F:" + f.folder().ref().qualified();
        }).toList();
    }

    private List<String> names(Query q) {
        return names(q, SearchScope.LIBRARY);
    }

    private static Query.Term term(Field f, Op op, String v) {
        return new Query.Term(f, op, v);
    }

    @Test
    void normalizationIgnoresCaseAndAccentsBothWays() {
        assertThat(names(term(Field.TITLE, Op.CONTAINS, "CAFE"))).containsExactly("T:music/Jazz/Cafe del Mar.mp3");
        assertThat(names(term(Field.ARTIST, Op.CONTAINS, "energie"))).containsExactly("T:music/Jazz/Cafe del Mar.mp3");
        assertThat(names(term(Field.ARTIST, Op.EQUALS, "ÉNERGIE"))).containsExactly("T:music/Jazz/Cafe del Mar.mp3");
        assertThat(names(term(Field.TITLE, Op.CONTAINS, "  del   mar "))).hasSize(1);
    }

    @Test
    void titleMatchesTagTitleOrFileName() {
        // "untagged take" has no tag title: found through its file name; "Thunderstruck" likewise
        assertThat(names(term(Field.TITLE, Op.CONTAINS, "untagged"))).containsExactly("T:music/Jazz/Live/untagged take.ogg");
        assertThat(names(term(Field.TITLE, Op.CONTAINS, "thunder")))
                .containsExactly("T:music/Rock/song.mp3", "T:other/Misc/Thunderstruck.mp3");
        // tagged track also found through its file name
        assertThat(names(term(Field.TITLE, Op.CONTAINS, "song"))).containsExactly("T:music/Rock/song.mp3");
    }

    @Test
    void titleMatchesContainersByTitleOrDirectoryName() {
        // Jazz has an album title overlay "Jazz Overlay": both the overlay and the directory name are searchable
        assertThat(names(term(Field.TITLE, Op.CONTAINS, "overlay"))).containsExactly("F:music/Jazz");
        List<String> jazz = names(term(Field.TITLE, Op.EQUALS, "jazz"));
        assertThat(jazz).containsExactly("F:music/Jazz");
        assertThat(names(new Query.Kinds(EnumSet.of(Kind.FOLDER)))).containsExactly("F:music", "F:music/Jazz",
                "F:music/Jazz/Live", "F:music/Rock", "F:other", "F:other/Misc");
    }

    @Test
    void titleMatchesContainersByDisplayNameRealNameAndAlbumTitle() {
        Snapshot s = snapshot(List.of(folder("music"),
                folder("music/Rock", new DirectorySettings(null, null,
                        new AlbumInfo("Rock Album", null, null, null, null, null), 0, null, 0, "Loud Stuff")),
                folder("music/Pop")), List.of());
        List<String> byDisplay = SearchEngine.search(s, SearchScope.LIBRARY, term(Field.TITLE, Op.CONTAINS, "loud"))
                .stream().map(h -> ((SearchHit.FolderHit) h).folder().ref().qualified()).toList();
        assertThat(byDisplay).containsExactly("music/Rock");
        for (String q : new String[] { "rock", "album" }) {   // real name and album title still find it
            assertThat(SearchEngine.search(s, SearchScope.LIBRARY, term(Field.TITLE, Op.CONTAINS, q)).stream()
                    .map(h -> ((SearchHit.FolderHit) h).folder().ref().qualified()).toList())
                    .containsExactly("music/Rock");
        }
        assertThat(SearchEngine.search(s, SearchScope.LIBRARY, term(Field.DIRECTORY, Op.EQUALS, "rock"))).hasSize(1);
        assertThat(SearchEngine.search(s, SearchScope.LIBRARY, term(Field.DIRECTORY, Op.CONTAINS, "loud"))).isEmpty();
    }

    @Test
    void artistAlbumGenreFieldsOnlyMatchTracks() {
        assertThat(names(term(Field.ARTIST, Op.CONTAINS, "miles"))).containsExactly("T:music/Jazz/Blue.flac");
        assertThat(names(term(Field.ALBUM, Op.CONTAINS, "black"))).containsExactly("T:music/Rock/song.mp3");
        assertThat(names(term(Field.GENRE, Op.EQUALS, "rock"))).containsExactly("T:music/Rock/song.mp3");
        assertThat(names(term(Field.ARTIST, Op.CONTAINS, ""))).allMatch(n -> n.startsWith("T:"));
    }

    @Test
    void albumOverlayIsAppliedToAlbumLevelFields() {
        // all Jazz-directory tracks get the overlay album / album artist / genre; tags no longer match
        assertThat(names(term(Field.ALBUM, Op.CONTAINS, "jazz overlay"))).containsExactly(
                "T:music/Jazz/Blue.flac", "T:music/Jazz/Cafe del Mar.mp3");
        assertThat(names(term(Field.ALBUM, Op.CONTAINS, "kind of blue"))).isEmpty();
        assertThat(names(term(Field.GENRE, Op.EQUALS, "overlay genre"))).hasSize(2);
        assertThat(names(term(Field.GENRE, Op.EQUALS, "ambient"))).isEmpty();
        assertThat(names(term(Field.ALBUM_ARTIST, Op.EQUALS, "various overlay"))).hasSize(2);
        // the overlay does not apply to a subdirectory (no inheritance)
        assertThat(names(term(Field.ALBUM, Op.CONTAINS, "overlay")))
                .doesNotContain("T:music/Jazz/Live/untagged take.ogg");
    }

    @Test
    void artistFallsBackToAlbumArtist() {
        assertThat(names(term(Field.ARTIST, Op.CONTAINS, "various overlay"))).containsExactly(
                "T:music/Jazz/Blue.flac", "T:music/Jazz/Cafe del Mar.mp3");
    }

    @Test
    void fileNameAndDirectoryNameFields() {
        assertThat(names(term(Field.FILENAME, Op.CONTAINS, ".flac"))).containsExactly("T:music/Jazz/Blue.flac");
        assertThat(names(term(Field.DIRECTORY, Op.EQUALS, "live")))
                .containsExactly("F:music/Jazz/Live", "T:music/Jazz/Live/untagged take.ogg");
    }

    @Test
    void anyFieldAndFreeText() {
        assertThat(names(Query.text("MILES blue"))).containsExactly("T:music/Jazz/Blue.flac");
        assertThat(names(Query.text("   "))).hasSize(11);   // 6 folders + 5 tracks
        assertThat(names(Query.text("nothing like this"))).isEmpty();
    }

    @Test
    void scopeRestrictsToSubtreeAndExcludesTheContainerItself() {
        RootRef jazz = RootRef.parse("music/Jazz");
        assertThat(names(new Query.All(), SearchScope.below(jazz))).containsExactly(
                "F:music/Jazz/Live", "T:music/Jazz/Live/untagged take.ogg", "T:music/Jazz/Blue.flac",
                "T:music/Jazz/Cafe del Mar.mp3");
        assertThat(names(term(Field.TITLE, Op.CONTAINS, "thunder"), SearchScope.below(RootRef.parse("other"))))
                .containsExactly("T:other/Misc/Thunderstruck.mp3");
        assertThat(names(term(Field.TITLE, Op.CONTAINS, "thunder"), SearchScope.below(jazz))).isEmpty();
        assertThat(names(new Query.All(), SearchScope.below(RootRef.parse("nope")))).isEmpty();
    }

    @Test
    void resultsAreInStableDisplayOrderDirectoriesBeforeTheirContent() {
        List<String> all = names(new Query.All());
        assertThat(all).isEqualTo(names(new Query.All()));
        assertThat(all.indexOf("F:music/Jazz")).isLessThan(all.indexOf("T:music/Jazz/Blue.flac"));
        assertThat(all.indexOf("F:music")).isZero();
    }

    @Test
    void kindsRestrictResults() {
        Query tracksNamedJazz = new Query.And(List.of(new Query.Kinds(EnumSet.of(Kind.TRACK)),
                term(Field.TITLE, Op.CONTAINS, "jazz")));
        assertThat(names(tracksNamedJazz)).isEmpty();
        Query anyKind = new Query.And(List.of(new Query.Kinds(EnumSet.allOf(Kind.class)),
                term(Field.TITLE, Op.CONTAINS, "jazz")));
        assertThat(names(anyKind)).containsExactly("F:music/Jazz");
        assertThat(names(new Query.Kinds(EnumSet.noneOf(Kind.class)))).isEmpty();
    }

    @Test
    void orAndExistsAndNegativeOperators() {
        assertThat(names(new Query.Or(List.of(term(Field.GENRE, Op.EQUALS, "rock"),
                term(Field.ARTIST, Op.CONTAINS, "miles"))))).containsExactly("T:music/Jazz/Blue.flac",
                        "T:music/Rock/song.mp3");
        assertThat(names(new Query.Exists(Field.GENRE, false))).contains("T:music/Jazz/Live/untagged take.ogg",
                "F:music").doesNotContain("T:music/Rock/song.mp3");
        assertThat(names(new Query.Exists(Field.GENRE, true))).containsExactly("T:music/Jazz/Blue.flac",
                "T:music/Jazz/Cafe del Mar.mp3", "T:music/Rock/song.mp3");
        // != requires the field to be present: untagged tracks and all folders do not match
        assertThat(names(term(Field.GENRE, Op.NOT_EQUALS, "rock"))).containsExactly("T:music/Jazz/Blue.flac",
                "T:music/Jazz/Cafe del Mar.mp3");
        assertThat(names(term(Field.TITLE, Op.NOT_CONTAINS, "thunder"))).doesNotContain("T:music/Rock/song.mp3",
                "T:other/Misc/Thunderstruck.mp3").contains("F:music");
        assertThat(names(term(Field.TITLE, Op.STARTS_WITH, "blue"))).containsExactly("T:music/Jazz/Blue.flac");
        assertThat(names(new Query.None())).isEmpty();
    }

    @Test
    void emptySnapshotYieldsNothing() {
        assertThat(SearchEngine.search(Snapshot.empty(), SearchScope.LIBRARY, new Query.All())).isEmpty();
    }
}
