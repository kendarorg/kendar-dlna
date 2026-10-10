package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Admin REST API smoke tests: login flow, CSRF, read endpoints, tag edit round trip (checked through Browse and
 * Search) and the playlist lifecycle (checked through Browse). These tests change the server and restore it.
 */
@Order(90)
@TestMethodOrder(MethodOrderer.MethodName.class)
class AdminApiIT {

    private final Server server = Server.get();
    private final UpnpClient cds = server.upnp();

    @BeforeEach
    void adminAvailable() {
        Assumptions.assumeTrue(server.adminSkipReason() == null, () -> "admin API: " + server.adminSkipReason());
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String json(String... kv) {
        var n = AdminClient.mapper().createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            n.put(kv[i], kv[i + 1]);
        }
        return n.toString();
    }

    private AdminClient freshLogin() {
        AdminClient a = server.newAdminClient();
        AdminClient.Reply r = a.login(server.adminUser(), server.adminPassword());
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return a;
    }

    // ---------------------------------------------------------------- session

    @Test
    void protectedResourcesNeedALoginSession() {
        AdminClient anonymous = server.newAdminClient();
        AdminClient.Reply tree = anonymous.get("/api/v1/admin/folders/tree");
        assertThat(tree.status()).isEqualTo(401);
        assertThat(tree.json().path("code").asText()).isEqualTo("unauthenticated");
        assertThat(anonymous.get("/api/v1/admin/playlists").status()).isEqualTo(401);
        assertThat(anonymous.post("/api/v1/admin/rescan", "{}").status()).isEqualTo(401);
        JsonNode session = anonymous.get("/api/v1/auth/session").json();
        assertThat(session.path("authenticated").asBoolean(true)).isFalse();
    }

    @Test
    void loginLogoutFlow() {
        AdminClient a = server.newAdminClient();
        AdminClient.Reply wrong = a.login(server.adminUser(), "definitely-not-the-password");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.json().path("code").asText()).isEqualTo("invalid_credentials");

