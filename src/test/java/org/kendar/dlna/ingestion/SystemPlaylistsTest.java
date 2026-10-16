package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.ResolvedPlaylist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.curation.CurationException;
import org.kendar.dlna.curation.PlaylistService;
import org.kendar.dlna.testutil.LibraryFixture;

class SystemPlaylistsTest {

    @TempDir
    Path tmp;

    @Test
    void parsesM3uAndPlsEntries() {
        assertThat(SystemPlaylists.parse("m3u", "#EXTM3U\n#EXTINF:1,x\n01 one.wav\r\n\r\nhttp://radio/stream\n..\\x\\b.mp3\n"))
                .containsExactly("01 one.wav", "..\\x\\b.mp3");
        assertThat(SystemPlaylists.parse("pls", "[playlist]\nFile1=a.mp3\nTitle1=A\nFile2 = b.mp3\nFile3=http://x/y\nNumberOfEntries=3\n"))
                .containsExactly("a.mp3", "b.mp3");
    }

    @Test
    void decodesUtf8WithBomAndFallsBackToWindows1252() {
        byte[] bom = "ï»¿".getBytes(StandardCharsets.ISO_8859_1);
        byte[] utf8 = "café".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + utf8.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(utf8, 0, withBom, bom.length, utf8.length);
        assertThat(SystemPlaylists.decode(withBom)).isEqualTo("café");
        assertThat(SystemPlaylists.decode("café".getBytes(Charset.forName("windows-1252")))).isEqualTo("café");
    }

    @Test
    void scanShowsOnlyExistingFilesAsReadOnlySystemPlaylists() throws IOException {
        LibraryFixture lib = new LibraryFixture(tmp);
        Path album = lib.music.resolve("Album");
        Files.writeString(album.resolve("mix.m3u8"), "#EXTM3U\n03 three.mp3\nmissing.mp3\n..\\Album\\01 one.wav\n"
                + lib.music.resolve("Album/02 two.flac").toAbsolutePath() + "\nhttp://radio/x\n");
        Files.writeString(lib.music.resolve("Other/empty.m3u"), "nothing.mp3\n");
        Files.writeString(lib.music.resolve("Other/set.pls"), "[playlist]\nFile1=../Album/04 four.mid\n");

        Snapshot snap = lib.scanner.scan(lib.roots, lib.playlists, 2);

        assertThat(snap.playlists()).extracting(ResolvedPlaylist::name).containsExactly("mix", "set");
        ResolvedPlaylist mix = snap.playlists().get(0);
        assertThat(mix.playlist().system()).isTrue();
        assertThat(mix.playlist().key()).startsWith(Playlist.SYSTEM_PREFIX);
        assertThat(mix.tracks()).extracting(t -> t.ref().qualified())
                .containsExactly("main/Album/03 three.mp3", "main/Album/01 one.wav", "main/Album/02 two.flac");
        assertThat(mix.brokenCount()).as("entries missing from disk or catalog are left out").isZero();
    }

    @Test
    void systemPlaylistsCannotBeEdited() throws IOException {
        LibraryFixture lib = new LibraryFixture(tmp);
        Files.writeString(lib.music.resolve("Album/mix.m3u"), "01 one.wav\n");
        lib.catalog.install(lib.scanner.scan(lib.roots, lib.playlists, 2), true);
        PlaylistService svc = new PlaylistService(lib.catalog, lib.playlists);
        String key = lib.catalog.current().playlists().get(0).playlist().key();

        assertThatThrownBy(() -> svc.rename(key, "x")).isInstanceOf(CurationException.class)
                .extracting(e -> ((CurationException) e).code()).isEqualTo(CurationException.NOT_EDITABLE);
        assertThatThrownBy(() -> svc.delete(key)).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> svc.add(key, List.of("main/Album/02 two.flac"))).isInstanceOf(CurationException.class);
        assertThat(Files.exists(lib.playlists.resolve(key + ".json"))).isFalse();
    }
}
