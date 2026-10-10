package org.kendar.dlna.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.ContainerKeys;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.ResolvedPlaylist;
import org.kendar.dlna.ingestion.PlaylistParser;
import org.kendar.dlna.testutil.LibraryFixture;

class PlaylistServiceTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    PlaylistService playlists;

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        playlists = new PlaylistService(lib.catalog, lib.playlists);
    }

    private ResolvedPlaylist resolved(String key) {
        return lib.catalog.current().playlists().stream().filter(p -> p.playlist().key().equals(key)).findFirst()
                .orElseThrow();
    }

    @Test
    void createWritesTheFileAndShowsUpInTheCatalogImmediately() throws IOException {
        long before = lib.catalog.current().containerUpdateId(ContainerKeys.PLAYLISTS);
        Playlist p = playlists.create("  Road Trip: Ünïcode! ");

        assertThat(p.key()).isEqualTo("road-trip-unicode");
        assertThat(p.name()).isEqualTo("Road Trip: Ünïcode!");
        Path file = lib.playlists.resolve("road-trip-unicode.json");
        assertThat(file).exists();
        PlaylistParser.Result onDisk = PlaylistParser.parseFile(file);
        assertThat(onDisk.error()).isNull();
        assertThat(onDisk.playlist().name()).isEqualTo("Road Trip: Ünïcode!");
        assertThat(resolved("road-trip-unicode").entries()).isEmpty();
        assertThat(lib.catalog.current().containerUpdateId(ContainerKeys.PLAYLISTS)).isGreaterThan(before);
        assertThat(lib.store.saves).isEqualTo(1);
        // and a rescan reads the same playlist back
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        assertThat(lib.catalog.current().playlists()).hasSize(1);
    }

    @Test
    void keysStayUniqueAndNamesAreValidated() {
        assertThat(playlists.create("Mix").key()).isEqualTo("mix");
        assertThat(playlists.create("Mix").key()).isEqualTo("mix-2");
        assertThat(playlists.create("???").key()).isEqualTo("playlist");
        assertThatThrownBy(() -> playlists.create("  ")).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> playlists.create("a\nb")).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> playlists.create("x".repeat(201))).isInstanceOf(CurationException.class);
    }

    @Test
    void addAppendsKnownSongsOnly() {
        playlists.create("Mix");
        Playlist p = playlists.add("mix", List.of("main/Album/01 one.wav", "main/Album/02 two.flac"));
        assertThat(p.songs()).containsExactly("main/Album/01 one.wav", "main/Album/02 two.flac");
        assertThat(resolved("mix").tracks()).hasSize(2);
        assertThatThrownBy(() -> playlists.add("mix", List.of("main/Album/missing.mp3")))
                .isInstanceOf(CurationException.class).extracting("code").isEqualTo(CurationException.NOT_FOUND);
        assertThatThrownBy(() -> playlists.add("mix", List.of("main/../../etc/passwd")))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> playlists.add("mix", List.of())).isInstanceOf(CurationException.class);
        assertThat(resolved("mix").entries()).hasSize(2);
    }

    @Test
    void reorderAndRemoveReplaceTheSongList() throws IOException {
        playlists.create("Mix");
        playlists.add("mix", List.of("main/Album/01 one.wav", "main/Album/02 two.flac", "main/Album/03 three.mp3"));
        playlists.replaceSongs("mix", List.of("main/Album/03 three.mp3", "main/Album/01 one.wav"));

        assertThat(resolved("mix").tracks()).extracting(t -> t.fileName()).containsExactly("03 three.mp3",
                "01 one.wav");
        String json = Files.readString(lib.playlists.resolve("mix.json"), StandardCharsets.UTF_8);
        assertThat(json.indexOf("03 three")).isLessThan(json.indexOf("01 one"));
        assertThat(json).doesNotContain("two.flac");
    }

    @Test
    void brokenEntriesSurviveEditsAndCanBeFixed() {
        playlists.create("Mix");
        playlists.add("mix", List.of("main/Album/01 one.wav"));
        // an entry whose file moved: allowed in a replaced list (it was already there)
        playlists.replaceSongs("mix", List.of("main/Album/old-name.wav", "main/Album/01 one.wav"));
        assertThat(resolved("mix").brokenCount()).isEqualTo(1);
        assertThat(resolved("mix").entries().get(0).status()).isEqualTo(ResolvedPlaylist.Status.MISSING);

        Playlist fixed = playlists.fixPath("mix", 0, "main/Album/02 two.flac");

        assertThat(fixed.songs()).containsExactly("main/Album/02 two.flac", "main/Album/01 one.wav");
        assertThat(resolved("mix").brokenCount()).isZero();
        assertThatThrownBy(() -> playlists.fixPath("mix", 5, "main/Album/02 two.flac"))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> playlists.fixPath("mix", 0, "main/Album/none.flac"))
                .isInstanceOf(CurationException.class);
    }

    @Test
    void renameKeepsTheKeyAndFile() {
        playlists.create("Mix");
        Playlist p = playlists.rename("mix", "Better Mix");
        assertThat(p.key()).isEqualTo("mix");
        assertThat(resolved("mix").name()).isEqualTo("Better Mix");
        assertThat(PlaylistParser.parseFile(lib.playlists.resolve("mix.json")).playlist().name())
                .isEqualTo("Better Mix");
    }

    @Test
    void deleteRemovesFileAndCatalogEntry() {
        playlists.create("Mix");
        playlists.delete("mix");
        assertThat(lib.playlists.resolve("mix.json")).doesNotExist();
        assertThat(lib.catalog.current().playlists()).isEmpty();
        assertThatThrownBy(() -> playlists.delete("mix")).isInstanceOf(CurationException.class)
                .extracting("code").isEqualTo(CurationException.NOT_FOUND);
    }

    @Test
    void unsafeKeysAreRefused() {
        assertThatThrownBy(() -> playlists.rename("../evil", "x")).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> playlists.delete("a/b")).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> playlists.add(".hidden", List.of("main/Album/01 one.wav")))
                .isInstanceOf(CurationException.class);
    }
}
