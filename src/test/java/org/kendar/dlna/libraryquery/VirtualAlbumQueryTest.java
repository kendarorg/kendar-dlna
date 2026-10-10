package org.kendar.dlna.libraryquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.curation.CurationException;
import org.kendar.dlna.curation.PerFormatTagWriter;
import org.kendar.dlna.curation.TagEdit;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.identity.IdentityException;
import org.kendar.dlna.identity.Role;
import org.kendar.dlna.identity.User;
import org.kendar.dlna.ingestion.ScanTrigger;
import org.kendar.dlna.shared.Json;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.CueFixtures;
import org.kendar.dlna.testutil.LibraryFixture;
import org.kendar.dlna.views.Views;

import com.fasterxml.jackson.databind.JsonNode;

/** Virtual albums through the REST query facade: folder listing, library-wide views, search, allowed roots. */
class VirtualAlbumQueryTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    LibraryQuery query;
    User lena;
    User kim;
    User admin;

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        CueFixtures.write(tmp.resolve("extra/Gigs"));
        lib.roots.put("extra", tmp.resolve("extra"));
        lib.rescans.rescanNow(ScanTrigger.MANUAL, null);
        ArtworkService artwork = new ArtworkService(lib.catalog.holder(), lib.files::resolve, ref -> Optional.empty(),
                tmp.resolve("thumbs"));
        query = new LibraryQuery(lib.catalog::current, new Views(lib.catalog::current), artwork);
        lena = user("lena", Role.USER, "main");
        kim = user("kim", Role.USER, "extra");
        admin = user("root", Role.ADMIN);
    }

    private static User user(String name, Role role, String... roots) {
        return new User(name + "-id", name, name, role, "x", false, List.of(roots), false, true, Instant.EPOCH, null);
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static JsonNode node(Object o) {
        return Json.mapper().valueToTree(o);
    }

    @Test
    void theDirectoryListingShowsTheFileAsAVirtualFolderAndItsTracksCarryTheRange() {
        JsonNode dir = node(query.folder(kim, q("path", "extra/Gigs")));
        assertThat(dir.get("children")).hasSize(1);
        JsonNode album = dir.get("children").get(0);
        assertThat(album.get("type").asText()).isEqualTo("folder");
        assertThat(album.get("virtual").asBoolean()).isTrue();
        assertThat(album.get("path").asText()).isEqualTo("extra/Gigs/disc.wav");
        assertThat(album.get("displayName").asText()).isEqualTo("Live Album");

        JsonNode inside = node(query.folder(kim, q("path", "extra/Gigs/disc.wav")));
        assertThat(inside.get("virtual").asBoolean()).isTrue();
        assertThat(inside.get("parent").asText()).isEqualTo("extra/Gigs");
        assertThat(inside.get("trackCount").asInt()).isEqualTo(3);
        JsonNode second = inside.get("children").get(1).get("track");
        assertThat(second.get("path").asText()).isEqualTo("extra/Gigs/disc.wav#2");
        assertThat(second.get("title").asText()).isEqualTo("Middle");
        assertThat(second.get("album").asText()).isEqualTo("Live Album");
        assertThat(second.get("playability").asText()).isEqualTo("transcoded");
        assertThat(second.get("streamFormat").asText()).isEqualTo("mp3");
        assertThat(second.get("segment").get("source").asText()).isEqualTo("extra/Gigs/disc.wav");
        assertThat(second.get("segment").get("origin").asText()).isEqualTo("cue");
        assertThat(second.get("segment").get("startMillis").asLong()).isEqualTo(3493);
        assertThat(second.get("segment").get("endMillis").asLong()).isEqualTo(6000);
        assertThat(second.get("durationMillis").asLong()).isEqualTo(2507);
        assertThat(inside.get("children").get(0).get("track").get("segment").get("index").asInt()).isEqualTo(1);
    }

    @Test
    void trackLookupByVirtualPath() {
        JsonNode t = node(query.track(kim, q("path", "extra/Gigs/disc.wav#3")));
        assertThat(t.toString()).contains("Finale");
    }

    @Test
    void libraryWideViewsAndSearchSeeTheTracksAndTheAlbum() {
        JsonNode albums = node(query.groups(kim, "albums", q("limit", "50")));
        assertThat(albums.toString()).contains("Live Album");
        JsonNode songs = node(query.songs(kim, q("limit", "50")));
        assertThat(songs.toString()).contains("Opening", "Middle", "Finale");
        JsonNode artists = node(query.groups(kim, "artists", q("limit", "50")));
        assertThat(artists.toString()).contains("The Band");
        JsonNode found = node(query.search(kim, q("q", "finale")));
        assertThat(found.get("tracks").get("total").asInt()).isEqualTo(1);
        assertThat(node(query.search(kim, q("q", "live album"))).toString()).contains("Live Album");
    }

    @Test
    void aUserWithoutTheRootNeverSeesTheVirtualAlbumOrItsTracks() {
        for (String text : List.of("finale", "live album", "opening", "band", "guest", "disc")) {
            assertThat(node(query.search(lena, q("q", text))).toString()).as(text).doesNotContain("Gigs")
                    .doesNotContain("Live Album").doesNotContain("Finale");
            assertThat(node(query.suggest(lena, q("q", text))).toString()).as(text).doesNotContain("Live Album");
        }
        assertThat(node(query.songs(lena, q("limit", "200"))).toString()).doesNotContain("Finale", "Opening");
        assertThat(node(query.groups(lena, "albums", q("limit", "200"))).toString()).doesNotContain("Live Album");
        assertThat(node(query.groups(lena, "artists", q("limit", "200"))).toString()).doesNotContain("The Band");
        assertThat(node(query.recent(lena, q("limit", "200"))).toString()).doesNotContain("Finale");
        assertThat(node(query.random(lena, q("count", "1000", "seed", "3"))).toString()).doesNotContain("Gigs");
        for (Runnable call : List.<Runnable>of(
                () -> query.folder(lena, q("path", "extra/Gigs/disc.wav")),
                () -> query.track(lena, q("path", "extra/Gigs/disc.wav#2")),
                () -> query.artwork(lena, q("kind", "folder", "path", "extra/Gigs/disc.wav")),
                () -> query.artwork(lena, q("kind", "track", "path", "extra/Gigs/disc.wav#1")))) {
            assertThatThrownBy(call::run).isInstanceOf(IdentityException.class)
                    .extracting(e -> ((IdentityException) e).code()).isEqualTo("forbidden_root");
        }
        assertThat(node(query.songs(admin, q("limit", "200"))).toString()).contains("Finale");
    }

    @Test
    void tagsOfAVirtualTrackCannotBeWrittenIntoTheSourceFile() {
        TagService tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
        RootRef ref = RootRef.parse("extra/Gigs/disc.wav#2");
        assertThatThrownBy(() -> tags.writeOne(ref,
                new TagEdit("X", null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(CurationException.class);
        assertThat(lib.catalog.current().track(ref).orElseThrow().tags().title()).isEqualTo("Middle");
    }
}
