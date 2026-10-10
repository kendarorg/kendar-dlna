package org.kendar.dlna.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.util.List;

import org.junit.jupiter.api.Test;

class OverlayAndNamesTest {

    private static DirectorySettings withAlbum(AlbumInfo a) {
        return new DirectorySettings(null, null, a, 0, null);
    }

    @Test
    void folderTitleIsAlbumTitleElseDirectoryName() {
        assertThat(DisplayNames.folderTitle(folder("m/Dir"))).isEqualTo("Dir");
        assertThat(DisplayNames.folderTitle(folder("m"))).isEqualTo("m");
        assertThat(DisplayNames.folderTitle(
                folder("m/Dir", withAlbum(new AlbumInfo("Nice Album", null, null, null, null, null)))))
                .isEqualTo("Nice Album");
    }

    private static DirectorySettings named(String displayName, AlbumInfo a) {
        return new DirectorySettings(null, null, a, 0, null, 0, displayName);
    }

    @Test
    void displayNameBeatsAlbumTitleAndDirectoryNameAndGetsNoDiscSuffix() {
        AlbumInfo album = new AlbumInfo("Nice Album", null, null, null, 2, 3);
        assertThat(DisplayNames.folderTitle(folder("m/Dir", named("  My Name  ", album)))).isEqualTo("My Name");
        assertThat(DisplayNames.folderTitle(folder("m/Dir", named("My Name", null)))).isEqualTo("My Name");
        // a root directory too
        assertThat(DisplayNames.folderTitle(folder("m", named("Music", null)))).isEqualTo("Music");
    }

    @Test
    void blankDisplayNameIsUnset() {
        AlbumInfo album = new AlbumInfo("Nice Album", null, null, null, 2, null);
        assertThat(named("   ", album).displayName()).isNull();
        assertThat(DisplayNames.folderTitle(folder("m/Dir", named("   ", album)))).isEqualTo("Nice Album (CD 2)");
        assertThat(DisplayNames.folderTitle(folder("m/Dir", named("", null)))).isEqualTo("Dir");
        assertThat(DisplayNames.folderTitle(folder("m/Dir", named(null, null)))).isEqualTo("Dir");
    }

    @Test
    void folderTitleIncludesTheDiscNumberWhenSet() {
        assertThat(DisplayNames.folderTitle(
                folder("m/Dir", withAlbum(new AlbumInfo("Nice Album", null, null, null, 2, 3)))))
                .isEqualTo("Nice Album (CD 2)");
        assertThat(DisplayNames.folderTitle(
                folder("m/Dir", withAlbum(new AlbumInfo(null, null, null, null, 1, null)))))
                .isEqualTo("Dir (CD 1)");
        // no disc number: unchanged
        assertThat(DisplayNames.folderTitle(
                folder("m/Dir", withAlbum(new AlbumInfo("Nice Album", null, null, null, null, 3)))))
                .isEqualTo("Nice Album");
    }

    @Test
    void folderTitleDoesNotRepeatADiscAlreadyNamed() {
        assertThat(DisplayNames.folderTitle(
                folder("m/CD2", withAlbum(new AlbumInfo(null, null, null, null, 2, null)))))
                .isEqualTo("CD2");
        assertThat(DisplayNames.folderTitle(
                folder("m/Dir", withAlbum(new AlbumInfo("Live - Disc 02", null, null, null, 2, null)))))
                .isEqualTo("Live - Disc 02");
        // a different disc number in the name is not a match
        assertThat(DisplayNames.folderTitle(
                folder("m/CD1", withAlbum(new AlbumInfo(null, null, null, null, 2, null)))))
                .isEqualTo("CD1 (CD 2)");
        assertThat(DisplayNames.folderTitle(
                folder("m/CD12", withAlbum(new AlbumInfo(null, null, null, null, 2, null)))))
                .isEqualTo("CD12 (CD 2)");
    }

    @Test
    void trackTitleIsTagElseFileNameWithoutExtension() {
        assertThat(DisplayNames.trackTitle(track("m/01 Song.flac"))).isEqualTo("01 Song");
        TrackTags t = new TrackTags(" Real Title ", null, null, null, null, null, null, null, null);
        assertThat(DisplayNames.trackTitle(track("m/x.mp3", t, 0))).isEqualTo("Real Title");
    }

    @Test
    void overlayWinsForAlbumFieldsButNotTrackFields() {
        TrackTags tags = new TrackTags("Song", "Artist", "Tag Album", "Tag AA", "Rock", 1999, 3, 1, 2);
        Track tr = track("m/a.mp3", tags, 0);
        DirectorySettings s = withAlbum(new AlbumInfo("Overlay", null, 2001, "Jazz", null, null));

        EffectiveTrack e = EffectiveTrack.of(tr, s);

        assertThat(e.album()).isEqualTo("Overlay");
        assertThat(e.albumArtist()).isEqualTo("Tag AA");
        assertThat(e.year()).isEqualTo(2001);
        assertThat(e.genre()).isEqualTo("Jazz");
        assertThat(e.discNo()).isEqualTo(1);
        assertThat(e.track().tags().title()).isEqualTo("Song");
    }

    @Test
    void outOfSyncDetectsDifferencesIgnoringCaseAndAccents() {
        TrackTags tags = new TrackTags("S", "A", "Café", null, null, 2000, 1, null, null);
        List<Track> tracks = List.of(track("m/a.mp3", tags, 0));

        assertThat(EffectiveTrack.outOfSync(withAlbum(new AlbumInfo("CAFE", null, 2000, null, null, null)), tracks))
                .isFalse();
        assertThat(EffectiveTrack.outOfSync(withAlbum(new AlbumInfo("Other", null, null, null, null, null)), tracks))
                .isTrue();
        assertThat(EffectiveTrack.outOfSync(withAlbum(new AlbumInfo(null, null, 1999, null, null, null)), tracks))
                .isTrue();
        assertThat(EffectiveTrack.outOfSync(DirectorySettings.DEFAULT, tracks)).isFalse();
    }

    @Test
    void formatByExtensionIsCaseInsensitive() {
        assertThat(Format.fromFileName("A.MP3")).contains(Format.MP3);
        assertThat(Format.fromFileName("a.aif")).contains(Format.AIFF);
        assertThat(Format.fromFileName("a.opus")).contains(Format.OPUS);
        assertThat(Format.fromFileName("a.jpg")).isEmpty();
        assertThat(Format.fromFileName("noext")).isEmpty();
    }
}
