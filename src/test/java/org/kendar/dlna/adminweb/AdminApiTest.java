package org.kendar.dlna.adminweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.curation.FolderService;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.LoginThrottle;
import org.kendar.dlna.identity.PasswordHasher;
import org.kendar.dlna.identity.SessionStore;
import org.kendar.dlna.curation.JAudioTaggerWriter;
import org.kendar.dlna.curation.PlaylistService;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.LibraryFixture;

import com.fasterxml.jackson.databind.JsonNode;

/** The REST API through its pure seam (no Jetty): auth, CSRF, error model and the resources. */
class AdminApiTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    SessionStore sessions;
    IdentityService identity;
    AdminApi api;
    ActiveStreams streams = new ActiveStreams();
    Shuffle shuffle;
    String generated;

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        sessions = new SessionStore(lib.clock);
        java.util.List<String> log = new java.util.ArrayList<>();
        identity = new IdentityService(tmp.resolve("users"), java.util.List.of("main"), null,
                new PasswordHasher(1000, new SecureRandom()), sessions, lib.clock, new SecureRandom(), log::add);
        identity.bootstrap(java.util.Optional.empty());
        generated = log.get(0).lines().filter(l -> l.contains("password:")).findFirst().orElseThrow()
                .replaceFirst(".*password:\\s*", "").trim();
        TagService tags = new TagService(lib.catalog, lib.files, new org.kendar.dlna.curation.PerFormatTagWriter(), lib.reader);
        shuffle = new Shuffle(lib.catalog::current);
        AdminServices svc = new AdminServices(lib.catalog, lib.rescans, tags,
                new FolderService(lib.catalog, lib.files, tags), new PlaylistService(lib.catalog, lib.playlists),
                shuffle, streams, null, () -> 0, new BuildInfo("1.2.3", "abc123", "2026-01-01T00:00:00Z"), lib.clock);
        api = new AdminApi(svc, identity);
    }

    /** A logged-in browser. */
    final class Client {
        String cookie;
        String csrf;

        ApiResponse send(ApiRequest r) {
            ApiRequest q = cookie == null ? r : r.withHeader("cookie", cookie);
            if (csrf != null && !q.isRead()) {
                q = q.withHeader("x-csrf-token", csrf);
            }
            return api.handle(q);
        }

        ApiResponse get(String path) {
            return send(ApiRequest.of("GET", path));
        }

        ApiResponse get(String path, String... query) {
            ApiRequest r = ApiRequest.of("GET", path);
            for (int i = 0; i < query.length; i += 2) {
                r = r.withQuery(query[i], query[i + 1]);
            }
            return send(r);
        }

        ApiResponse write(String method, String path, Object body, String... query) {
            ApiRequest r = ApiRequest.of(method, path);
            for (int i = 0; i < query.length; i += 2) {
                r = r.withQuery(query[i], query[i + 1]);
            }
            return send(body == null ? r : r.withJson(body));
        }
    }

    private ApiRequest loginRequest(String user, String pw) {
        return ApiRequest.of("POST", "/auth/login").withHeader("x-requested-with", "kendar")
                .withJson(Map.of("username", user, "password", pw));
    }

    private Client login(String pw) {
        Client c = new Client();
        ApiResponse r = api.handle(loginRequest("admin", pw));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        c.cookie = r.setCookies().get(0).split(";")[0];
        c.csrf = r.jsonBody().get("csrfToken").asText();
        return c;
    }

    /** Logged in with a chosen password (forced change done). */
    private Client ready() {
        Client c = login(generated);
        ApiResponse r = c.write("POST", "/me/password", Map.of("currentPassword", generated,
                "newPassword", "a-chosen-password"));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return c;
    }

    // ---- login, forced change, logout ----

    @Test
    void unauthenticatedRequestsGet401WithTheErrorModel() {
        ApiResponse r = api.handle(ApiRequest.of("GET", "/admin/folders/tree"));
        assertThat(r.status()).isEqualTo(401);
        JsonNode e = r.jsonBody();
        assertThat(e.get("code").asText()).isEqualTo("unauthenticated");
        assertThat(e.has("message")).isTrue();
        assertThat(e.has("details")).isTrue();
        assertThat(api.handle(ApiRequest.of("GET", "/admin/version")).status()).isEqualTo(401);
        assertThat(api.handle(ApiRequest.of("GET", "/auth/session")).jsonBody().get("authenticated").asBoolean())
                .isFalse();
    }

    @Test
    void forgedCookieIsRejected() {
        ApiResponse r = api.handle(ApiRequest.of("GET", "/admin/folders/tree").withHeader("cookie", "kd_session=forged"));
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void loginSetsAHardenedCookie() {
        ApiResponse r = api.handle(loginRequest("admin", generated));
        assertThat(r.status()).isEqualTo(200);
        String cookie = r.setCookies().get(0);
        assertThat(cookie).startsWith("kd_session=").contains("HttpOnly").contains("SameSite=Strict")
                .doesNotContain("Secure");
        ApiResponse secure = api.handle(new ApiRequest("POST", "/auth/login", null,
                Map.of("x-requested-with", "kendar"), loginRequest("admin", generated).body(), "1.2.3.4", true));
        assertThat(secure.setCookies().get(0)).contains("Secure");
    }

    @Test
    void firstLoginForcesAPasswordChange() {
        Client c = login(generated);
        JsonNode st = c.get("/auth/session").jsonBody();
        assertThat(st.get("mustChange").asBoolean()).isTrue();
        // everything but the session endpoints is blocked
        ApiResponse blocked = c.get("/admin/folders/tree");
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.jsonBody().get("code").asText()).isEqualTo("password_change_required");
        assertThat(c.get("/admin/version").status()).isEqualTo(403);

        // wrong current password / weak / same
        assertThat(c.write("POST", "/me/password", Map.of("currentPassword", "x", "newPassword",
                "a-chosen-password")).jsonBody().get("code").asText()).isEqualTo("wrong_password");
        assertThat(c.write("POST", "/me/password", Map.of("currentPassword", generated, "newPassword",
                "short")).jsonBody().get("code").asText()).isEqualTo("weak_password");

        ApiResponse ok = c.write("POST", "/me/password", Map.of("currentPassword", generated,
                "newPassword", "a-chosen-password"));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.jsonBody().get("mustChange").asBoolean()).isFalse();
        assertThat(c.get("/admin/folders/tree").status()).isEqualTo(200);
        // the old password no longer works, the new one does
        assertThat(api.handle(loginRequest("admin", generated)).status()).isEqualTo(401);
        assertThat(api.handle(loginRequest("admin", "a-chosen-password")).status()).isEqualTo(200);
    }

    @Test
    void changingThePasswordSignsOutOtherSessions() {
        Client a = ready();
        Client b = login("a-chosen-password");
        assertThat(b.get("/admin/folders/tree").status()).isEqualTo(200);
        ApiResponse r = a.write("POST", "/me/password", Map.of("currentPassword", "a-chosen-password",
                "newPassword", "another-chosen-password"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(a.get("/admin/folders/tree").status()).isEqualTo(200);
        assertThat(b.get("/admin/folders/tree").status()).isEqualTo(401);
    }

    @Test
    void resetEndsSessionsAndForcesAChangeAgain() {
        Client c = ready();
        identity.resetAdminPassword();
        assertThat(c.get("/admin/folders/tree").status()).isEqualTo(401);
        assertThat(identity.userByName("admin").orElseThrow().mustChange()).isTrue();
    }

    @Test
    void loginNeedsTheCustomHeaderAndRightCredentials() {
        ApiRequest noHeader = ApiRequest.of("POST", "/auth/login").withJson(Map.of("username", "admin", "password",
                generated));
        assertThat(api.handle(noHeader).status()).isEqualTo(403);
        assertThat(api.handle(loginRequest("admin", "wrong")).status()).isEqualTo(401);
        assertThat(api.handle(loginRequest("nobody", generated)).status()).isEqualTo(401);
        assertThat(api.handle(loginRequest("admin", "wrong")).jsonBody().get("code").asText())
                .isEqualTo("invalid_credentials");
        ApiRequest bad = ApiRequest.of("POST", "/auth/login").withHeader("x-requested-with", "kendar").withBody(
                "nope".getBytes());
        assertThat(api.handle(bad).status()).isEqualTo(400);
    }

    @Test
    void crossOriginLoginIsRefused() {
        ApiRequest r = loginRequest("admin", generated).withHeader("origin", "http://evil.example")
                .withHeader("host", "music.local:8200");
        assertThat(api.handle(r).status()).isEqualTo(403);
        ApiRequest same = loginRequest("admin", generated).withHeader("origin", "http://music.local:8200")
                .withHeader("host", "music.local:8200");
        assertThat(api.handle(same).status()).isEqualTo(200);
    }

    @Test
    void loginIsThrottledPerAddress() {
        for (int i = 0; i < LoginThrottle.DEFAULT_MAX_FAILURES; i++) {
            assertThat(api.handle(loginRequest("admin", "bad" + i).from("9.9.9.9")).status()).isEqualTo(401);
        }
        ApiResponse blocked = api.handle(loginRequest("admin", generated).from("9.9.9.9"));
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.jsonBody().get("code").asText()).isEqualTo("too_many_attempts");
        assertThat(blocked.headers()).containsKey("Retry-After");
        // another address is fine
        assertThat(api.handle(loginRequest("admin", generated).from("8.8.8.8")).status()).isEqualTo(200);
        // after the window the address may try again
        lib.clock.advance(Duration.ofMinutes(16));
        assertThat(api.handle(loginRequest("admin", generated).from("9.9.9.9")).status()).isEqualTo(200);
    }

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() {
        Client c = ready();
        ApiResponse r = c.write("POST", "/auth/logout", null);
        assertThat(r.status()).isEqualTo(204);
        assertThat(r.setCookies().get(0)).contains("Max-Age=0");
        assertThat(c.get("/admin/folders/tree").status()).isEqualTo(401);
    }

    // ---- CSRF ----

    @Test
    void writesNeedTheCsrfToken() {
        Client c = ready();
        Map<String, Object> body = Map.of("sort", "title");
        String saved = c.csrf;

        c.csrf = null;
        ApiResponse missing = c.write("PUT", "/admin/folders/settings", body, "path", "main/Album");
        assertThat(missing.status()).isEqualTo(403);
        assertThat(missing.jsonBody().get("code").asText()).isEqualTo("csrf");

        c.csrf = "wrong-token";
        assertThat(c.write("PUT", "/admin/folders/settings", body, "path", "main/Album").status()).isEqualTo(403);
        assertThat(c.write("POST", "/admin/playlists", Map.of("name", "x")).status()).isEqualTo(403);
        assertThat(c.write("POST", "/admin/rescan", null).status()).isEqualTo(403);
        assertThat(lib.music.resolve("Album/settings.json")).doesNotExist();
        assertThat(lib.catalog.current().playlists()).isEmpty();

        c.csrf = saved;
        assertThat(c.write("PUT", "/admin/folders/settings", body, "path", "main/Album").status()).isEqualTo(200);
        // reads need no token
        c.csrf = null;
        assertThat(c.get("/admin/folders/tree").status()).isEqualTo(200);
    }

    @Test
    void crossOriginWritesAreRefusedEvenWithAToken() {
        Client c = ready();
        ApiRequest r = ApiRequest.of("POST", "/admin/playlists").withJson(Map.of("name", "x"))
                .withHeader("origin", "http://evil.example").withHeader("host", "music.local:8200");
        assertThat(c.send(r).status()).isEqualTo(403);
    }

    // ---- folders ----

    @Test
    void treeAndContents() {
        Client c = ready();
        JsonNode tree = c.get("/admin/folders/tree").jsonBody();
        assertThat(tree.get("roots")).hasSize(1);
        JsonNode root = tree.get("roots").get(0);
        assertThat(root.get("path").asText()).isEqualTo("main");
        assertThat(root.get("children")).extracting(n -> n.get("name").asText()).containsExactly("Album", "Other");

        JsonNode f = c.get("/admin/folders/contents", "path", "main/Album").jsonBody();
        assertThat(f.get("trackCount").asInt()).isEqualTo(4);
        assertThat(f.get("settings").get("sort").asText()).isEqualTo("filename");
        assertThat(f.get("children")).extracting(n -> n.get("name").asText())
                .containsExactly("01 one.wav", "02 two.flac", "03 three.mp3", "04 four.mid");
        JsonNode midi = f.get("children").get(3).get("track");
        assertThat(midi.get("format").asText()).isEqualTo("midi");
        assertThat(midi.get("editable").asBoolean()).isTrue();
        assertThat(midi.get("editableFields")).extracting(JsonNode::asText).containsExactly("title", "artist", "year");
        assertThat(f.get("children").get(0).get("track").get("editableFields")).hasSize(10);
        assertThat(f.get("children").get(0).get("track").get("editable").asBoolean()).isTrue();

        assertThat(c.get("/admin/folders/contents", "path", "main/Nope").status()).isEqualTo(404);
        assertThat(c.get("/admin/folders/contents", "path", "main/../x").status()).isEqualTo(400);
        assertThat(c.get("/admin/folders/contents").status()).isEqualTo(400);
    }

    @Test
    void settingsEditIsReflectedAndValidated() {
        Client c = ready();
        ApiResponse r = c.write("PUT", "/admin/folders/settings", Map.of("sort", "explicit", "order",
                List.of("03 three.mp3", "01 one.wav"), "random", 7, "views", List.of("artist", "album"), "album",
                Map.of("title", "Edited", "year", 1984)), "path", "main/Album");
        assertThat(r.status()).as(r.text()).isEqualTo(200);

        JsonNode f = c.get("/admin/folders/contents", "path", "main/Album").jsonBody();
        assertThat(f.get("settings").get("sort").asText()).isEqualTo("explicit");
        assertThat(f.get("settings").get("random").asInt()).isEqualTo(7);
        assertThat(f.get("settings").get("views")).extracting(JsonNode::asText).containsExactly("artist", "album");
        assertThat(f.get("displayName").asText()).isEqualTo("Edited");
        assertThat(f.get("children").get(0).get("name").asText()).isEqualTo("03 three.mp3");
        assertThat(f.get("outOfSync").asBoolean()).isTrue();   // overlay differs from the (empty) tags

        ApiResponse bad = c.write("PUT", "/admin/folders/settings", Map.of("sort", "nonsense"), "path", "main/Album");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.jsonBody().get("code").asText()).isEqualTo("invalid");
    }

    @Test
    void syncFromTagsReportsConflictsWith409AndApplyToTagsFixesOutOfSync() {
        Client c = ready();
        c.write("PUT", "/admin/tracks", Map.of("album", "A"), "path", "main/Album/01 one.wav");
        c.write("PUT", "/admin/tracks", Map.of("album", "B"), "path", "main/Album/02 two.flac");
        ApiResponse r = c.write("POST", "/admin/folders/sync-from-tags", null, "path", "main/Album");
        assertThat(r.status()).isEqualTo(409);
        JsonNode e = r.jsonBody();
        assertThat(e.get("code").asText()).isEqualTo("sync_conflict");
        JsonNode conflict = e.get("details").get("conflicts").get(0);
        assertThat(conflict.get("field").asText()).isEqualTo("album");
        assertThat(conflict.get("values")).hasSize(2);
        assertThat(conflict.get("values").get(0).get("files").get(0).asText()).isEqualTo("01 one.wav");
        assertThat(lib.music.resolve("Album/settings.json")).doesNotExist();

        c.write("PUT", "/admin/folders/settings", Map.of("album", Map.of("title", "Unified")), "path", "main/Album");
        // writing needs the explicit list of confirmed files: no body / no list is refused
        assertThat(c.write("POST", "/admin/folders/sync-to-tags", null, "path", "main/Album").status()).isEqualTo(400);
        assertThat(c.write("POST", "/admin/folders/sync-to-tags", Map.of(), "path", "main/Album").status()).isEqualTo(400);
        assertThat(c.write("POST", "/admin/folders/sync-to-tags", Map.of("files", java.util.List.of()), "path",
                "main/Album").status()).isEqualTo(400);
        assertThat(c.write("POST", "/admin/folders/sync-to-tags", Map.of("files", java.util.List.of("nope.wav")), "path",
                "main/Album").status()).isEqualTo(400);
        ApiResponse apply = c.write("POST", "/admin/folders/sync-to-tags", Map.of("files", java.util.List.of(
                "01 one.wav", "02 two.flac", "03 three.mp3", "04 four.mid")), "path", "main/Album");
        assertThat(apply.status()).as(apply.text()).isEqualTo(200);
        assertThat(apply.jsonBody().get("skipped")).extracting(JsonNode::asText).containsExactly("04 four.mid");
        assertThat(c.get("/admin/folders/contents", "path", "main/Album").jsonBody().get("outOfSync").asBoolean())
                .isFalse();
        assertThat(c.write("POST", "/admin/folders/sync-from-tags", null, "path", "main/Album").status()).isEqualTo(200);
    }

    @Test
    void artUploadValidatesAndRearIsServedBack() throws IOException {
        Client c = ready();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", png);

        ApiResponse rear = c.send(ApiRequest.of("PUT", "/admin/folders/art").withQuery("path", "main/Album")
                .withQuery("kind", "rear").withBody(png.toByteArray()));
        assertThat(rear.status()).as(rear.text()).isEqualTo(200);
        assertThat(rear.jsonBody().get("hasRear").asBoolean()).isTrue();

        ApiResponse got = c.get("/admin/folders/art", "path", "main/Album", "kind", "rear");
        assertThat(got.status()).isEqualTo(200);
        assertThat(got.contentType()).isEqualTo("image/jpeg");
        assertThat(got.body()).isEqualTo(Files.readAllBytes(lib.music.resolve("Album/rear.jpg")));
        assertThat(c.get("/admin/folders/art", "path", "main/Album", "kind", "front").status()).isEqualTo(404);

        ApiResponse junk = c.send(ApiRequest.of("PUT", "/admin/folders/art").withQuery("path", "main/Album")
                .withQuery("kind", "front").withBody("not an image".getBytes()));
        assertThat(junk.status()).isEqualTo(400);
        assertThat(c.get("/admin/folders/art", "path", "main/Album", "kind", "side").status()).isEqualTo(400);
        assertThat(c.get("/admin/folders/art", "path", "main/Album").status()).isEqualTo(400);
    }

    // ---- tracks ----

    @Test
    void singleAndBatchTagEdits() {
        Client c = ready();
        ApiResponse one = c.write("PUT", "/admin/tracks", Map.of("title", "Renamed", "year", "1999"), "path",
                "main/Album/03 three.mp3");
        assertThat(one.status()).as(one.text()).isEqualTo(200);
        assertThat(one.jsonBody().get("title").asText()).isEqualTo("Renamed");
        assertThat(c.get("/admin/tracks", "path", "main/Album/03 three.mp3").jsonBody().get("year").asInt())
                .isEqualTo(1999);

        ApiResponse batch = c.write("POST", "/admin/tracks/batch", Map.of("paths", List.of("main/Album/01 one.wav",
                "main/Album/04 four.mid", "main/Album/02 two.flac"), "edit", Map.of("genre", "Blues")));
        assertThat(batch.status()).isEqualTo(200);
        JsonNode b = batch.jsonBody();
        assertThat(b.get("failed").asInt()).isZero();   // the MIDI file just skips the genre it cannot hold
        assertThat(c.get("/admin/tracks", "path", "main/Album/02 two.flac").jsonBody().get("genre").asText())
                .isEqualTo("Blues");

        // MIDI single edit: title and artist are written into the file
        ApiResponse midi = c.write("PUT", "/admin/tracks", Map.of("title", "Midi Title", "artist", "Midi Artist"), "path",
                "main/Album/04 four.mid");
        assertThat(midi.status()).as(midi.text()).isEqualTo(200);
        assertThat(midi.jsonBody().get("title").asText()).isEqualTo("Midi Title");
        assertThat(midi.jsonBody().get("artist").asText()).isEqualTo("Midi Artist");
        assertThat(c.write("PUT", "/admin/tracks", Map.of("year", "abc"), "path", "main/Album/01 one.wav").status())
                .isEqualTo(400);
        assertThat(c.write("PUT", "/admin/tracks", Map.of("title", "x"), "path", "main/Album/zzz.wav").status())
                .isEqualTo(404);
        assertThat(c.write("POST", "/admin/tracks/batch", Map.of("paths", List.of(), "edit", Map.of("genre", "x")))
                .status()).isEqualTo(400);
        assertThat(c.get("/admin/tracks", "path", "main/Album/zzz.wav").status()).isEqualTo(404);
    }

    // ---- keepWholeFiles and list.json sections ----

    @Test
    void keepWholeFilesRoundTripsThroughTheSettingsApi() {
        Client c = ready();
        assertThat(c.get("/admin/folders/settings", "path", "main/Album").jsonBody().get("settings")
                .get("keepWholeFiles").asBoolean()).isFalse();
        assertThat(c.write("PUT", "/admin/folders/settings", Map.of("sort", "title", "keepWholeFiles", true), "path",
                "main/Album").status()).isEqualTo(200);
        JsonNode s = c.get("/admin/folders/contents", "path", "main/Album").jsonBody().get("settings");
        assertThat(s.get("keepWholeFiles").asBoolean()).isTrue();
        assertThat(s.get("sort").asText()).isEqualTo("title");
        assertThat(lib.music.resolve("Album/settings.json")).content().contains("\"keepWholeFiles\"");

        // a client that does not send the field does not lose it; an explicit false clears it
        c.write("PUT", "/admin/folders/settings", Map.of("sort", "filename"), "path", "main/Album");
        assertThat(c.get("/admin/folders/settings", "path", "main/Album").jsonBody().get("settings")
                .get("keepWholeFiles").asBoolean()).isTrue();
        c.write("PUT", "/admin/folders/settings", Map.of("keepWholeFiles", false), "path", "main/Album");
        assertThat(c.get("/admin/folders/settings", "path", "main/Album").jsonBody().get("settings")
                .get("keepWholeFiles").asBoolean()).isFalse();
        assertThat(lib.music.resolve("Album/settings.json")).content().doesNotContain("keepWholeFiles");
        assertThat(c.write("PUT", "/admin/folders/settings", Map.of("keepWholeFiles", "yes"), "path", "main/Album")
                .status()).isEqualTo(400);
    }

    private void cueDirectory() {
        org.kendar.dlna.testutil.CueFixtures.write(lib.music.resolve("Cues"));
        lib.catalog.commit(lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null,
                new org.kendar.dlna.ingestion.ScanProgress(), false));
    }

    @Test
    void generateProposesEntriesFromTheCueSheetAndWritesNothing() {
        cueDirectory();
        Client c = ready();
        ApiResponse r = c.write("POST", "/admin/folders/list/generate", null, "path", "main/Cues");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode body = r.jsonBody();
        assertThat(body.get("entries")).hasSize(3);
        JsonNode second = body.get("entries").get(1);
        assertThat(second.get("filename").asText()).isEqualTo("disc.wav");
        assertThat(second.get("index").asInt()).isEqualTo(2);
        assertThat(second.get("start").asText()).isEqualTo("00:03.493");
        assertThat(second.get("title").asText()).isEqualTo("Middle");
        assertThat(second.get("author").asText()).isEqualTo("Guest");
        assertThat(second.get("partOf").asText()).isEqualTo("Live Album");
        assertThat(body.get("sources").get(0).get("origin").asText()).isEqualTo("cue");
        assertThat(body.get("sources").get(0).get("cueFile").asText()).isEqualTo("disc.cue");
        assertThat(body.get("sources").get(0).get("tracks").asInt()).isEqualTo(3);
        assertThat(body.get("warnings")).isEmpty();
        assertThat(lib.music.resolve("Cues/list.json")).doesNotExist();   // preview only

        assertThat(c.write("POST", "/admin/folders/list/generate", null, "path", "main/Album").jsonBody()
                .get("entries")).isEmpty();
        assertThat(c.write("POST", "/admin/folders/list/generate", null, "path", "main/Nope").status()).isEqualTo(404);
        assertThat(c.write("POST", "/admin/folders/list/generate", null).status()).isEqualTo(400);
        assertThat(c.get("/admin/folders/list/generate", "path", "main/Cues").status()).isNotEqualTo(200);
        assertThat(c.write("PUT", "/admin/folders/list/generate", null, "path", "main/Cues").status())
                .isNotEqualTo(200);
    }

    @Test
    void generateProposesEntriesFromEmbeddedChaptersAndTheyOverrideTitlesInTheLibraryApi() {
        org.kendar.dlna.testutil.ChapterFiles.mp3Chapters(lib.music.resolve("book.mp3"), true,
                new org.kendar.dlna.testutil.ChapterFiles.Chap("a", 0, 700, "First"),
                new org.kendar.dlna.testutil.ChapterFiles.Chap("b", 700, 2000, "Second"));
        lib.catalog.commit(lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null,
                new org.kendar.dlna.ingestion.ScanProgress(), false));
        Client c = ready();
        ApiResponse r = c.write("POST", "/admin/folders/list/generate", null, "path", "main");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode body = r.jsonBody();
        assertThat(body.get("entries")).hasSize(2);
        assertThat(body.get("entries").get(1).get("index").asInt()).isEqualTo(2);
        assertThat(body.get("entries").get(1).get("start").asText()).isEqualTo("00:00.700");
        assertThat(body.get("entries").get(1).get("title").asText()).isEqualTo("Second");
        assertThat(body.get("sources").get(0).get("origin").asText()).isEqualTo("chapters");

        ApiResponse put = c.write("PUT", "/admin/folders/list", Map.of("version", "none", "entries", java.util.List.of(
                Map.of("filename", "book.mp3", "start", "00:00.900", "title", "Renamed"))), "path", "main");
        assertThat(put.status()).as(put.text()).isEqualTo(200);
        assertThat(c.get("/library/folders/contents", "path", "main/book.mp3").text()).contains("First")
                .contains("Renamed").doesNotContain("Second");
    }

    @Test
    void generateNeedsAnAdminSessionAndTheCsrfToken() {
        cueDirectory();
        assertThat(api.handle(ApiRequest.of("POST", "/admin/folders/list/generate").withQuery("path", "main/Cues"))
                .status()).isEqualTo(401);
        Client c = ready();
        c.csrf = "wrong";
        ApiResponse r = c.write("POST", "/admin/folders/list/generate", null, "path", "main/Cues");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.jsonBody().get("code").asText()).isEqualTo("csrf");
    }

    @Test
    void generatedEntriesSaveThroughThePutEndpointAndComeBackWithIndexAndStart() {
        cueDirectory();
        Client c = ready();
        JsonNode proposal = c.write("POST", "/admin/folders/list/generate", null, "path", "main/Cues").jsonBody();
        ApiResponse saved = c.write("PUT", "/admin/folders/list", Map.of("version", "none", "entries",
                proposal.get("entries")), "path", "main/Cues");
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        JsonNode got = c.get("/admin/folders/list", "path", "main/Cues").jsonBody();
        assertThat(got.get("entries")).hasSize(3);
        assertThat(got.get("entries").get(2).get("index").asInt()).isEqualTo(3);
        assertThat(got.get("entries").get(2).get("start").asText()).isEqualTo("00:06.000");
        assertThat(got.get("files")).extracting(JsonNode::asText).contains("disc.wav");
        assertThat(got.get("unknown")).isEmpty();

        // a whole-file entry is a different entry; bad locators are refused
        ApiResponse bad = c.write("PUT", "/admin/folders/list", Map.of("entries", List.of(
                Map.of("filename", "disc.wav", "index", 0, "title", "x"))), "path", "main/Cues");
        assertThat(bad.status()).isEqualTo(400);
        ApiResponse notNumber = c.write("PUT", "/admin/folders/list", Map.of("entries", List.of(
                Map.of("filename", "disc.wav", "index", "abc", "title", "x"))), "path", "main/Cues");
        assertThat(notNumber.status()).isEqualTo(400);
        assertThat(notNumber.jsonBody().get("code").asText()).isEqualTo("bad_request");
        ApiResponse badStart = c.write("PUT", "/admin/folders/list", Map.of("entries", List.of(
                Map.of("filename", "disc.wav", "start", "later", "title", "x"))), "path", "main/Cues");
        assertThat(badStart.status()).isEqualTo(400);
    }

    // ---- playlists ----

    @Test
    void playlistLifecycle() {
        Client c = ready();
        ApiResponse created = c.write("POST", "/admin/playlists", Map.of("name", "Evening"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.jsonBody().get("key").asText()).isEqualTo("evening");
        assertThat(c.write("POST", "/admin/playlists", Map.of("name", " ")).status()).isEqualTo(400);

        ApiResponse added = c.write("POST", "/admin/playlists/evening/songs", Map.of("paths",
                List.of("main/Album/01 one.wav", "main/Album/02 two.flac", "main/Album/03 three.mp3")));
        assertThat(added.status()).as(added.text()).isEqualTo(200);
        assertThat(added.jsonBody().get("entries")).hasSize(3);

        ApiResponse reordered = c.write("PUT", "/admin/playlists/evening", Map.of("songs",
                List.of("main/Album/03 three.mp3", "main/Album/gone.mp3", "main/Album/01 one.wav"), "name", "Late"));
        assertThat(reordered.status()).as(reordered.text()).isEqualTo(200);
        JsonNode d = reordered.jsonBody();
        assertThat(d.get("name").asText()).isEqualTo("Late");
        assertThat(d.get("entries").get(1).get("status").asText()).isEqualTo("missing");
        assertThat(d.get("broken").asInt()).isEqualTo(1);

        ApiResponse fixed = c.write("POST", "/admin/playlists/evening/fix-path", Map.of("index", 1, "path",
                "main/Album/02 two.flac"));
        assertThat(fixed.jsonBody().get("broken").asInt()).isZero();

        JsonNode list = c.get("/admin/playlists").jsonBody().get("playlists");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("count").asInt()).isEqualTo(3);

        assertThat(c.get("/admin/playlists/none").status()).isEqualTo(404);
        assertThat(c.write("POST", "/admin/playlists/evening/songs", Map.of("paths", List.of("main/x.mp3"))).status())
                .isEqualTo(404);
        assertThat(c.write("DELETE", "/admin/playlists/evening", null).status()).isEqualTo(204);
        assertThat(c.get("/admin/playlists").jsonBody().get("playlists")).isEmpty();
        assertThat(lib.playlists.resolve("evening.json")).doesNotExist();
    }

    // ---- search ----

    @Test
    void searchUsesTheSharedEngine() {
        Client c = ready();
        c.write("PUT", "/admin/tracks", Map.of("artist", "Zebra Quartet"), "path", "main/Album/01 one.wav");
        JsonNode r = c.get("/admin/search", "q", "zebra", "kind", "track").jsonBody();
        assertThat(r.get("total").asInt()).isEqualTo(1);
        assertThat(r.get("hits").get(0).get("path").asText()).isEqualTo("main/Album/01 one.wav");
        JsonNode folders = c.get("/admin/search", "q", "album", "kind", "folder").jsonBody();
        assertThat(folders.get("hits").get(0).get("type").asText()).isEqualTo("folder");
        assertThat(c.get("/admin/search", "q", "four", "scope", "main/Other").jsonBody().get("total").asInt()).isZero();
        assertThat(c.get("/admin/search", "q", "four").jsonBody().get("total").asInt()).isEqualTo(1);
    }

    // ---- admin ----

    @Test
    void rescanForceRereadsUnchangedFilesOnlyWhenAsked() throws Exception {
        Client c = ready();
        ApiResponse plain = c.write("POST", "/admin/rescan", Map.of("path", "main/Album"));
        assertThat(plain.status()).isEqualTo(202);
        assertThat(plain.jsonBody().get("force").asBoolean()).isFalse();
        for (int i = 0; i < 100 && lib.rescans.isRunning(); i++) {
            Thread.sleep(50);
        }
        JsonNode last = c.get("/admin/scan").jsonBody().get("last");
        assertThat(last.get("tracksRead").asInt()).isZero();
        assertThat(last.get("tracksReused").asInt()).isPositive();

        ApiResponse forced = c.write("POST", "/admin/rescan", Map.of("path", "main/Album", "force", true));
        assertThat(forced.status()).isEqualTo(202);
        assertThat(forced.jsonBody().get("force").asBoolean()).isTrue();
        for (int i = 0; i < 100 && lib.rescans.isRunning(); i++) {
            Thread.sleep(50);
        }
        last = c.get("/admin/scan").jsonBody().get("last");
        assertThat(last.get("tracksReused").asInt()).isZero();
        assertThat(last.get("tracksRead").asInt()).isPositive();

        assertThat(c.write("POST", "/admin/rescan", Map.of("force", true)).status()).isEqualTo(202);
        for (int i = 0; i < 100 && lib.rescans.isRunning(); i++) {
            Thread.sleep(50);
        }
        assertThat(c.get("/admin/scan").jsonBody().get("last").get("tracksRead").asInt()).isEqualTo(4);
    }

    @Test
    void rescanAndStatus() throws Exception {
        Client c = ready();
        ApiResponse r = c.write("POST", "/admin/rescan", null);
        assertThat(r.status()).isEqualTo(202);
        for (int i = 0; i < 100 && lib.rescans.isRunning(); i++) {
            Thread.sleep(50);
        }
        JsonNode s = c.get("/admin/scan").jsonBody();
        assertThat(s.get("running").asBoolean()).isFalse();
        assertThat(s.get("last").get("trigger").asText()).isEqualTo("MANUAL");
        assertThat(s.get("trackCount").asInt()).isEqualTo(4);

        assertThat(c.write("POST", "/admin/rescan", Map.of("path", "main/Album")).status()).isEqualTo(202);
        for (int i = 0; i < 100 && lib.rescans.isRunning(); i++) {
            Thread.sleep(50);
        }
        assertThat(c.get("/admin/scan").jsonBody().get("last").get("scope").asText()).isEqualTo("main/Album");
        assertThat(c.write("POST", "/admin/rescan", Map.of("path", "nope/Dir")).status()).isEqualTo(404);
    }

    @Test
    void unsupportedInvalidSettingsStreamsSnapshotsAndVersion() throws IOException {
        Files.writeString(lib.music.resolve("Other/settings.json"), "{ broken");
        Files.writeString(lib.music.resolve("Other/song.m4a"), "x");
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        Client c = ready();

        JsonNode inv = c.get("/admin/invalid-settings").jsonBody().get("folders");
        assertThat(inv).hasSize(1);
        assertThat(inv.get(0).get("path").asText()).isEqualTo("main/Other");
        JsonNode un = c.get("/admin/unsupported").jsonBody().get("files");
        assertThat(un).extracting(n -> n.get("path").asText()).contains("main/Other/song.m4a");

        String trackId = lib.catalog.current().tracks().get(0).id();
        try (ActiveStreams.Handle h = streams.open("10.0.0.5", "f.x", trackId, "mp3")) {
            JsonNode st = c.get("/admin/streams").jsonBody();
            assertThat(st.get("streams")).hasSize(1);
            assertThat(st.get("streams").get(0).get("transcoded").asBoolean()).isTrue();
            assertThat(st.get("streams").get(0).get("path").asText()).startsWith("main/Album/");
            assertThat(st.get("transcodeCache").isNull()).isTrue();
        }
        assertThat(c.get("/admin/streams").jsonBody().get("streams")).isEmpty();

        // a Random N snapshot shows up
        c.write("PUT", "/admin/folders/settings", Map.of("random", 3), "path", "main/Album");
        shuffle.open(new ClientId("10.0.0.9", "VLC"), lib.catalog.current().folder(
                LibraryFixture.ref("main/Album")).orElseThrow());
        JsonNode snaps = c.get("/admin/snapshots").jsonBody().get("snapshots");
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).get("directory").asText()).isEqualTo("main/Album");
        assertThat(snaps.get(0).get("clientIp").asText()).isEqualTo("10.0.0.9");

        JsonNode v = c.get("/admin/version").jsonBody();
        assertThat(v.get("version").asText()).isEqualTo("1.2.3");
        assertThat(v.get("commit").asText()).isEqualTo("abc123");
    }

    @Test
    void unknownResourcesGetAJson404() {
        Client c = ready();
        ApiResponse r = c.get("/nothing/here");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.jsonBody().get("code").asText()).isEqualTo("not_found");
        assertThat(c.write("DELETE", "/admin/folders/tree", null).status()).isEqualTo(404);
    }

    // ---- list.json ----

    @Test
    void listJsonRoundTripThroughTheApi() throws IOException {
        Client c = ready();
        JsonNode v = c.get("/admin/folders/list", "path", "main/Album").jsonBody();
        assertThat(v.get("exists").asBoolean()).isFalse();
        assertThat(v.get("version").asText()).isEqualTo("none");
        assertThat(v.get("missing")).extracting(JsonNode::asText)
                .containsExactly("01 one.wav", "02 two.flac", "03 three.mp3", "04 four.mid");

        ApiResponse saved = c.write("PUT", "/admin/folders/list", Map.of("version", "none", "entries", List.of(
                Map.of("filename", "04 four.mid", "author", "A", "partOf", "P", "title", "Shown", "transcriber", "T"))),
                "path", "main/Album");
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        JsonNode s = saved.jsonBody();
        assertThat(s.get("exists").asBoolean()).isTrue();
        assertThat(s.get("entries")).hasSize(1);
        assertThat(s.get("missing")).hasSize(3);
        assertThat(lib.music.resolve("Album/list.json")).exists();

        // GET returns what was written, and the track shows the new title everywhere
        JsonNode again = c.get("/admin/folders/list", "path", "main/Album").jsonBody();
        assertThat(again.get("entries").get(0).get("title").asText()).isEqualTo("Shown");
        assertThat(again.get("version").asText()).isEqualTo(s.get("version").asText());
        JsonNode tr = c.get("/admin/tracks", "path", "main/Album/04 four.mid").jsonBody();
        assertThat(tr.get("displayTitle").asText()).isEqualTo("Shown");
        assertThat(tr.get("artist").asText()).isEqualTo("A");
        assertThat(tr.get("album").asText()).isEqualTo("P");
        assertThat(tr.get("transcriber").asText()).isEqualTo("T");
        JsonNode contents = c.get("/admin/folders/contents", "path", "main/Album").jsonBody();
        assertThat(contents.get("hasList").asBoolean()).isTrue();
        assertThat(contents.get("listError").isNull()).isTrue();

        // removing every entry deletes the file
        ApiResponse gone = c.write("PUT", "/admin/folders/list",
                Map.of("version", s.get("version").asText(), "entries", List.of()), "path", "main/Album");
        assertThat(gone.status()).isEqualTo(200);
        assertThat(lib.music.resolve("Album/list.json")).doesNotExist();
        assertThat(c.get("/admin/tracks", "path", "main/Album/04 four.mid").jsonBody().get("displayTitle").asText())
                .isEqualTo("Four");
    }

    @Test
    void listJsonValidationAndConflictsUseTheErrorModel() throws IOException {
        Client c = ready();
        ApiResponse bad = c.write("PUT", "/admin/folders/list", Map.of("entries", List.of(
                Map.of("filename", "missing.mid", "title", "x"))), "path", "main/Album");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.jsonBody().get("code").asText()).isEqualTo("invalid");
        assertThat(c.write("PUT", "/admin/folders/list", Map.of("nothing", 1), "path", "main/Album").status())
                .isEqualTo(400);
        assertThat(c.write("PUT", "/admin/folders/list", Map.of("entries", List.of("x")), "path", "main/Album")
                .status()).isEqualTo(400);
        assertThat(c.get("/admin/folders/list", "path", "main/Nope").status()).isEqualTo(404);
        assertThat(c.get("/admin/folders/list").status()).isEqualTo(400);
        assertThat(c.write("DELETE", "/admin/folders/list", null, "path", "main/Album").status()).isEqualTo(404);
        assertThat(lib.music.resolve("Album/list.json")).doesNotExist();

        Files.writeString(lib.music.resolve("Album/list.json"), "[{\"filename\":\"04 four.mid\",\"title\":\"Theirs\"}]");
        ApiResponse conflict = c.write("PUT", "/admin/folders/list", Map.of("version", "none", "entries",
                List.of(Map.of("filename", "04 four.mid", "title", "Mine"))), "path", "main/Album");
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.jsonBody().get("code").asText()).isEqualTo("conflict");
        assertThat(Files.readString(lib.music.resolve("Album/list.json"))).contains("Theirs");
    }

    @Test
    void listJsonWritesNeedTheCsrfTokenAndALogin() {
        Client c = ready();
        c.csrf = "wrong";
        assertThat(c.write("PUT", "/admin/folders/list", Map.of("entries", List.of()), "path", "main/Album").status())
                .isEqualTo(403);
        assertThat(api.handle(ApiRequest.of("GET", "/admin/folders/list").withQuery("path", "main/Album")).status())
                .isEqualTo(401);
    }

    // ---- per-file sections list (<file>.json) ----

    @Test
    void fileListRoundTripThroughTheApiWritesTheSidecarAndRebuildsTheAlbum() throws IOException {
        cueDirectory();
        Client c = ready();
        JsonNode v = c.get("/admin/folders/list/file", "path", "main/Cues/disc.wav").jsonBody();
        assertThat(v.get("exists").asBoolean()).isFalse();
        assertThat(v.get("version").asText()).isEqualTo("none");
        assertThat(v.get("fileName").asText()).isEqualTo("disc.wav");
        assertThat(v.get("sidecar").asText()).isEqualTo("disc.wav.json");
        assertThat(v.get("split").asBoolean()).isTrue();

        JsonNode proposal = c.write("POST", "/admin/folders/list/file/generate", null, "path", "main/Cues/disc.wav")
                .jsonBody();
        assertThat(proposal.get("entries")).hasSize(3);
        assertThat(proposal.get("sources").get(0).get("origin").asText()).isEqualTo("cue");
        assertThat(lib.music.resolve("Cues/disc.wav.json")).doesNotExist();   // preview only

        ApiResponse saved = c.write("PUT", "/admin/folders/list/file", Map.of("version", "none", "entries", List.of(
                Map.of("partOf", "My live album"),
                Map.of("index", 2, "title", "My middle"))), "path", "main/Cues/disc.wav");
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        JsonNode s = saved.jsonBody();
        assertThat(s.get("exists").asBoolean()).isTrue();
        assertThat(s.get("entries")).hasSize(2);
        assertThat(s.get("entries").get(1).get("filename").asText()).isEqualTo("disc.wav");
        assertThat(lib.music.resolve("Cues/disc.wav.json")).exists();
        assertThat(lib.music.resolve("Cues/list.json")).doesNotExist();   // the directory list stays untouched

        String contents = c.get("/library/folders/contents", "path", "main/Cues/disc.wav").text();
        assertThat(contents).contains("My middle").contains("My live album").contains("Opening");
        assertThat(c.get("/admin/folders/list", "path", "main/Cues").jsonBody().get("sidecars"))
                .extracting(JsonNode::asText).containsExactly("disc.wav");

        ApiResponse gone = c.write("PUT", "/admin/folders/list/file",
                Map.of("version", s.get("version").asText(), "entries", List.of()), "path", "main/Cues/disc.wav");
        assertThat(gone.status()).isEqualTo(200);
        assertThat(lib.music.resolve("Cues/disc.wav.json")).doesNotExist();
        assertThat(c.get("/library/folders/contents", "path", "main/Cues/disc.wav").text()).contains("Middle")
                .doesNotContain("My middle");
    }

    @Test
    void fileListValidationConflictsAndUnknownFilesUseTheErrorModel() throws IOException {
        cueDirectory();
        Client c = ready();
        String f = "main/Cues/disc.wav";
        ApiResponse other = c.write("PUT", "/admin/folders/list/file", Map.of("entries", List.of(
                Map.of("filename", "other.wav", "title", "x"))), "path", f);
        assertThat(other.status()).isEqualTo(400);
        assertThat(other.jsonBody().get("code").asText()).isEqualTo("invalid");
        assertThat(c.write("PUT", "/admin/folders/list/file", Map.of("entries", List.of(
                Map.of("index", 0, "title", "x"))), "path", f).status()).isEqualTo(400);
        assertThat(c.write("PUT", "/admin/folders/list/file", Map.of("entries", List.of(
                Map.of("index", 1, "start", "soon", "title", "x"))), "path", f).status()).isEqualTo(400);
        assertThat(c.write("PUT", "/admin/folders/list/file", Map.of("entries", List.of(
                Map.of("index", 1, "title", "a"), Map.of("index", 1, "title", "b"))), "path", f).status())
                .isEqualTo(400);
        assertThat(c.write("PUT", "/admin/folders/list/file", Map.of("nothing", 1), "path", f).status())
                .isEqualTo(400);
        assertThat(c.get("/admin/folders/list/file").status()).isEqualTo(400);
        assertThat(c.get("/admin/folders/list/file", "path", "main/Cues/nope.wav").status()).isEqualTo(404);
        assertThat(c.get("/admin/folders/list/file", "path", "main/Cues").status()).isEqualTo(404);
        assertThat(c.write("POST", "/admin/folders/list/file/generate", null, "path", "main/Cues/nope.wav").status())
                .isEqualTo(404);
        assertThat(c.write("DELETE", "/admin/folders/list/file", null, "path", f).status()).isEqualTo(404);
        assertThat(lib.music.resolve("Cues/disc.wav.json")).doesNotExist();

        Files.writeString(lib.music.resolve("Cues/disc.wav.json"), "[{\"index\":1,\"title\":\"Theirs\"}]");
        ApiResponse conflict = c.write("PUT", "/admin/folders/list/file", Map.of("version", "none", "entries",
                List.of(Map.of("index", 1, "title", "Mine"))), "path", f);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.jsonBody().get("code").asText()).isEqualTo("conflict");
        assertThat(Files.readString(lib.music.resolve("Cues/disc.wav.json"))).contains("Theirs");
    }

    @Test
    void fileListWritesNeedTheCsrfTokenAndALogin() {
        cueDirectory();
        assertThat(api.handle(ApiRequest.of("GET", "/admin/folders/list/file").withQuery("path", "main/Cues/disc.wav"))
                .status()).isEqualTo(401);
        Client c = ready();
        c.csrf = "wrong";
        assertThat(c.write("PUT", "/admin/folders/list/file", Map.of("entries", List.of(
                Map.of("index", 1, "title", "x"))), "path", "main/Cues/disc.wav").status()).isEqualTo(403);
        assertThat(c.write("POST", "/admin/folders/list/file/generate", null, "path", "main/Cues/disc.wav").status())
                .isEqualTo(403);
        assertThat(lib.music.resolve("Cues/disc.wav.json")).doesNotExist();
    }
}
