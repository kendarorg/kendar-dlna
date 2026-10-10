package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Snapshot;

class PlaylistParserTest {

    @TempDir
    Path dir;

    @Test
    void parsesNameAndOrderedSongs() {
        PlaylistParser.Result r = PlaylistParser.parse("trip",
                "{\"name\":\" Road trip \",\"songs\":[\"main/a.mp3\",\" main/b/c.flac \"]}");

        assertThat(r.error()).isNull();
        assertThat(r.playlist().name()).isEqualTo("Road trip");
        assertThat(r.playlist().songs()).containsExactly("main/a.mp3", "main/b/c.flac");
        assertThat(r.playlist().key()).isEqualTo("trip");
    }

    @Test
    void nameFallsBackToFileKeyAndSongsAreOptional() {
        Playlist p = PlaylistParser.parse("evening", "{}").playlist();
        assertThat(p.name()).isEqualTo("evening");
        assertThat(p.songs()).isEmpty();
    }

    @Test
    void rejectsBrokenPlaylistsWithoutThrowing() {
        assertThat(PlaylistParser.parse("x", "{broken").error()).contains("malformed");
        assertThat(PlaylistParser.parse("x", "[1,2]").error()).contains("object");
        assertThat(PlaylistParser.parse("x", "{\"songs\":\"a\"}").error()).contains("array");
        assertThat(PlaylistParser.parse("x", "{\"songs\":[1]}").error()).contains("strings");
        assertThat(PlaylistParser.parse("x", "{\"songs\":[\" \"]}").playlist()).isNull();
    }

    @Test
    void keyIsFileNameWithoutJsonExtension() {
        assertThat(PlaylistParser.keyOf("Mix.json")).isEqualTo("Mix");
        assertThat(PlaylistParser.keyOf("Mix.JSON")).isEqualTo("Mix");
        assertThat(PlaylistParser.keyOf("Mix")).isEqualTo("Mix");
    }

    @Test
    void scannerLoadsPlaylistsSortedByNameAndSkipsBrokenOnes() throws Exception {
        Files.writeString(dir.resolve("b.json"), "{\"name\":\"Zeta\",\"songs\":[\"m/a.mp3\"]}");
        Files.writeString(dir.resolve("a.json"), "{\"name\":\"alpha 2\"}");
        Files.writeString(dir.resolve("c.json"), "{\"name\":\"alpha 10\"}");
        Files.writeString(dir.resolve("bad.json"), "{nope");
        Files.writeString(dir.resolve(".hidden.json"), "{}");
        Files.writeString(dir.resolve("readme.txt"), "ignored");

        List<Playlist> list = LibraryScanner.loadPlaylists(dir);

        assertThat(list).extracting(Playlist::name).containsExactly("alpha 2", "alpha 10", "Zeta");
    }

    @Test
    void missingPlaylistDirectoryMeansNoPlaylists() {
        assertThat(LibraryScanner.loadPlaylists(dir.resolve("nope"))).isEmpty();
        assertThat(LibraryScanner.loadPlaylists(null)).isEmpty();
    }

    @Test
    void scanPutsPlaylistsIntoTheSnapshot() throws Exception {
        Path music = dir.resolve("music");
        Files.createDirectories(music);
        Path pl = dir.resolve("pl");
        Files.createDirectories(pl);
        Files.writeString(pl.resolve("one.json"), "{\"songs\":[\"main/none.mp3\"]}");

        Snapshot s = new LibraryScanner(new JAudioTaggerReader()).scan(java.util.Map.of("main", music), pl, 5);

        assertThat(s.playlists()).hasSize(1);
        assertThat(s.playlists().get(0).entries()).hasSize(1);
        assertThat(s.playlists().get(0).tracks()).isEmpty();
    }
}