        AdminClient.Reply ok = a.login(server.adminUser(), server.adminPassword());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json().path("authenticated").asBoolean()).isTrue();
        assertThat(ok.json().path("mustChange").asBoolean(true)).isFalse();
        assertThat(ok.json().path("csrfToken").asText()).isNotBlank();
        assertThat(ok.headers().get("set-cookie").get(0)).contains("kd_session=").contains("HttpOnly");

        assertThat(a.get("/api/v1/admin/folders/tree").status()).isEqualTo(200);
        assertThat(a.get("/api/v1/auth/session").json().path("username").asText()).isEqualTo(server.adminUser());

        assertThat(a.logout().status()).isEqualTo(204);
        assertThat(a.get("/api/v1/admin/folders/tree").status()).isEqualTo(401);
    }

    @Test
    void loginNeedsTheRequestedWithHeader() {
        Http.Response r = Http.send("POST", server.baseUrl() + "/api/v1/auth/login", "Content-Type", "application/json");
        assertThat(r.status()).isIn(400, 403);
    }

    @Test
    void writesNeedTheCsrfTokenAndASameOriginRequest() {
        AdminClient a = freshLogin();
        AdminClient.Reply noToken = a.postWithoutCsrf("/api/v1/admin/playlists", json("name", "csrf-test"));
        assertThat(noToken.status()).isEqualTo(403);
        assertThat(noToken.json().path("code").asText()).isEqualTo("csrf");
        AdminClient.Reply crossOrigin = a.postWithOrigin("/api/v1/admin/playlists", json("name", "csrf-test"),
                "http://evil.example");
        assertThat(crossOrigin.status()).isEqualTo(403);
        // nothing was created
        List<String> names = new ArrayList<>();
        a.get("/api/v1/admin/playlists").json().path("playlists").forEach(p -> names.add(p.path("name").asText()));
        assertThat(names).doesNotContain("csrf-test");
    }

    @Test
    void passwordChangeRejectsAWrongCurrentPasswordAndAWeakNewOne() {
        AdminClient a = freshLogin();
        AdminClient.Reply wrong = a.changePassword("not-the-current-password", "another-long-password-1");
        assertThat(wrong.status()).isEqualTo(403);
        assertThat(wrong.json().path("code").asText()).isEqualTo("wrong_password");
        AdminClient.Reply weak = a.changePassword(server.adminPassword(), "short");
        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.json().path("code").asText()).isEqualTo("weak_password");
        // the account still works with the old password
        assertThat(freshLogin().get("/api/v1/auth/session").json().path("authenticated").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- read endpoints

    @Test
    void readEndpointsDescribeTheLibrary() {
        AdminClient a = server.admin();
        JsonNode tree = a.get("/api/v1/admin/folders/tree").json();
        assertThat(tree.path("roots")).isNotEmpty();
        JsonNode scan = a.get("/api/v1/admin/scan").json();
        assertThat(scan.path("running").asBoolean(true)).isFalse();
        assertThat(scan.path("trackCount").asInt()).isPositive();
        assertThat(a.get("/api/v1/admin/version").json().path("version").asText()).isNotBlank();
        assertThat(a.get("/api/v1/admin/streams").status()).isEqualTo(200);
        assertThat(a.get("/api/v1/admin/snapshots").status()).isEqualTo(200);
        assertThat(a.get("/api/v1/admin/invalid-settings").json().path("folders")).isEmpty();
        assertThat(a.get("/api/v1/admin/playlists").status()).isEqualTo(200);
        assertThat(a.get("/api/v1/nonexistent").status()).isEqualTo(404);

        if (server.managed()) {
            assertThat(scan.path("trackCount").asInt()).isEqualTo(server.library().trackCount());
            JsonNode unsupported = a.get("/api/v1/admin/unsupported").json().path("files");
            List<String> paths = new ArrayList<>();
            unsupported.forEach(f -> paths.add(f.path("path").asText()));
            assertThat(paths).containsExactly("Music/Misc/song.m4a");
            JsonNode hits = a.get("/api/v1/admin/search?q=" + q("alpha") + "&kind=track").json();
            assertThat(hits.path("total").asInt()).isEqualTo(4);
            JsonNode contents = a.get("/api/v1/admin/folders/contents?path=" + q("Music/Alpha Album")).json();
            assertThat(contents.path("trackCount").asInt()).isEqualTo(4);
            assertThat(contents.path("settings").path("sort").asText()).isEqualTo("track");
            assertThat(a.get("/api/v1/admin/folders/contents?path=" + q("Music/Nowhere")).status()).isEqualTo(404);
        }
    }

    // ---------------------------------------------------------------- tags

    @Test
    void tagEditRoundTripShowsInBrowseAndSearch() {
        Assumptions.assumeTrue(server.managed(), "edits a file of the generated library");
        AdminClient a = server.admin();
        String path = "Music/Beta Album/Beta Tagged.mp3";
        String url = "/api/v1/admin/tracks?path=" + q(path);
        Tree.Node folder = Tree.shared().require("Folders/" + LibraryFactory.ROOT + "/Beta Album");
        long before = cds.systemUpdateId();
        try {
            AdminClient.Reply put = a.put(url, json("title", "Beta Retitled", "genre", "Blues"));
            assertThat(put.status()).as(put.body()).isEqualTo(200);
            assertThat(put.json().path("title").asText()).isEqualTo("Beta Retitled");
            JsonNode got = a.get(url).json();
            assertThat(got.path("title").asText()).isEqualTo("Beta Retitled");
            assertThat(got.path("genre").asText()).isEqualTo("Blues");
            assertThat(got.path("artist").asText()).as("untouched fields stay").isEqualTo("Other Band");

            // the DLNA side follows: new title, new genre, new SystemUpdateID and ContainerUpdateID
            assertThat(cds.systemUpdateId()).isGreaterThan(before);
            List<String> titles = new ArrayList<>();
            var d = cds.children(folder.id(), 0, 0).didl();
            d.getItems().forEach(i -> titles.add(i.getTitle()));
            assertThat(titles).contains("Beta Retitled").doesNotContain("Beta Tagged");
            assertThat(cds.search("0", "dc:title contains \"Retitled\"", 0, 0).total()).isEqualTo(1);
            assertThat(cds.search("0", "upnp:genre = \"Blues\"", 0, 0).total()).isEqualTo(1);
            assertThat(cds.search("0", "upnp:genre = \"Jazz\"", 0, 0).total()).isZero();

            // invalid and unwritable edits
            assertThat(a.put(url, "{}").status()).isEqualTo(400);
            assertThat(a.put("/api/v1/admin/tracks?path=" + q("Music/Beta Album/Opus.nothing"), json("title", "x")).status())
                    .isEqualTo(404);
        } finally {
            AdminClient.Reply back = a.put(url, json("title", "Beta Tagged", "genre", "Jazz"));
            assertThat(back.status()).as("restoring the tags: " + back.body()).isEqualTo(200);
        }
        assertThat(cds.search("0", "dc:title = \"Beta Tagged\"", 0, 0).total()).isEqualTo(1);
    }

    @Test
    void midiTitleArtistAndYearAreEditableTheRestIsIgnored() {
        Assumptions.assumeTrue(server.managed(), "edits a file of the generated library");
        AdminClient a = server.admin();
        String url = "/api/v1/admin/tracks?path=" + q("Music/Beta Album/Beta Midi.mid");
        String oldTitle = a.get(url).json().path("title").asText();
        try {
            AdminClient.Reply put = a.put(url, json("title", "Midi Retitled", "artist", "Midi Band", "year", "1999",
                    "genre", "ignored"));
            assertThat(put.status()).as(put.body()).isEqualTo(200);
            JsonNode got = a.get(url).json();
            assertThat(got.path("title").asText()).isEqualTo("Midi Retitled");
            assertThat(got.path("artist").asText()).isEqualTo("Midi Band");
            assertThat(got.path("year").asInt()).isEqualTo(1999);
            assertThat(got.path("genre").isNull()).isTrue();
        } finally {
            AdminClient.Reply back = a.put(url, json("title", oldTitle, "artist", "", "year", ""));
            assertThat(back.status()).as("restoring the tags: " + back.body()).isEqualTo(200);
        }
    }

    @Test
    void tagsOfOpusCannotBeEdited() {
        Assumptions.assumeTrue(server.managed(), "uses the generated library");
        AdminClient a = server.admin();
        AdminClient.Reply opus = a.put("/api/v1/admin/tracks?path=" + q("Music/Beta Album/Beta Opus.opus"), json("title", "x"));
        assertThat(opus.status()).isEqualTo(409);
    }

    // ---------------------------------------------------------------- playlists

    private String aTrackPath(AdminClient a, int index) {
        JsonNode hits = a.get("/api/v1/admin/search?q=" + q("a") + "&kind=track").json().path("hits");
        Assumptions.assumeTrue(hits.size() > index, "the library has too few tracks");
        return hits.get(index).path("path").asText();
    }

    private List<String> playlistTitlesInBrowse(String name) {
        var playlists = cds.children("playlists", 0, 0).didl().getContainers();
        var pl = playlists.stream().filter(c -> c.getTitle().equals(name)).findFirst();
        if (pl.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        cds.children(pl.get().getId(), 0, 0).didl().getItems().forEach(i -> out.add(i.getTitle()));
        return out;
    }

    @Test
    void playlistLifecycleIsVisibleInBrowse() {
        AdminClient a = server.admin();
        String p1 = aTrackPath(a, 0);
        String p2 = aTrackPath(a, 1);
        long before = cds.systemUpdateId();
        AdminClient.Reply created = a.post("/api/v1/admin/playlists", json("name", "IT Playlist"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String key = created.json().path("key").asText();
        assertThat(key).isNotBlank();
        try {
            assertThat(cds.systemUpdateId()).as("SystemUpdateID after creating a playlist").isGreaterThan(before);
            assertThat(playlistTitlesInBrowse("IT Playlist")).as("empty playlist visible").isEmpty();

            String body = AdminClient.mapper().createObjectNode().set("paths",
                    AdminClient.mapper().createArrayNode().add(p1).add(p2)).toString();
            AdminClient.Reply added = a.post("/api/v1/admin/playlists/" + key + "/songs", body);
            assertThat(added.status()).as(added.body()).isEqualTo(200);
            assertThat(added.json().path("entries")).hasSize(2);
            List<String> inBrowse = playlistTitlesInBrowse("IT Playlist");
            assertThat(inBrowse).hasSize(2);

            // reverse the order
            String reversed = AdminClient.mapper().createObjectNode().set("songs",
                    AdminClient.mapper().createArrayNode().add(p2).add(p1)).toString();
            assertThat(a.put("/api/v1/admin/playlists/" + key, reversed).status()).isEqualTo(200);
            List<String> afterReverse = playlistTitlesInBrowse("IT Playlist");
            assertThat(afterReverse).containsExactly(inBrowse.get(1), inBrowse.get(0));

            // rename
            assertThat(a.put("/api/v1/admin/playlists/" + key, json("name", "IT Renamed")).status()).isEqualTo(200);
            assertThat(playlistTitlesInBrowse("IT Playlist")).isNull();
            assertThat(playlistTitlesInBrowse("IT Renamed")).hasSize(2);

            // the items of the playlist stream
            var pl = cds.children("playlists", 0, 0).didl().getContainers().stream()
                    .filter(c -> c.getTitle().equals("IT Renamed")).findFirst().orElseThrow();
            var item = cds.children(pl.getId(), 0, 0).didl().getItems().get(0);
            assertThat(Http.head(server.rebase(item.getResources().get(0).getValue())).status()).isEqualTo(200);
        } finally {
            assertThat(a.delete("/api/v1/admin/playlists/" + key).status()).isEqualTo(204);
        }
        assertThat(playlistTitlesInBrowse("IT Renamed")).isNull();
        assertThat(a.get("/api/v1/admin/playlists/" + key).status()).isEqualTo(404);
    }

    @Test
    void playlistWithBrokenEntryIsReportedByTheApiAndSkippedInBrowse() {
        Assumptions.assumeTrue(server.managed(), "uses the generated playlist");
        AdminClient a = server.admin();
        JsonNode detail = a.get("/api/v1/admin/playlists/" + LibraryFactory.PLAYLIST_KEY).json();
        assertThat(detail.path("name").asText()).isEqualTo(LibraryFactory.PLAYLIST_NAME);
        assertThat(detail.path("entries")).hasSize(4);
        assertThat(detail.path("broken").asInt()).isEqualTo(1);
        assertThat(playlistTitlesInBrowse(LibraryFactory.PLAYLIST_NAME)).containsExactly("01 Alpha One",
                "Beta Tagged", "Zed");
    }

    @Test
    void playlistRequestsAreValidated() {
        AdminClient a = server.admin();
        assertThat(a.post("/api/v1/admin/playlists", "{}").status()).isIn(400, 201); // an unnamed playlist is named by its key
        assertThat(a.get("/api/v1/admin/playlists/does-not-exist").status()).isEqualTo(404);
        assertThat(a.post("/api/v1/admin/playlists/does-not-exist/songs", "{\"paths\":[\"Music/x.mp3\"]}").status())
                .isEqualTo(404);
        // clean up the unnamed playlist, if one was created
        JsonNode list = a.get("/api/v1/admin/playlists").json().path("playlists");
        for (JsonNode p : list) {
            if (p.path("count").asInt() == 0 && !p.path("key").asText().equals(LibraryFactory.PLAYLIST_KEY)) {
                a.delete("/api/v1/admin/playlists/" + p.path("key").asText());
            }
        }
    }

    // ---------------------------------------------------------------- users

    @Test
    void aRegisteredUserSeesTheLibraryButNotTheAdminApi() {
        AdminClient a = freshLogin();
        AdminClient.Reply created = a.post("/api/v1/admin/users", json("username", "it-lena"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = created.json().path("user").path("id").asText();
        try {
            String token = created.json().path("invite").path("token").asText();
            AdminClient lena = server.newAdminClient();
            assertThat(lena.post("/api/v1/auth/invite/accept", json("token", token, "password", "it-lena-password"))
                    .status()).isEqualTo(200);
            assertThat(lena.login("it-lena", "it-lena-password").status()).isEqualTo(200);
            assertThat(lena.get("/api/v1/admin/version").status()).isEqualTo(403);
            assertThat(lena.get("/api/v1/admin/users").status()).isEqualTo(403);
            AdminClient.Reply roots = lena.get("/api/v1/library/roots");
            assertThat(roots.status()).isEqualTo(200);
            assertThat(roots.json().path("roots")).hasSize(1);   // the main root only
            assertThat(lena.get("/api/v1/library/folders/contents?path=" + q("Nowhere")).status()).isEqualTo(403);
        } finally {
            a.delete("/api/v1/admin/users/" + id);
        }
    }
}
