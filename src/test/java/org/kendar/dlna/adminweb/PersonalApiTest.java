package org.kendar.dlna.adminweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.curation.FolderService;
import org.kendar.dlna.curation.PlaylistService;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.PasswordHasher;
import org.kendar.dlna.identity.SessionStore;
import org.kendar.dlna.ingestion.ScanTrigger;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.testutil.LibraryFixture;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code /me/playlists|favorites|queue|history}, {@code /shared} and moderation through the REST seam. */
class PersonalApiTest {

    static final String ONE = "main/Album/01 one.wav";
    static final String TWO = "main/Album/02 two.flac";
    static final String THREE = "main/Album/03 three.mp3";
    static final String X = "extra/Disc/x.wav";

    @TempDir
    Path tmp;
    LibraryFixture lib;
    IdentityService identity;
    AdminServices svc;
    AdminApi api;
    final List<String> announced = new ArrayList<>();
    String adminPassword;

    @BeforeEach
    void setUp() throws IOException {
        lib = new LibraryFixture(tmp);
        Path extra = Files.createDirectories(tmp.resolve("extra/Disc"));
        AudioFiles.fixture("sweep.wav", extra.resolve("x.wav"));
        lib.roots.put("extra", tmp.resolve("extra"));
        lib.rescans.rescanNow(ScanTrigger.MANUAL, null);
        SessionStore sessions = new SessionStore(lib.clock);
        identity = new IdentityService(tmp.resolve("users"), List.of("main", "extra"), null,
                new PasswordHasher(1000, new SecureRandom()), sessions, lib.clock, new SecureRandom(), announced::add);
        identity.bootstrap(Optional.empty());
        adminPassword = announced.get(0).lines().filter(l -> l.contains("password:")).findFirst().orElseThrow()
                .replaceFirst(".*password:\\s*", "").trim();
        TagService tags = new TagService(lib.catalog, lib.files, new org.kendar.dlna.curation.PerFormatTagWriter(),
                lib.reader);
        svc = new AdminServices(lib.catalog, lib.rescans, tags, new FolderService(lib.catalog, lib.files, tags),
                new PlaylistService(lib.catalog, lib.playlists), new Shuffle(lib.catalog::current),
                new ActiveStreams(), null, () -> 0, new BuildInfo("1.2.3", "abc123", "2026-01-01T00:00:00Z"),
                lib.clock);
        api = new AdminApi(svc, identity);
    }

    final class Client {
        final Map<String, String> jar = new LinkedHashMap<>();
        String csrf;
        String id;

        ApiResponse send(ApiRequest r) {
            ApiRequest q = r;
            if (!jar.isEmpty()) {
                StringBuilder c = new StringBuilder();
                jar.forEach((k, v) -> c.append(c.length() == 0 ? "" : "; ").append(k).append('=').append(v));
                q = q.withHeader("cookie", c.toString());
            }
            if (csrf != null && !q.isRead()) {
                q = q.withHeader("x-csrf-token", csrf);
            }
            if (!q.isRead()) {
                q = q.withHeader("x-requested-with", "kendar");
            }
            ApiResponse out = api.handle(q);
            for (String sc : out.setCookies()) {
                String nv = sc.split(";")[0];
                int eq = nv.indexOf('=');
                jar.put(nv.substring(0, eq), nv.substring(eq + 1));
            }
            if (out.status() == 200 && out.contentType() != null && out.text().contains("csrfToken")) {
                csrf = out.jsonBody().path("csrfToken").asText(csrf);
                id = out.jsonBody().path("id").asText(id);
            }
            return out;
        }

        ApiResponse get(String path, String... query) {
            ApiRequest r = ApiRequest.of("GET", path);
            for (int i = 0; i < query.length; i += 2) {
                r = r.withQuery(query[i], query[i + 1]);
            }
            return send(r);
        }

        ApiResponse write(String method, String path, Object body) {
            ApiRequest r = ApiRequest.of(method, path);
            return send(body == null ? r : r.withJson(body));
        }

        ApiResponse writeIfMatch(String method, String path, Object body, String ifMatch) {
            ApiRequest r = ApiRequest.of(method, path).withHeader("if-match", ifMatch);
            return send(body == null ? r : r.withJson(body));
        }

        JsonNode ok(ApiResponse r) {
            assertThat(r.status()).as(r.text()).isBetween(200, 201);
            return r.jsonBody();
        }

