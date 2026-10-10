package org.kendar.dlna.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.ContainerKeys;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.SortMode;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.ingestion.SettingsParser;
import org.kendar.dlna.shared.Json;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.LibraryFixture;

import com.fasterxml.jackson.databind.JsonNode;

class FolderServiceTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    TagService tags;
    FolderService folders;
    final RootRef album = RootRef.parse("main/Album");

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
        folders = new FolderService(lib.catalog, lib.files, tags);
    }

    private static JsonNode json(String s) {
        try {
            return Json.mapper().readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Folder folder() {
        return lib.catalog.current().folder(album).orElseThrow();
    }

    // ---- settings ----

    @Test
    void keepWholeFilesIsSavedAndNotDroppedByClientsThatDoNotKnowIt() {
        folders.saveSettings(album, json("{\"keepWholeFiles\":true}"));
        assertThat(folder().settings().keepWholeFiles()).isTrue();

        folders.saveSettings(album, json("{\"sort\":\"title\"}"));
        assertThat(folder().settings().keepWholeFiles()).as("absent field keeps the stored value").isTrue();

        folders.saveSettings(album, json("{\"sort\":\"title\",\"keepWholeFiles\":false}"));
        assertThat(folder().settings().keepWholeFiles()).isFalse();
    }

    @Test
    void savesSettingsAtomicallyAndUpdatesTheCatalog() throws IOException {
        long idBefore = lib.catalog.current().updateId();
        folders.saveSettings(album, json("""
                {"sort":"explicit","order":["03 three.mp3","01 one.wav"],
                 "album":{"title":"My Album","year":1999,"albumArtist":"Me"},"random":5,"recent":12,"views":["genre","album","composer"]}"""));

        DirectorySettings s = folder().settings();
        assertThat(s.sort()).isEqualTo(SortMode.EXPLICIT);
        assertThat(s.order()).containsExactly("03 three.mp3", "01 one.wav");
        assertThat(s.album().title()).isEqualTo("My Album");
        assertThat(s.album().year()).isEqualTo(1999);
        assertThat(s.random()).isEqualTo(5);
        assertThat(s.recent()).isEqualTo(12);
        assertThat(s.views()).containsExactly(ViewKind.GENRE, ViewKind.ALBUM, ViewKind.COMPOSER);
        // the file on disk parses to the same settings
        SettingsParser.Result onDisk = SettingsParser.parseFile(lib.music.resolve("Album/settings.json"));
        assertThat(onDisk.error()).isNull();
        assertThat(onDisk.settings()).isEqualTo(s);
        // the explicit order is effective immediately in the catalog
        assertThat(lib.catalog.current().children(album).get(0).ref().name()).isEqualTo("03 three.mp3");
        assertThat(lib.catalog.current().updateId()).isGreaterThan(idBefore);
        assertThat(lib.catalog.current().containerUpdateId(folder().id())).isGreaterThan(0);
        assertThat(lib.store.saves).isEqualTo(1);
        assertThat(ContainerKeys.virtual(folder().id())).isNotNull();
        try (var files = Files.list(lib.music.resolve("Album"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith(AtomicFiles.TEMP_PREFIX))).isEmpty();
        }
    }

    @Test
    void keepsUnknownKeysOfAnExistingFileAndOmitsDefaults() throws IOException {
        Files.writeString(lib.music.resolve("Album/settings.json"), "{\"custom\": {\"a\": 1}, \"random\": 3}");
        folders.saveSettings(album, json("{\"sort\":\"title\",\"random\":0,\"recent\":0}"));
        JsonNode written = json(Files.readString(lib.music.resolve("Album/settings.json")));
        assertThat(written.get("custom").get("a").asInt()).isEqualTo(1);
        assertThat(written.has("random")).isFalse();
        assertThat(written.has("views")).isFalse();
        assertThat(written.has("recent")).isFalse();
        assertThat(written.get("sort").asText()).isEqualTo("title");
    }

    @Test
    void displayNameRoundTripsKeepsUnknownKeysAndIsOmittedWhenBlank() throws IOException {
        Files.writeString(lib.music.resolve("Album/settings.json"), "{\"custom\": 7}");
        folders.saveSettings(album, json("{\"displayName\":\"  Shown Name \"}"));

        assertThat(folder().settings().displayName()).isEqualTo("Shown Name");
        JsonNode written = json(Files.readString(lib.music.resolve("Album/settings.json")));
        assertThat(written.get("displayName").asText()).isEqualTo("Shown Name");
        assertThat(written.get("custom").asInt()).isEqualTo(7);
        assertThat(SettingsParser.parseFile(lib.music.resolve("Album/settings.json")).settings())
                .isEqualTo(folder().settings());

        folders.saveSettings(album, json("{\"displayName\":\"   \"}"));
        written = json(Files.readString(lib.music.resolve("Album/settings.json")));
        assertThat(written.has("displayName")).isFalse();
        assertThat(written.get("custom").asInt()).isEqualTo(7);
        assertThat(folder().settings().displayName()).isNull();
    }

    @Test
    void displayNameValidation() {
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"displayName\":5}")))
                .isInstanceOf(CurationException.class).hasMessageContaining("displayName");
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"displayName\":\"" + "x".repeat(256) + "\"}")))
                .isInstanceOf(CurationException.class).hasMessageContaining("255");
        folders.saveSettings(album, json("{\"displayName\":\"" + "x".repeat(255) + "\"}"));
        assertThat(folder().settings().displayName()).hasSize(255);
    }

    @Test
    void replacesAnInvalidSettingsFile() throws IOException {
        Files.writeString(lib.music.resolve("Album/settings.json"), "{ not json");
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        assertThat(folder().settingsError()).isNotNull();

        folders.saveSettings(album, json("{\"sort\":\"track\"}"));
        assertThat(folder().settingsError()).isNull();
        assertThat(folder().settings().sort()).isEqualTo(SortMode.TRACK);
    }

    @Test
    void rejectsInvalidSettingsAndWritesNothing() {
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"sort\":\"bogus\"}")))
                .isInstanceOf(CurationException.class).hasMessageContaining("invalid sort");
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"order\":[\"a/b\"]}")))
                .isInstanceOf(CurationException.class).hasMessageContaining("plain");
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"random\":-1}")))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"recent\":-1}")))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"recent\":1000000}")))
                .isInstanceOf(CurationException.class).hasMessageContaining("recent");
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"views\":[\"nope\"]}")))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> folders.saveSettings(album, json("{\"album\":{\"year\":99999}}")))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> folders.saveSettings(album, json("[1]"))).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> folders.saveSettings(RootRef.parse("main/Nope"), json("{}")))
                .isInstanceOf(CurationException.class).extracting("code").isEqualTo(CurationException.NOT_FOUND);
        assertThat(Files.exists(lib.music.resolve("Album/settings.json"))).isFalse();
        assertThat(lib.store.saves).isZero();
    }

    // ---- sync ----

    @Test
    void applyToTagsWritesTheAlbumFieldsIntoEditableTracksAndSkipsMidi() {
        folders.saveSettings(album, json("{\"album\":{\"title\":\"Synced\",\"albumArtist\":\"AA\",\"year\":2001,"
                + "\"genre\":\"Jazz\",\"discNumber\":1,\"discTotal\":2}}"));
        assertThat(folders.syncToTags(album).skipped()).containsExactly("04 four.mid");

        for (String f : new String[] { "01 one.wav", "02 two.flac", "03 three.mp3" }) {
            var t = lib.catalog.current().track(album.child(f)).orElseThrow().tags();
            assertThat(t.album()).as(f).isEqualTo("Synced");
            assertThat(t.albumArtist()).isEqualTo("AA");
            assertThat(t.year()).isEqualTo(2001);
            assertThat(t.genre()).isEqualTo("Jazz");
            assertThat(t.discNo()).isEqualTo(1);
        }
        // overlay and tags agree now: no longer out of sync
        var tracks = lib.catalog.current().subtreeTracks(album).stream()
                .filter(t -> t.format().editableFields().contains("album")).toList();
        assertThat(org.kendar.dlna.catalog.EffectiveTrack.outOfSync(folder().settings(), tracks)).isFalse();
    }

    @Test
    void applyToTagsWritesOnlyTheSelectedFiles() {
        folders.saveSettings(album, json("{\"album\":{\"title\":\"Synced\"}}"));

        FolderService.ToTagsOutcome o = folders.syncToTags(album, java.util.List.of("02 two.flac"));

        assertThat(o.results()).hasSize(1);
        assertThat(lib.catalog.current().track(album.child("02 two.flac")).orElseThrow().tags().album())
                .isEqualTo("Synced");
        assertThat(lib.catalog.current().track(album.child("01 one.wav")).orElseThrow().tags().album())
                .isNotEqualTo("Synced");
        assertThat(lib.catalog.current().track(album.child("03 three.mp3")).orElseThrow().tags().album())
                .isNotEqualTo("Synced");
    }

    @Test
    void applyToTagsRejectsAnEmptyOrUnknownSelection() {
        folders.saveSettings(album, json("{\"album\":{\"title\":\"Synced\"}}"));

        assertThatThrownBy(() -> folders.syncToTags(album, java.util.List.of())).isInstanceOf(CurationException.class)
                .hasMessageContaining("No files selected");
        assertThatThrownBy(() -> folders.syncToTags(album, java.util.List.of("missing.wav")))
                .isInstanceOf(CurationException.class).hasMessageContaining("missing.wav");
    }

    @Test
    void applyToTagsNeedsAlbumFields() {
        assertThatThrownBy(() -> folders.syncToTags(album)).isInstanceOf(CurationException.class)
                .hasMessageContaining("no album fields");
    }

    @Test
    void fromTagsFillsTheAlbumWhenTheTracksAgree() {
        tags.write(java.util.List.of(album.child("01 one.wav"), album.child("02 two.flac"),
                album.child("03 three.mp3")),
                new TagEdit(null, null, "Agreed", "Same Artist", "Pop", "2010", null, null, null));
        FolderService.FromTagsOutcome o = folders.syncFromTags(album);
        assertThat(o.ok()).isTrue();
        assertThat(o.settings().album().title()).isEqualTo("Agreed");
        assertThat(o.settings().album().albumArtist()).isEqualTo("Same Artist");
        assertThat(o.settings().album().year()).isEqualTo(2010);
        assertThat(o.settings().album().genre()).isEqualTo("Pop");
        assertThat(folder().settings().album().title()).isEqualTo("Agreed");
        assertThat(lib.music.resolve("Album/settings.json")).exists();
    }

    @Test
    void fromTagsPreviewReturnsTheAlbumButWritesNothing() {
        tags.write(java.util.List.of(album.child("01 one.wav"), album.child("02 two.flac"),
                album.child("03 three.mp3")),
                new TagEdit(null, null, "Agreed", "Same Artist", "Pop", "2010", null, null, null));
        int saves = lib.store.saves;

        FolderService.FromTagsOutcome o = folders.syncFromTags(album, false);

        assertThat(o.ok()).isTrue();
        assertThat(o.settings().album().title()).isEqualTo("Agreed");
        assertThat(o.settings().album().year()).isEqualTo(2010);
        assertThat(lib.music.resolve("Album/settings.json")).doesNotExist();
        assertThat(lib.store.saves).isEqualTo(saves);
        assertThat(folder().settings().album().isEmpty()).isTrue();
    }

    @Test
    void fromTagsReportsConflictsAndWritesNothing() {
        tags.writeOne(album.child("01 one.wav"), new TagEdit(null, null, "Album A", null, "Rock", null, null, null,
                null));
        tags.writeOne(album.child("02 two.flac"), new TagEdit(null, null, "Album B", null, "ROCK", null, null, null,
                null));
        tags.writeOne(album.child("03 three.mp3"), new TagEdit(null, null, "Album A", null, null, null, null, null,
                null));
        int saves = lib.store.saves;

        FolderService.FromTagsOutcome o = folders.syncFromTags(album);

        assertThat(o.ok()).isFalse();
        assertThat(o.conflicts()).hasSize(1);            // genre differs only by case: not a conflict
        FolderService.SyncConflict c = o.conflicts().get(0);
        assertThat(c.field()).isEqualTo("album");
        assertThat(c.values()).extracting(FolderService.ConflictValue::value).containsExactly("Album A", "Album B");
        assertThat(c.values().get(0).files()).containsExactly("01 one.wav", "03 three.mp3");
        assertThat(c.values().get(1).files()).containsExactly("02 two.flac");
        assertThat(lib.music.resolve("Album/settings.json")).doesNotExist();
        assertThat(lib.store.saves).isEqualTo(saves);
        assertThat(folder().settings().album().isEmpty()).isTrue();
    }

    // ---- art ----

    private static byte[] image(String format) throws IOException {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void uploadsFrontAndRearAndConvertsNonJpeg() throws IOException {
        byte[] jpg = image("jpg");
        Folder f = folders.saveArt(album, FolderService.ArtKind.FRONT, jpg);
        assertThat(f.hasFront()).isTrue();
        assertThat(f.hasRear()).isFalse();
        assertThat(Files.readAllBytes(lib.music.resolve("Album/front.jpg"))).isEqualTo(jpg);

        Folder g = folders.saveArt(album, FolderService.ArtKind.REAR, image("png"));
        assertThat(g.hasRear()).isTrue();
        assertThat(g.hasFront()).isTrue();
        byte[] rear = Files.readAllBytes(lib.music.resolve("Album/rear.jpg"));
        assertThat(rear[0] & 0xFF).isEqualTo(0xFF);
        assertThat(rear[1] & 0xFF).isEqualTo(0xD8);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(rear))).isNotNull();

        assertThat(folders.artFile(album, FolderService.ArtKind.REAR)).isPresent();
        assertThat(folders.artFile(RootRef.parse("main/Other"), FolderService.ArtKind.REAR)).isEmpty();
        assertThat(lib.catalog.current().folder(album).orElseThrow().hasRear()).isTrue();
    }

    @Test
    void rejectsUploadsThatAreNotImages() {
        assertThatThrownBy(() -> folders.saveArt(album, FolderService.ArtKind.FRONT,
                "hello".getBytes(StandardCharsets.UTF_8))).isInstanceOf(CurationException.class)
                .hasMessageContaining("not a readable image");
        assertThatThrownBy(() -> folders.saveArt(album, FolderService.ArtKind.FRONT, new byte[0]))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> folders.saveArt(album, FolderService.ArtKind.FRONT,
                new byte[FolderService.MAX_ART_BYTES + 1])).isInstanceOf(CurationException.class);
        assertThat(lib.music.resolve("Album/front.jpg")).doesNotExist();
    }

    @Test
    void artKindParsing() {
        assertThat(FolderService.ArtKind.parse("FRONT")).contains(FolderService.ArtKind.FRONT);
        assertThat(FolderService.ArtKind.parse("rear")).contains(FolderService.ArtKind.REAR);
        assertThat(FolderService.ArtKind.parse("side")).isEmpty();
    }
}
