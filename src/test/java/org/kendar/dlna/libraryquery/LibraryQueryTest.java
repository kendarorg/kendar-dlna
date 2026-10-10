package org.kendar.dlna.libraryquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.curation.TagEdit;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.identity.IdentityException;
import org.kendar.dlna.identity.Role;
import org.kendar.dlna.identity.User;
import org.kendar.dlna.ingestion.ScanTrigger;
import org.kendar.dlna.shared.Json;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.testutil.LibraryFixture;
import org.kendar.dlna.views.Views;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The library query facade against a real scanned library with two roots: {@code main} (visible to the restricted
 * user) and {@code extra} (admin only). Nothing of {@code extra} may ever show up for the restricted user.
 */
class LibraryQueryTest {

    /** Strings that only occur in the {@code extra} root. */
    private static final List<String> SECRETS = List.of("extra", "Hidden", "Bob", "Jazz", "Secret", "Gamma");

    @TempDir
    Path tmp;
    LibraryFixture lib;
    Views views;
    LibraryQuery query;
    TagService tags;
    User lena;
    User kim;
    User admin;

    @BeforeEach
    void setUp() throws IOException {
        lib = new LibraryFixture(tmp);
        Path extra = Files.createDirectories(tmp.resolve("extra/Disc"));
        AudioFiles.fixture("sweep.mp3", extra.resolve("x1.mp3"));
        AudioFiles.fixture("sweep.flac", extra.resolve("x2.flac"));
        AudioFiles.fixture("sweep.mp3", tmp.resolve("extra/solo.mp3"));
        lib.roots.put("extra", tmp.resolve("extra"));
        Files.write(lib.music.resolve("Album/front.jpg"), jpeg(1000, 600));
        lib.rescans.rescanNow(ScanTrigger.MANUAL, null);
        tags = new TagService(lib.catalog, lib.files, new org.kendar.dlna.curation.PerFormatTagWriter(), lib.reader);
        tag("main/Album/02 two.flac", "Alpha Song", "Alice", "First", "Rock", "1");
        tag("main/Album/03 three.mp3", "Beta Song", "Alice", "First", "Rock", "2");
        tag("extra/Disc/x1.mp3", "Secret Song", "Bob", "Hidden", "Jazz", "1");
        tag("extra/Disc/x2.flac", "Secret Two", "Bob", "Hidden", "Jazz", "2");
        tag("extra/solo.mp3", "Gamma Song", "Alice", "First", "Rock", "3");

        views = new Views(lib.catalog::current);
        ArtworkService artwork = new ArtworkService(lib.catalog.holder(), lib.files::resolve, ref -> Optional.empty(),
                tmp.resolve("thumbs"));
        query = new LibraryQuery(lib.catalog::current, views, artwork);
        lena = user("lena", Role.USER, "main");
        kim = user("kim", Role.USER, "extra");
        admin = user("root", Role.ADMIN);
    }

    private void tag(String path, String title, String artist, String album, String genre, String trackNo) {
        tags.writeOne(RootRef.parse(path), new TagEdit(title, artist, album, artist, genre, "1999", trackNo, null,
                null, null));
    }