        JsonNode createPlaylist(String name, String... tracks) {
            return ok(write("POST", "/me/playlists", Map.of("name", name, "tracks", List.of(tracks))));
        }
    }

    private Client adminClient() {
        Client c = new Client();
        assertThat(c.write("POST", "/auth/login", Map.of("username", "admin", "password", adminPassword)).status())
                .isEqualTo(200);
        assertThat(c.write("POST", "/me/password", Map.of("currentPassword", adminPassword, "newPassword",
                "admin-chosen-password")).status()).isEqualTo(200);
        return c;
    }

    private Client userClient(Client admin, String name, List<String> roots) {
        ApiResponse r = admin.write("POST", "/admin/users", Map.of("username", name, "allowedRoots", roots));
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        Client c = new Client();
        ApiResponse acc = c.write("POST", "/auth/invite/accept", Map.of("token",
                r.jsonBody().get("invite").get("token").asText(), "password", name + "-password-1"));
        assertThat(acc.status()).as(acc.text()).isEqualTo(200);
        return c;
    }

    private static List<String> paths(JsonNode entries) {
        List<String> out = new ArrayList<>();
        entries.forEach(e -> out.add(e.path("path").asText()));
        return out;
    }

    // ---- persistence ----

    @Test
    void personalDataIsPersistedAtomicallyAndSurvivesARestart() throws IOException {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        JsonNode pl = lena.createPlaylist("Mix", ONE, TWO);
        String id = pl.get("id").asText();
        lena.ok(lena.write("POST", "/me/favorites", Map.of("paths", List.of(THREE))));
        lena.ok(lena.write("PUT", "/me/queue", Map.of("items", List.of(ONE, TWO), "index", 1, "positionSeconds", 12.5,
                "shuffle", true, "repeat", "all", "deviceId", "phone")));
        lena.ok(lena.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 40)));
        api.personal().flushAll();

        Path dir = identity.dataDir(lena.id);
        assertThat(dir.resolve("playlists").resolve(id + ".json")).exists();
        assertThat(dir.resolve("favorites.json")).exists();
        assertThat(dir.resolve("queue.json")).exists();
        assertThat(dir.resolve("history.json")).exists();
        try (Stream<Path> s = Files.walk(dir)) {
            assertThat(s.filter(p -> p.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }

        api = new AdminApi(svc, identity); // a restart: nothing cached
        JsonNode again = lena.ok(lena.get("/me/playlists/" + id));
        assertThat(paths(again.get("entries"))).containsExactly(ONE, TWO);
        assertThat(again.get("version").asLong()).isEqualTo(1);
        assertThat(paths(lena.ok(lena.get("/me/favorites")).get("entries"))).containsExactly(THREE);
        JsonNode q = lena.ok(lena.get("/me/queue"));
        assertThat(q.get("index").asInt()).isEqualTo(1);
        assertThat(q.get("positionSeconds").asDouble()).isEqualTo(12.5);
        assertThat(q.get("repeat").asText()).isEqualTo("all");
        assertThat(q.get("shuffle").asBoolean()).isTrue();
        assertThat(paths(lena.ok(lena.get("/me/history/recent")).get("entries"))).containsExactly(ONE);
        assertThat(lena.ok(lena.get("/me/history/most-played")).get("entries").get(0).get("playCount").asInt())
                .isEqualTo(1);
    }

    @Test
    void personalEndpointsNeedLoginAndCsrf() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        assertThat(new Client().get("/me/playlists").status()).isEqualTo(401);
        assertThat(new Client().get("/shared/playlists").status()).isEqualTo(401);
        ApiRequest noCsrf = ApiRequest.of("POST", "/me/playlists").withJson(Map.of("name", "x"))
                .withHeader("cookie", "kd_session=" + lena.jar.get("kd_session"));
        assertThat(api.handle(noCsrf).status()).isEqualTo(403);
    }

    // ---- concurrency ----

    @Test
    void staleVersionsAreRefusedWith412OrConflict409() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        String id = lena.createPlaylist("Mix", ONE).get("id").asText();
        ApiResponse get = lena.get("/me/playlists/" + id);
        assertThat(get.headers().get("ETag")).isEqualTo("\"1\"");

        ApiResponse added = lena.writeIfMatch("POST", "/me/playlists/" + id + "/tracks", Map.of("paths", List.of(TWO)),
                "\"1\"");
        assertThat(added.status()).isEqualTo(200);
        assertThat(added.headers().get("ETag")).isEqualTo("\"2\"");

        ApiResponse stale = lena.writeIfMatch("PUT", "/me/playlists/" + id, Map.of("name", "Other"), "\"1\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.jsonBody().get("code").asText()).isEqualTo("precondition_failed");
        assertThat(stale.jsonBody().get("details").get("currentVersion").asLong()).isEqualTo(2);

        ApiResponse conflict = lena.write("PUT", "/me/playlists/" + id, Map.of("name", "Other", "version", 1));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.jsonBody().get("code").asText()).isEqualTo("conflict");

        ApiResponse good = lena.write("PUT", "/me/playlists/" + id, Map.of("name", "Other", "version", 2));
        assertThat(good.status()).isEqualTo(200);
        assertThat(good.jsonBody().get("version").asLong()).isEqualTo(3);
        assertThat(lena.writeIfMatch("DELETE", "/me/playlists/" + id, null, "\"2\"").status()).isEqualTo(412);
        assertThat(lena.writeIfMatch("DELETE", "/me/playlists/" + id, null, "\"3\"").status()).isEqualTo(204);

        ApiRequest inm = ApiRequest.of("GET", "/me/favorites").withHeader("if-none-match", "\"1\"");
        assertThat(lena.send(inm).status()).isEqualTo(304);
    }

    @Test
    void trackOrderCanBeReplacedAndEntriesFixed() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        String id = lena.createPlaylist("Mix", ONE, TWO, THREE).get("id").asText();
        JsonNode r = lena.ok(lena.write("PUT", "/me/playlists/" + id + "/tracks",
                Map.of("paths", List.of(THREE, ONE), "version", 1)));
        assertThat(paths(r.get("entries"))).containsExactly(THREE, ONE);
        // the web player needs the art ref of every track (null when there is no picture)
        assertThat(r.get("entries").get(0).get("track").has("art")).isTrue();
        // a stored path that disappeared from the library shows as missing, and can be fixed
        r = lena.ok(lena.write("PUT", "/me/playlists/" + id + "/tracks", Map.of("paths", List.of(THREE, ONE)))); // ok
        assertThat(lena.write("PUT", "/me/playlists/" + id + "/tracks", Map.of("paths", List.of("main/Gone.mp3")))
                .status()).isEqualTo(404);
        r = lena.ok(lena.write("POST", "/me/playlists/" + id + "/fix-path", Map.of("index", 1, "path", TWO)));
        assertThat(paths(r.get("entries"))).containsExactly(THREE, TWO);
        assertThat(lena.write("POST", "/me/playlists/" + id + "/fix-path", Map.of("index", 9, "path", TWO)).status())
                .isEqualTo(400);
    }

    // ---- sharing / visibility ----

    @Test
    void privatePlaylistsAreInvisibleSharedOnesReadOnlyAndCopyable() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        Client omar = userClient(admin, "omar", List.of("main"));
        String id = lena.createPlaylist("Lena mix", ONE, TWO).get("id").asText();

        assertThat(omar.get("/me/playlists").jsonBody().get("playlists")).isEmpty();
        assertThat(omar.get("/shared/playlists").jsonBody().get("playlists")).isEmpty();
        assertThat(omar.get("/shared/playlists/" + lena.id + "/" + id).status()).isEqualTo(404);
        assertThat(omar.get("/me/playlists/" + id).status()).isEqualTo(404);
        assertThat(omar.write("PUT", "/me/playlists/" + id, Map.of("name", "hack")).status()).isEqualTo(404);

        lena.ok(lena.write("POST", "/me/playlists/" + id + "/share", null));
        JsonNode shared = omar.ok(omar.get("/shared/playlists")).get("playlists");
        assertThat(shared).hasSize(1);
        assertThat(shared.get(0).get("owner").get("id").asText()).isEqualTo(lena.id);
        assertThat(shared.get(0).get("mine").asBoolean()).isFalse();
        JsonNode detail = omar.ok(omar.get("/shared/playlists/" + lena.id + "/" + id));
        assertThat(paths(detail.get("entries"))).containsExactly(ONE, TWO);
        // read-only for others: no write route under /shared, no edit through /me
        assertThat(omar.write("PUT", "/shared/playlists/" + lena.id + "/" + id, Map.of("name", "x")).status())
                .isEqualTo(404);

        JsonNode copy = omar.ok(omar.write("POST", "/me/playlists/copy",
                Map.of("source", "shared", "owner", lena.id, "id", id)));
        assertThat(copy.get("shared").asBoolean()).isFalse();
        assertThat(copy.get("name").asText()).isEqualTo("Lena mix (copy)");
        assertThat(paths(copy.get("entries"))).containsExactly(ONE, TWO);
        assertThat(copy.get("id").asText()).isNotEqualTo(id);

        lena.ok(lena.write("POST", "/me/playlists/" + id + "/unshare", null));
        assertThat(omar.get("/shared/playlists/" + lena.id + "/" + id).status()).isEqualTo(404);
        assertThat(omar.write("POST", "/me/playlists/copy", Map.of("source", "shared", "owner", lena.id, "id", id))
                .status()).isEqualTo(404);
    }

    @Test
    void systemPlaylistsAreReadOnlyForUsersAndFilteredByRoots() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        svc.playlists().create("Sys");
        svc.playlists().add("sys", List.of(ONE, X));
        JsonNode list = lena.ok(lena.get("/shared/system")).get("playlists");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("count").asInt()).isEqualTo(1);
        assertThat(list.get(0).get("unavailable").asInt()).isEqualTo(1);
        JsonNode d = lena.ok(lena.get("/shared/system/sys"));
        assertThat(paths(d.get("entries"))).containsExactly(ONE);
        assertThat(admin.ok(admin.get("/shared/system/sys")).get("entries")).hasSize(2);
        assertThat(lena.write("PUT", "/shared/system/sys", Map.of("name", "x")).status()).isEqualTo(404);
        assertThat(lena.write("PUT", "/admin/playlists/sys", Map.of("name", "x")).status()).isEqualTo(403);

        JsonNode copy = lena.ok(lena.write("POST", "/shared/system/sys/copy", null));
        assertThat(paths(copy.get("entries"))).containsExactly(ONE);
    }

    // ---- roots filtering ----

    @Test
    void entriesOutsideTheViewersRootsAreHiddenButOwnersKeepThem() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        Client omar = userClient(admin, "omar", List.of("main", "extra"));
        // adding from a root the user cannot access is refused before anything else
        assertThat(lena.write("POST", "/me/playlists", Map.of("name", "n", "tracks", List.of(X))).status())
                .isEqualTo(403);
        String id = omar.createPlaylist("Both", ONE, X, TWO).get("id").asText();
        omar.ok(omar.write("POST", "/me/playlists/" + id + "/share", null));
        assertThat(lena.write("POST", "/me/playlists/" + id + "/tracks", Map.of("paths", List.of(X))).status())
                .isEqualTo(404); // not hers
        String own = lena.createPlaylist("Mine", ONE).get("id").asText();
        assertThat(lena.write("POST", "/me/playlists/" + own + "/tracks", Map.of("paths", List.of(X))).status())
                .isEqualTo(403);
        assertThat(lena.write("POST", "/me/favorites", Map.of("paths", List.of(X))).status()).isEqualTo(403);

        JsonNode seen = lena.ok(lena.get("/shared/playlists/" + omar.id + "/" + id));
        assertThat(paths(seen.get("entries"))).containsExactly(ONE, TWO);
        assertThat(seen.get("unavailable").asInt()).isEqualTo(1);
        assertThat(seen.get("total").asInt()).isEqualTo(2);
        assertThat(lena.ok(lena.get("/shared/playlists")).get("playlists").get(0).get("count").asInt()).isEqualTo(2);

        // copying copies only what the copier can access
        JsonNode copy = lena.ok(lena.write("POST", "/me/playlists/copy",
                Map.of("source", "shared", "owner", omar.id, "id", id)));
        assertThat(paths(copy.get("entries"))).containsExactly(ONE, TWO);
        JsonNode stored = omar.ok(omar.get("/me/playlists/" + id));
        assertThat(stored.get("entries")).hasSize(3); // the owner still has everything

        // the admin removes the root from omar: omar keeps the entry (marked), others still do not see it
        identity.updateUser(omar.id, null, null, List.of("main"), null, null, "admin-id-unused");
        JsonNode after = omar.ok(omar.get("/me/playlists/" + id));
        assertThat(after.get("entries")).hasSize(3);
        assertThat(after.get("entries").get(1).get("status").asText()).isEqualTo("no_access");
        assertThat(after.get("entries").get(1).has("track")).isFalse();
        assertThat(after.get("unavailable").asInt()).isEqualTo(1);
        // keeping the stored entry in an edit is fine, new foreign ones are not
        JsonNode edited = omar.ok(omar.write("PUT", "/me/playlists/" + id + "/tracks",
                Map.of("paths", List.of(TWO, X, ONE))));
        assertThat(paths(edited.get("entries"))).containsExactly(TWO, X, ONE);
        identity.updateUser(omar.id, null, null, List.of("main", "extra"), null, null, "admin-id-unused");
        assertThat(omar.ok(omar.get("/me/playlists/" + id)).get("entries").get(1).get("status").asText())
                .isEqualTo("ok");
    }

    // ---- admin privacy / moderation ----

    @Test
    void adminSeesOnlySharedPlaylistsAndCanModerateThem() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        String priv = lena.createPlaylist("Private", ONE).get("id").asText();
        String pub = lena.createPlaylist("Public", TWO).get("id").asText();
        lena.ok(lena.write("POST", "/me/favorites", Map.of("paths", List.of(ONE))));
        lena.ok(lena.write("POST", "/me/playlists/" + pub + "/share", null));

        JsonNode list = admin.ok(admin.get("/admin/shared-playlists")).get("playlists");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(pub);
        assertThat(list.get(0).get("owner").get("username").asText()).isEqualTo("lena");
        assertThat(admin.get("/me/playlists").jsonBody().get("playlists")).isEmpty();
        assertThat(admin.get("/shared/playlists/" + lena.id + "/" + priv).status()).isEqualTo(404);
        assertThat(admin.write("POST", "/admin/shared-playlists/" + lena.id + "/" + priv + "/unshare", null).status())
                .isEqualTo(404);
        assertThat(admin.write("DELETE", "/admin/shared-playlists/" + lena.id + "/" + priv, null).status())
                .isEqualTo(404);
        assertThat(lena.get("/me/playlists/" + priv).status()).isEqualTo(200);
        assertThat(lena.get("/admin/shared-playlists").status()).isEqualTo(403);

        assertThat(admin.write("POST", "/admin/shared-playlists/" + lena.id + "/" + pub + "/unshare", null).status())
                .isEqualTo(204);
        JsonNode mine = lena.ok(lena.get("/me/playlists/" + pub));
        assertThat(mine.get("shared").asBoolean()).isFalse();
        assertThat(mine.get("version").asLong()).isEqualTo(3);
        lena.ok(lena.write("POST", "/me/playlists/" + pub + "/share", null));
        assertThat(admin.write("DELETE", "/admin/shared-playlists/" + lena.id + "/" + pub, null).status())
                .isEqualTo(204);
        assertThat(lena.get("/me/playlists/" + pub).status()).isEqualTo(404);
        assertThat(admin.get("/admin/shared-playlists").jsonBody().get("playlists")).isEmpty();
    }

    @Test
    void deletingAUserRemovesTheirDataAndTheirSharedPlaylists() throws IOException {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        Client omar = userClient(admin, "omar", List.of("main"));
        String id = lena.createPlaylist("Mix", ONE).get("id").asText();
        lena.ok(lena.write("POST", "/me/playlists/" + id + "/share", null));
        lena.ok(lena.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 40)));
        Path dir = identity.dataDir(lena.id);
        assertThat(dir).exists();
        assertThat(omar.ok(omar.get("/shared/playlists")).get("playlists")).hasSize(1);

        assertThat(admin.write("DELETE", "/admin/users/" + lena.id, null).status()).isEqualTo(204);
        assertThat(dir).doesNotExist();
        assertThat(omar.ok(omar.get("/shared/playlists")).get("playlists")).isEmpty();
        assertThat(omar.get("/shared/playlists/" + lena.id + "/" + id).status()).isEqualTo(404);
        api.personal().maintain(); // must not resurrect the folder with pending history
        api.personal().flushAll();
        assertThat(dir).doesNotExist();
    }

    // ---- queue ----

    @Test
    void queueResumesAndIsFilteredByTheViewersRoots() {
        Client admin = adminClient();
        Client omar = userClient(admin, "omar", List.of("main", "extra"));
        assertThat(omar.ok(omar.get("/me/queue")).get("items")).isEmpty();
        omar.ok(omar.write("PUT", "/me/queue", Map.of("items", List.of(ONE, X, TWO), "index", 2,
                "positionSeconds", 30, "source", Map.of("type", "album", "id", "a1"), "deviceId", "laptop")));
        api = new AdminApi(svc, identity);
        JsonNode q = omar.ok(omar.get("/me/queue"));
        assertThat(paths(q.get("items"))).containsExactly(ONE, X, TWO);
        assertThat(q.get("index").asInt()).isEqualTo(2);
        assertThat(q.get("source").get("type").asText()).isEqualTo("album");
        assertThat(q.get("deviceId").asText()).isEqualTo("laptop");
        assertThat(q.get("version").asLong()).isEqualTo(1);

        identity.updateUser(omar.id, null, null, List.of("main"), null, null, "admin-id-unused");
        q = omar.ok(omar.get("/me/queue"));
        assertThat(paths(q.get("items"))).containsExactly(ONE, TWO);
        assertThat(q.get("index").asInt()).isEqualTo(1);
        assertThat(q.get("unavailable").asInt()).isEqualTo(1);
        assertThat(q.get("positionSeconds").asDouble()).isEqualTo(30);

        // last writer wins; a version precondition can be requested
        assertThat(omar.write("PUT", "/me/queue", Map.of("items", List.of(ONE), "version", 99)).status())
                .isEqualTo(409);
        assertThat(omar.write("PUT", "/me/queue", Map.of("items", List.of(X))).status()).isEqualTo(403);
        assertThat(omar.write("PUT", "/me/queue", Map.of("items", List.of(ONE), "repeat", "bogus")).status())
                .isEqualTo(400);
        JsonNode last = omar.ok(omar.write("PUT", "/me/queue", Map.of("items", List.of(ONE, TWO), "index", 7)));
        assertThat(last.get("index").asInt()).isEqualTo(1); // clamped
    }

    // ---- history ----

    @Test
    void aPlayCountsAfter30SecondsOrHalfTheTrackAndHistoryIsFiltered() {
        Client admin = adminClient();
        Client omar = userClient(admin, "omar", List.of("main", "extra"));
        assertThat(omar.ok(omar.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 2, "durationSeconds", 200)))
                .get("counted").asBoolean()).isFalse();
        assertThat(omar.ok(omar.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 31)))
                .get("counted").asBoolean()).isTrue();
        assertThat(omar.ok(omar.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 5,
                "durationSeconds", 8))).get("playCount").asInt()).isEqualTo(2); // more than 50 %
        omar.ok(omar.write("POST", "/me/history", Map.of("path", X, "playedSeconds", 99)));
        omar.ok(omar.write("POST", "/me/history", Map.of("path", TWO, "playedSeconds", 99)));
        JsonNode most = omar.ok(omar.get("/me/history/most-played"));
        assertThat(paths(most.get("entries"))).startsWith(ONE);
        assertThat(most.get("entries").get(0).get("playCount").asInt()).isEqualTo(2);
        assertThat(paths(omar.ok(omar.get("/me/history/recent")).get("entries"))).containsExactly(TWO, X, ONE);
        assertThat(omar.ok(omar.get("/me/history/recent", "distinct", "false")).get("total").asInt()).isEqualTo(4);

        identity.updateUser(omar.id, null, null, List.of("main"), null, null, "admin-id-unused");
        JsonNode recent = omar.ok(omar.get("/me/history/recent", "limit", "1"));
        assertThat(paths(recent.get("entries"))).containsExactly(TWO);
        assertThat(recent.get("unavailable").asInt()).isEqualTo(1);
        assertThat(recent.get("total").asInt()).isEqualTo(2);
        assertThat(omar.write("POST", "/me/history", Map.of("path", X, "playedSeconds", 99)).status())
                .isEqualTo(403);
        assertThat(omar.write("POST", "/me/history", Map.of("playedSeconds", 99)).status()).isEqualTo(400);
    }

    @Test
    void aForcedPlayCountsAndEntriesCanBeForgotten() {
        Client admin = adminClient();
        Client omar = userClient(admin, "omar", List.of("main", "extra"));
        assertThat(omar.ok(omar.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 3,
                "durationSeconds", 200, "force", true))).get("counted").asBoolean()).isTrue();
        assertThat(omar.ok(omar.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 0,
                "durationSeconds", 200, "force", true))).get("counted").asBoolean()).isFalse();
        JsonNode recent = omar.ok(omar.get("/me/history/recent"));
        assertThat(recent.get("entries").get(0).get("status").asText()).isEqualTo("ok");

        assertThat(omar.send(ApiRequest.of("DELETE", "/me/history/most-played").withQuery("path", ONE)).status())
                .isEqualTo(204);
        assertThat(omar.ok(omar.get("/me/history/most-played")).get("total").asInt()).isZero();
        assertThat(paths(omar.ok(omar.get("/me/history/recent")).get("entries"))).containsExactly(ONE);
        assertThat(omar.send(ApiRequest.of("DELETE", "/me/history/recent").withQuery("path", ONE)).status())
                .isEqualTo(204);
        assertThat(omar.ok(omar.get("/me/history/recent")).get("total").asInt()).isZero();
        assertThat(omar.send(ApiRequest.of("DELETE", "/me/history/recent")).status()).isEqualTo(400);
    }

    @Test
    void historyReportsAreRateLimited() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        for (int i = 0; i < 30; i++) {
            lena.ok(lena.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 40)));
        }
        ApiResponse limited = lena.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 40));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.jsonBody().get("code").asText()).isEqualTo("rate_limited");
        assertThat(limited.headers().get("Retry-After")).isNotNull();
        lib.clock.advance(Duration.ofSeconds(61));
        assertThat(lena.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 40)).status())
                .isEqualTo(200);
        // another user is not affected
        Client omar = userClient(admin, "omar", List.of("main"));
        assertThat(omar.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 40)).status())
                .isEqualTo(200);
    }

    @Test
    void historyIsARingBufferOf1000WithAllPlayCounts() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        for (int i = 0; i < 1010; i++) {
            lib.clock.advance(Duration.ofSeconds(3));
            lena.ok(lena.write("POST", "/me/history", Map.of("path", i % 2 == 0 ? ONE : TWO, "playedSeconds", 40)));
        }
        JsonNode recent = lena.ok(lena.get("/me/history/recent", "distinct", "false", "limit", "1000"));
        assertThat(recent.get("total").asInt()).isEqualTo(1000);
        JsonNode most = lena.ok(lena.get("/me/history/most-played"));
        assertThat(most.get("entries").get(0).get("playCount").asInt()).isEqualTo(505);
        assertThat(most.get("entries").get(1).get("playCount").asInt()).isEqualTo(505);
    }

    @Test
    void idleUsersAreEvictedAfterFlushingHistoryAndReloadedLazily() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        lena.ok(lena.write("POST", "/me/history", Map.of("path", ONE, "playedSeconds", 40)));
        lib.clock.advance(Duration.ofSeconds(1));
        lena.ok(lena.write("POST", "/me/history", Map.of("path", TWO, "playedSeconds", 40))); // debounced, in memory
        Path history = identity.dataDir(lena.id).resolve("history.json");
        assertThat(Files.exists(history)).isTrue();
        assertThat(api.personal().loadedSpaces()).isGreaterThan(0);

        api.personal().maintain();
        lib.clock.advance(Duration.ofMinutes(16));
        api.personal().maintain();
        assertThat(api.personal().loadedSpaces()).isZero();
        assertThat(paths(lena.ok(lena.get("/me/history/recent")).get("entries"))).containsExactly(TWO, ONE);
        assertThat(api.personal().loadedSpaces()).isEqualTo(1);
    }

    @Test
    void validationErrors() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        assertThat(lena.write("POST", "/me/playlists", Map.of("name", " ")).status()).isEqualTo(400);
        assertThat(lena.write("POST", "/me/playlists", Map.of("name", "x", "tracks", List.of("main/../etc")))
                .status()).isEqualTo(400);
        assertThat(lena.write("POST", "/me/playlists", Map.of("name", "x", "tracks", List.of("main/Nope.mp3")))
                .status()).isEqualTo(404);
        assertThat(lena.get("/me/playlists/..%2Fx").status()).isEqualTo(404);
        assertThat(lena.get("/me/playlists/nope").status()).isEqualTo(404);
        assertThat(lena.write("POST", "/me/playlists/copy", Map.of("source", "weird")).status()).isEqualTo(400);
    }
}