    private static User user(String name, Role role, String... roots) {
        return new User(name + "-id", name, name, role, "x", false, List.of(roots), false, true, Instant.EPOCH, null);
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static String text(Object o) {
        try {
            return Json.mapper().writeValueAsString(o);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonNode node(Object o) {
        return Json.mapper().valueToTree(o);
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<String> titles(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(n -> out.add(n.path("title").asText()));
        return out;
    }

    private void assertNoSecrets(String what, Object result) {
        String json = text(result);
        for (String s : SECRETS) {
            assertThat(json).as(what + " leaks " + s).doesNotContain(s);
        }
    }

    // ---- every resource is confined to the allowed roots ----

    @Test
    void aRestrictedUserNeverSeesAnythingOutsideTheAllowedRootsInAnyResource() {
        Map<String, String> all = q("limit", "200");
        List<Object> results = new ArrayList<>();
        results.add(query.roots(lena));
        results.add(query.folder(lena, q("path", "main")));
        results.add(query.folder(lena, q("path", "main/Album")));
        for (String view : List.of("albums", "artists", "genres")) {
            results.add(query.groups(lena, view, all));
        }
        results.add(query.songs(lena, all));
        results.add(query.recent(lena, all));
        for (long seed = 1; seed <= 5; seed++) {
            results.add(query.random(lena, q("seed", String.valueOf(seed), "count", "1000")));
        }
        results.add(query.random(lena, q("path", "main", "count", "1000")));
        for (String text : List.of("song", "alice", "first", "secret", "bob", "hidden", "jazz", "gamma", "disc", "x1",
                "a", "e")) {
            results.add(query.search(lena, q("q", text)));
            results.add(query.suggest(lena, q("q", text)));
            for (String kind : List.of("track", "album", "artist", "folder")) {
                results.add(query.search(lena, q("q", text, "kind", kind, "limit", "200")));
            }
        }
        assertThat(node(query.search(lena, q("q", "extra"))).get("tracks").get("total").asInt()).isZero();
        assertThat(node(query.search(lena, q("q", "extra"))).get("folders").get("total").asInt()).isZero();
        results.add(query.group(lena, "artists", firstId(lena, "artists"), false, all));
        results.add(query.group(lena, "albums", firstId(lena, "albums"), true, all));
        results.add(query.group(lena, "genres", firstId(lena, "genres"), false, all));
        for (int i = 0; i < results.size(); i++) {
            assertNoSecrets("result #" + i, results.get(i));
        }
        // and the same queries do reach them for an administrator
        assertThat(text(query.songs(admin, all))).contains("Secret Song", "Gamma Song");
        assertThat(text(query.search(admin, q("q", "secret")))).contains("Secret Song");
        assertThat(text(query.random(admin, q("count", "1000", "seed", "1")))).contains("extra/");
    }

    @Test
    void explicitLocationsOutsideTheAllowedRootsAreForbiddenEvenWhenTheyDoNotExist() {
        List<Runnable> calls = List.of(
                () -> query.folder(lena, q("path", "extra")),
                () -> query.folder(lena, q("path", "extra/Disc")),
                () -> query.folder(lena, q("path", "nonexistent")),
                () -> query.track(lena, q("path", "extra/Disc/x1.mp3")),
                () -> query.track(lena, q("path", "extra/nope.mp3")),
                () -> query.random(lena, q("path", "extra/Disc")),
                () -> query.search(lena, q("q", "song", "scope", "extra")),
                () -> query.artwork(lena, q("kind", "track", "path", "extra/solo.mp3")),
                () -> query.artwork(lena, q("kind", "folder", "path", "extra/Disc")));
        for (int i = 0; i < calls.size(); i++) {
            int n = i;
            assertThatThrownBy(() -> calls.get(n).run()).as("call #" + n).isInstanceOf(IdentityException.class)
                    .extracting(e -> ((IdentityException) e).code()).isEqualTo("forbidden_root");
        }
        assertThatThrownBy(() -> query.folder(kim, q("path", "main/Album"))).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> query.folder(admin, q("path", "nonexistent"))).isInstanceOf(QueryException.class);
    }

    @Test
    void groupIdsFromAnotherScopeAreUnknown() {
        String hidden = idOf(admin, "albums", "Hidden");
        assertThat(query.group(kim, "albums", hidden, false, q()).get("item")).isNotNull();
        assertThatThrownBy(() -> query.group(lena, "albums", hidden, false, q())).isInstanceOf(QueryException.class)
                .extracting(e -> ((QueryException) e).code()).isEqualTo("not_found");
        assertThatThrownBy(() -> query.group(lena, "albums", hidden, true, q())).isInstanceOf(QueryException.class);
        String bob = idOf(admin, "artists", "Bob");
        assertThatThrownBy(() -> query.group(lena, "artists", bob, false, q())).isInstanceOf(QueryException.class);
    }

    @Test
    void aUserWithoutRootsSeesAnEmptyLibrary() {
        User nobody = user("nobody", Role.USER);
        assertThat(node(query.roots(nobody)).get("roots")).isEmpty();
        assertThat(node(query.songs(nobody, q())).get("total").asInt()).isZero();
        assertThat(node(query.groups(nobody, "albums", q())).get("total").asInt()).isZero();
        assertThat(node(query.recent(nobody, q())).get("total").asInt()).isZero();
        assertThat(node(query.random(nobody, q())).get("total").asInt()).isZero();
        assertThat(node(query.search(nobody, q("q", "song"))).get("tracks").get("total").asInt()).isZero();
    }

    // ---- grouping rules over a set of roots ----

    @Test
    void albumsArtistsAndGenresAreGroupedOverTheCallersRootsOnly() {
        JsonNode adminAlbums = node(query.groups(admin, "albums", q("limit", "200")));
        JsonNode lenaAlbums = node(query.groups(lena, "albums", q("limit", "200")));
        assertThat(count(adminAlbums, "First")).isEqualTo(3);
        assertThat(count(adminAlbums, "Hidden")).isEqualTo(2);
        assertThat(count(lenaAlbums, "First")).isEqualTo(2);
        assertThat(titles(lenaAlbums.get("items"))).doesNotContain("Hidden");

        JsonNode lenaArtists = node(query.groups(lena, "artists", q()));
        assertThat(titles(lenaArtists.get("items"))).contains("Alice").doesNotContain("Bob");
        assertThat(count(lenaArtists, "Alice")).isEqualTo(2);
        assertThat(count(node(query.groups(admin, "artists", q())), "Alice")).isEqualTo(3);

        JsonNode genres = node(query.groups(lena, "genres", q()));
        assertThat(titles(genres.get("items"))).contains("Rock").doesNotContain("Jazz");
        assertThat(titles(node(query.groups(admin, "genres", q())).get("items"))).contains("Rock", "Jazz");
    }

    @Test
    void albumDetailListsTracksInDiscAndTrackOrderAndArtistDetailListsAlbums() {
        String id = idOf(lena, "albums", "First");
        JsonNode album = node(query.group(lena, "albums", id, false, q()));
        assertThat(album.get("item").get("title").asText()).isEqualTo("First");
        assertThat(album.get("item").get("artist").asText()).isEqualTo("Alice");
        assertThat(album.get("total").asInt()).isEqualTo(2);
        List<String> t = new ArrayList<>();
        album.get("tracks").forEach(n -> t.add(n.get("title").asText()));
        assertThat(t).containsExactly("Alpha Song", "Beta Song");

        String alice = idOf(lena, "artists", "Alice");
        JsonNode artist = node(query.group(lena, "artists", alice, false, q()));
        assertThat(titles(artist.get("albums"))).containsExactly("First");
        assertThat(artist.get("allTracks").get("trackCount").asInt()).isEqualTo(2);
        JsonNode inArtist = node(query.group(lena, "artists", artist.get("albums").get(0).get("id").asText(), false,
                q()));
        assertThat(inArtist.get("total").asInt()).isEqualTo(2);
        JsonNode all = node(query.group(lena, "artists", alice, true, q()));
        assertThat(all.get("total").asInt()).isEqualTo(2);
        JsonNode genre = node(query.group(lena, "genres", idOf(lena, "genres", "Rock"), false, q()));
        assertThat(titles(genre.get("albums"))).containsExactly("First");
    }

    @Test
    void libraryWideTreesAreMemoizedPerSnapshotAndRootsSet() {
        var snap = lib.catalog.current();
        var a = views.libraryNode(snap, Set.of("main"), ViewKind.ALBUM, "").orElseThrow();
        assertThat(views.libraryNode(snap, Set.of("main"), ViewKind.ALBUM, "").orElseThrow()).isSameAs(a);
        var both = views.libraryNode(snap, Set.of("main", "extra"), ViewKind.ALBUM, "").orElseThrow();
        assertThat(both).isNotSameAs(a);
        assertThat(views.libraryNode(snap, Set.of("extra", "main"), ViewKind.ALBUM, "").orElseThrow()).isSameAs(both);
        var cur = lib.catalog.current();
        lib.catalog.holder().commit(cur.withUpdates(cur.updateId() + 1, cur.scannedAt(), cur.containerUpdateIds()));
        assertThat(views.libraryNode(lib.catalog.current(), Set.of("main"), ViewKind.ALBUM, "").orElseThrow())
                .isNotSameAs(a);
    }

    // ---- tracks, folders ----

    @Test
    void trackCarriesMetadataDurationFormatPlayabilityAndArtRef() {
        JsonNode flac = node(query.track(lena, q("path", "main/Album/02 two.flac"))).get("track");
        assertThat(flac.get("title").asText()).isEqualTo("Alpha Song");
        assertThat(flac.get("album").asText()).isEqualTo("First");
        assertThat(flac.get("format").asText()).isEqualTo("flac");
        assertThat(flac.get("durationMillis").asLong()).isPositive();
        assertThat(flac.get("playability").asText()).isEqualTo("native");
        assertThat(flac.get("art").asText()).startsWith("/api/v1/library/artwork?kind=track&path=main%2FAlbum%2F02");
        assertThat(node(query.track(lena, q("path", "main/Album/02 two.flac", "formats", "mp3"))).get("track")
                .get("playability").asText()).isEqualTo("transcoded");
        JsonNode midi = node(query.track(lena, q("path", "main/Album/04 four.mid"))).get("track");
        assertThat(midi.get("playability").asText()).isEqualTo("transcoded");
        assertThat(midi.get("streamFormat").asText()).isEqualTo("mp3");
        assertThatThrownBy(() -> query.track(admin, q("path", "main/Album/nope.mp3"))).isInstanceOf(
                QueryException.class);
    }

    @Test
    void folderListsChildrenInSettingsOrderWithOverlayArtAndPerDirectorySettings() {
        JsonNode album = node(query.folder(lena, q("path", "main/Album")));
        assertThat(album.get("snapshot").asLong()).isEqualTo(lib.catalog.current().updateId());
        assertThat(album.get("parent").asText()).isEqualTo("main");
        assertThat(album.get("trackCount").asInt()).isEqualTo(4);
        assertThat(album.get("art").asText()).contains("kind=folder");
        assertThat(album.get("random").asInt()).isZero();
        assertThat(album.get("views")).isEmpty();
        List<String> names = new ArrayList<>();
        album.get("children").forEach(n -> names.add(n.get("track").get("name").asText()));
        assertThat(names).containsExactly("01 one.wav", "02 two.flac", "03 three.mp3", "04 four.mid");
        JsonNode root = node(query.folder(lena, q("path", "main")));
        assertThat(root.get("parent").isNull()).isTrue();
        assertThat(root.get("isRoot").asBoolean()).isTrue();
        assertThat(root.get("folderCount").asInt()).isEqualTo(2);
    }

    // ---- pagination ----

    @Test
    void listsPaginateWithOffsetLimitAndTotal() {
        JsonNode first = node(query.songs(admin, q("limit", "2")));
        assertThat(first.get("total").asInt()).isEqualTo(7);
        assertThat(first.get("items")).hasSize(2);
        List<String> seen = new ArrayList<>();
        for (int off = 0; off < 8; off += 3) {
            JsonNode page = node(query.songs(admin, q("offset", String.valueOf(off), "limit", "3")));
            assertThat(page.get("total").asInt()).isEqualTo(7);
            assertThat(page.get("offset").asInt()).isEqualTo(off);
            page.get("items").forEach(n -> seen.add(n.get("path").asText()));
        }
        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        assertThat(node(query.songs(admin, q("offset", "100"))).get("items")).isEmpty();
        assertThat(node(query.songs(admin, q("limit", "100000"))).get("limit").asInt()).isEqualTo(200);
        assertThat(node(query.folder(lena, q("path", "main/Album", "limit", "1", "offset", "3"))).get("children"))
                .hasSize(1);
        assertThat(node(query.groups(admin, "albums", q("limit", "1"))).get("total").asInt()).isGreaterThan(1);
        assertThatThrownBy(() -> query.songs(admin, q("offset", "-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.songs(admin, q("limit", "0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.songs(admin, q("limit", "x"))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- search ----

    @Test
    void searchGroupsTracksAlbumsArtistsAndFoldersWithinTheCallersScope() {
        JsonNode r = node(query.search(lena, q("q", "alice")));
        assertThat(r.get("tracks").get("total").asInt()).isEqualTo(2);
        assertThat(titles(r.get("artists").get("items"))).containsExactly("Alice");
        JsonNode first = node(query.search(lena, q("q", "first")));
        assertThat(titles(first.get("albums").get("items"))).containsExactly("First");
        assertThat(first.get("albums").get("items").get(0).get("trackCount").asInt()).isEqualTo(2);
        JsonNode folders = node(query.search(lena, q("q", "album", "kind", "folder")));
        assertThat(folders.has("tracks")).isFalse();
        assertThat(folders.get("folders").get("items").get(0).get("path").asText()).isEqualTo("main/Album");
        assertThat(node(query.search(admin, q("q", "alice"))).get("tracks").get("total").asInt()).isEqualTo(3);
        assertThat(node(query.search(admin, q("q", "secret", "scope", "extra/Disc"))).get("tracks").get("total")
                .asInt()).isEqualTo(2);
        JsonNode paged = node(query.search(admin, q("q", "song", "kind", "track", "limit", "2", "offset", "2")));
        assertThat(paged.get("tracks").get("items")).hasSize(2);
        assertThat(paged.get("tracks").get("total").asInt()).isEqualTo(4);
        assertThatThrownBy(() -> query.search(lena, q("q", " "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.search(lena, q("q", "a", "kind", "bogus"))).isInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void typeaheadSuggestsPrefixMatchesFirstAndIsEmptyForBlankText() {
        JsonNode s = node(query.suggest(lena, q("q", "al"))).get("suggestions");
        assertThat(s.get(0).get("type").asText()).isEqualTo("artist");
        assertThat(s.get(0).get("label").asText()).isEqualTo("Alice");
        List<String> types = new ArrayList<>();
        s.forEach(n -> types.add(n.get("type").asText()));
        assertThat(types).contains("track");
        assertThat(node(query.suggest(lena, q("q", "al", "limit", "1"))).get("suggestions")).hasSize(1);
        assertThat(node(query.suggest(lena, q("q", ""))).get("suggestions")).isEmpty();
        assertThat(text(query.suggest(admin, q("q", "bo")))).contains("Bob");
    }

    // ---- recently added (first-seen) ----

    @Test
    void recentlyAddedIsOrderedByFirstSeenAndSurvivesRescansAndTagEdits() throws IOException {
        lib.clock.advance(Duration.ofDays(3650));   // later than the real file times of the first scan
        AudioFiles.fixture("sweep.mp3", lib.music.resolve("Other/new.mp3"));
        lib.rescans.rescanNow(ScanTrigger.MANUAL, null);
        long seen = lib.clock.millis();
        JsonNode r = node(query.recent(lena, q()));
        assertThat(r.get("items").get(0).get("path").asText()).isEqualTo("main/Other/new.mp3");
        assertThat(r.get("items").get(0).get("addedAt").asLong()).isEqualTo(seen);

        lib.clock.advance(Duration.ofHours(1));
        AudioFiles.fixture("sweep.mp3", tmp.resolve("extra/newest.mp3"));
        lib.rescans.rescanNow(ScanTrigger.MANUAL, null);
        assertThat(node(query.recent(admin, q())).get("items").get(0).get("path").asText())
                .isEqualTo("extra/newest.mp3");
        assertThat(node(query.recent(lena, q())).get("items").get(0).get("path").asText())
                .isEqualTo("main/Other/new.mp3");     // the admin-only newer file is invisible to lena
        assertThat(text(query.recent(lena, q("limit", "200")))).doesNotContain("newest");

        tag("main/Other/new.mp3", "Renamed", "Zed", "Z", "Pop", "1");
        lib.clock.advance(Duration.ofHours(1));
        lib.rescans.rescanNow(ScanTrigger.MANUAL, null);
        JsonNode after = node(query.recent(lena, q())).get("items").get(0);
        assertThat(after.get("path").asText()).isEqualTo("main/Other/new.mp3");
        assertThat(after.get("addedAt").asLong()).isEqualTo(seen);
        assertThat(node(query.recent(lena, q("since", String.valueOf(seen)))).get("total").asInt()).isEqualTo(1);
        assertThat(node(query.recent(lena, q("limit", "1"))).get("total").asInt()).isEqualTo(5);
    }

    // ---- random ----

    @Test
    void randomIsSeededPerUserAndStaysInsideTheRootsOrTheFolder() {
        JsonNode a = node(query.random(admin, q("seed", "42", "count", "5")));
        JsonNode b = node(query.random(admin, q("seed", "42", "count", "5")));
        assertThat(a.get("items")).isEqualTo(b.get("items"));
        assertThat(a.get("seed").asLong()).isEqualTo(42);
        assertThat(a.get("total").asInt()).isEqualTo(5);
        assertThat(node(query.random(admin, q("count", "5"))).get("seed")).isNotNull();
        JsonNode sub = node(query.random(lena, q("path", "main/Album", "count", "100", "seed", "7")));
        assertThat(sub.get("total").asInt()).isEqualTo(4);
        sub.get("items").forEach(n -> assertThat(n.get("path").asText()).startsWith("main/Album/"));
        JsonNode page = node(query.random(admin, q("seed", "1", "count", "7", "limit", "3", "offset", "3")));
        assertThat(page.get("items")).hasSize(3);
        assertThat(page.get("total").asInt()).isEqualTo(7);
        assertThatThrownBy(() -> query.random(admin, q("count", "0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.random(admin, q("count", "1001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.random(admin, q("path", "main/nowhere"))).isInstanceOf(QueryException.class);
    }

    // ---- artwork ----

    @Test
    void artworkComesInSmallMediumAndLargeAndNeverUpscales() throws IOException {
        assertThat(dims(query.artwork(lena, q("kind", "folder", "path", "main/Album", "size", "small"))))
                .containsExactly(160, 96);
        assertThat(dims(query.artwork(lena, q("kind", "folder", "path", "main/Album", "size", "medium"))))
                .containsExactly(300, 180);
        assertThat(dims(query.artwork(lena, q("kind", "track", "path", "main/Album/02 two.flac", "size", "large"))))
                .containsExactly(800, 480);
        assertThat(dims(query.artwork(lena, q("kind", "track", "path", "main/Album/02 two.flac")))).containsExactly(
                300, 180);   // default: medium
        LibraryQuery.Image medium = query.artwork(lena, q("kind", "folder", "path", "main/Album", "size", "medium"));
        LibraryQuery.Image large = query.artwork(lena, q("kind", "folder", "path", "main/Album", "size", "large"));
        assertThat(medium.etag()).isNotEqualTo(large.etag());
        assertThat(medium.contentType()).isEqualTo("image/jpeg");
        assertThat(query.artwork(lena, q("kind", "folder", "path", "main/Album", "size", "medium")).etag())
                .isEqualTo(medium.etag());
        assertThatThrownBy(() -> query.artwork(lena, q("kind", "folder", "path", "main/Album", "size", "huge")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.artwork(lena, q("kind", "folder", "path", "main/Other", "size", "small")))
                .isInstanceOf(QueryException.class);   // no art there
        assertThat(query.artwork(admin, q("kind", "folder", "path", "main/Album", "size", "large")).data())
                .isNotEmpty();
    }

    private static List<Integer> dims(LibraryQuery.Image img) throws IOException {
        BufferedImage i = ImageIO.read(new ByteArrayInputStream(img.data()));
        return List.of(i.getWidth(), i.getHeight());
    }

    // ---- helpers ----

    private int count(JsonNode page, String title) {
        for (JsonNode n : page.get("items")) {
            if (n.get("title").asText().equals(title)) {
                return n.get("trackCount").asInt();
            }
        }
        throw new AssertionError("no entry " + title + " in " + titles(page.get("items")));
    }

    private String idOf(User user, String view, String title) {
        for (JsonNode n : node(query.groups(user, view, q("limit", "200"))).get("items")) {
            if (n.get("title").asText().equals(title)) {
                return n.get("id").asText();
            }
        }
        throw new AssertionError("no " + view + " entry " + title);
    }

    private String firstId(User user, String view) {
        return node(query.groups(user, view, q())).get("items").get(0).get("id").asText();
    }
}
