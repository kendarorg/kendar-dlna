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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.curation.FolderService;
import org.kendar.dlna.curation.PlaylistService;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.LoginThrottle;
import org.kendar.dlna.identity.PasswordHasher;
import org.kendar.dlna.identity.SessionStore;
import org.kendar.dlna.ingestion.ScanTrigger;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.testutil.LibraryFixture;

import com.fasterxml.jackson.databind.JsonNode;

/** Identity through the REST seam: roles per endpoint, allowed roots, invites, remember-me, user management. */
class IdentityApiTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    SessionStore sessions;
    IdentityService identity;
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
        sessions = new SessionStore(lib.clock);
        identity = newIdentity();
        identity.bootstrap(Optional.empty());
        adminPassword = announced.get(0).lines().filter(l -> l.contains("password:")).findFirst().orElseThrow()
                .replaceFirst(".*password:\\s*", "").trim();
        api = newApi(identity);
    }

    private IdentityService newIdentity() {
        return new IdentityService(tmp.resolve("users"), List.of("main", "extra"), null,
                new PasswordHasher(1000, new SecureRandom()), sessions, lib.clock, new SecureRandom(),
                announced::add);
    }

    private AdminApi newApi(IdentityService id) {
        TagService tags = new TagService(lib.catalog, lib.files, new org.kendar.dlna.curation.PerFormatTagWriter(),
                lib.reader);
        AdminServices svc = new AdminServices(lib.catalog, lib.rescans, tags,
                new FolderService(lib.catalog, lib.files, tags), new PlaylistService(lib.catalog, lib.playlists),
                new Shuffle(lib.catalog::current), new ActiveStreams(), null, () -> 0,
                new BuildInfo("1.2.3", "abc123", "2026-01-01T00:00:00Z"), lib.clock);
        return new AdminApi(svc, id);
    }

    /** A browser with its cookie jar. */
    final class Client {
        final Map<String, String> jar = new LinkedHashMap<>();
        String csrf;
        String ip = "10.0.0.1";

        ApiResponse send(ApiRequest r) {
            ApiRequest q = r.from(ip);
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
                String value = nv.substring(eq + 1);
                if (sc.contains("Max-Age=0")) {
                    jar.remove(nv.substring(0, eq));
                } else {
                    jar.put(nv.substring(0, eq), value);
                }
            }
            if (out.status() == 200 && out.contentType() != null && out.text().contains("csrfToken")) {
                csrf = out.jsonBody().path("csrfToken").asText(csrf);
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

        ApiResponse write(String method, String path, Object body, String... query) {
            ApiRequest r = ApiRequest.of(method, path);
            for (int i = 0; i < query.length; i += 2) {
                r = r.withQuery(query[i], query[i + 1]);
            }
            return send(body == null ? r : r.withJson(body));
        }

        ApiResponse login(String user, String password, boolean remember) {
            return write("POST", "/auth/login", Map.of("username", user, "password", password, "remember", remember));
        }
    }

    private Client adminClient() {
        Client c = new Client();
        assertThat(c.login("admin", adminPassword, false).status()).isEqualTo(200);
        assertThat(c.write("POST", "/me/password", Map.of("currentPassword", adminPassword, "newPassword",
                "admin-chosen-password")).status()).isEqualTo(200);
        return c;
    }

    /** Creates a user through the admin API with a known password, returns a logged-in client. */
    private Client userClient(Client admin, String name, List<String> roots, boolean download) {
        ApiResponse r = admin.write("POST", "/admin/users", Map.of("username", name, "allowedRoots", roots,
                "downloadAllowed", download));
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        String token = r.jsonBody().get("invite").get("token").asText();
        Client c = new Client();
        ApiResponse acc = c.write("POST", "/auth/invite/accept", Map.of("token", token, "password", name + "-password-1"));
        assertThat(acc.status()).as(acc.text()).isEqualTo(200);
        return c;
    }

    private String idOf(Client admin, String name) {
        for (JsonNode u : admin.get("/admin/users").jsonBody().get("users")) {
            if (u.get("username").asText().equals(name)) {
                return u.get("id").asText();
            }
        }
        throw new AssertionError("no such user " + name);
    }

    // ---- login, cookies, remember-me ----

    @Test
    void cookiesAreHardenedAndScopedToTheApiMount() {
        Client c = new Client();
        ApiResponse r = c.login("admin", adminPassword, true);
        assertThat(r.status()).isEqualTo(200);
        List<String> cookies = r.setCookies();
        assertThat(cookies).hasSize(2);
        String session = cookies.stream().filter(x -> x.startsWith("kd_session=")).findFirst().orElseThrow();
        String remember = cookies.stream().filter(x -> x.startsWith("kd_remember=")).findFirst().orElseThrow();
        assertThat(session).contains("Path=/api/v1;").contains("HttpOnly").contains("SameSite=Strict")
                .doesNotContain("Secure");
        assertThat(remember).contains("Path=/api/v1/auth;").contains("HttpOnly").contains("SameSite=Strict")
                .contains("Max-Age=" + Duration.ofDays(30).toSeconds());
        JsonNode st = r.jsonBody();
        assertThat(st.get("role").asText()).isEqualTo("admin");
        assertThat(st.get("username").asText()).isEqualTo("admin");
        assertThat(st.get("mustChange").asBoolean()).isTrue();

        ApiResponse secure = api.handle(new ApiRequest("POST", "/auth/login", null, Map.of("x-requested-with", "k"),
                ApiRequest.of("POST", "/x").withJson(Map.of("username", "admin", "password", adminPassword,
                        "remember", true)).body(), "1.2.3.4", true));
        assertThat(secure.setCookies()).allMatch(x -> x.contains("Secure"));

        Client noRemember = new Client();
        assertThat(noRemember.login("admin", adminPassword, false).setCookies()).hasSize(1);
    }

    @Test
    void rememberMeSignsInAgainAndRotatesTheToken() {
        adminClient();
        Client first = new Client();
        first.login("admin", "admin-chosen-password", true);
        String oldToken = first.jar.get("kd_remember");

        Client later = new Client();   // a new browser session holding only the remember-me cookie
        later.jar.put("kd_remember", oldToken);
        ApiResponse r = later.write("POST", "/auth/refresh", Map.of());
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.jsonBody().get("authenticated").asBoolean()).isTrue();
        assertThat(later.jar.get("kd_remember")).isNotEqualTo(oldToken);
        assertThat(later.get("/admin/version").status()).isEqualTo(200);

        Client replay = new Client();
        replay.jar.put("kd_remember", oldToken);
        ApiResponse again = replay.write("POST", "/auth/refresh", Map.of());
        assertThat(again.status()).isEqualTo(401);
        assertThat(again.jsonBody().get("code").asText()).isEqualTo("invalid_remember");
        assertThat(new Client().write("POST", "/auth/refresh", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void rememberMeSurvivesARestartAndExpires() {
        adminClient();
        Client c = new Client();
        c.login("admin", "admin-chosen-password", true);
        String token = c.jar.get("kd_remember");

        // restart: new sessions, new services over the same files
        sessions = new SessionStore(lib.clock);
        identity = newIdentity();
        api = newApi(identity);
        Client after = new Client();
        after.jar.put("kd_remember", token);
        assertThat(after.get("/admin/version").status()).as("the old login session is gone").isEqualTo(401);
        assertThat(after.write("POST", "/auth/refresh", Map.of()).status()).isEqualTo(200);
        assertThat(after.get("/admin/version").status()).isEqualTo(200);

        String rotated = after.jar.get("kd_remember");
        lib.clock.advance(Duration.ofDays(31));
        Client expired = new Client();
        expired.jar.put("kd_remember", rotated);
        assertThat(expired.write("POST", "/auth/refresh", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void logoutClearsBothCookiesAndRevokesTheRememberToken() {
        adminClient();
        Client c = new Client();
        c.login("admin", "admin-chosen-password", true);
        String token = c.jar.get("kd_remember");

        ApiResponse out = c.write("POST", "/auth/logout", null);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.setCookies()).hasSize(2).allMatch(x -> x.contains("Max-Age=0"));
        assertThat(c.jar).isEmpty();
        Client replay = new Client();
        replay.jar.put("kd_remember", token);
        assertThat(replay.write("POST", "/auth/refresh", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void refreshAndLoginNeedTheCustomHeader() {
        ApiRequest noHeader = ApiRequest.of("POST", "/auth/refresh").withHeader("cookie", "kd_remember=x");
        assertThat(api.handle(noHeader).status()).isEqualTo(403);
        ApiRequest login = ApiRequest.of("POST", "/auth/login").withJson(Map.of("username", "admin", "password", "x"));
        assertThat(api.handle(login).status()).isEqualTo(403);
    }

    // ---- throttling through the API ----

    @Test
    void aUserNameIsThrottledAcrossAddresses() {
        for (int i = 0; i < IdentityService.USER_MAX_FAILURES; i++) {
            Client c = new Client();
            c.ip = "9.9." + i + ".9";
            assertThat(c.login("admin", "wrong" + i, false).status()).isEqualTo(401);
        }
        Client other = new Client();
        other.ip = "8.8.8.8";
        ApiResponse blocked = other.login("admin", adminPassword, false);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.headers()).containsKey("Retry-After");
        assertThat(blocked.jsonBody().get("code").asText()).isEqualTo("too_many_attempts");

        lib.clock.advance(Duration.ofMinutes(16));
        assertThat(other.login("admin", adminPassword, false).status()).isEqualTo(200);
    }

    @Test
    void anAddressIsThrottledAcrossUserNames() {
        for (int i = 0; i < LoginThrottle.DEFAULT_MAX_FAILURES; i++) {
            assertThat(new Client().login("user" + i, "wrong", false).status()).isEqualTo(401);
        }
        assertThat(new Client().login("admin", adminPassword, false).status()).isEqualTo(429);
        Client elsewhere = new Client();
        elsewhere.ip = "7.7.7.7";
        assertThat(elsewhere.login("admin", adminPassword, false).status()).isEqualTo(200);
    }

    @Test
    void errorsAreGenericForUnknownDisabledAndUnfinishedAccounts() {
        Client admin = adminClient();
        admin.write("POST", "/admin/users", Map.of("username", "pending"));
        ApiResponse unknown = new Client().login("nobody", "whatever-password", false);
        ApiResponse pending = new Client().login("pending", "whatever-password", false);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(pending.status()).isEqualTo(401);
        assertThat(pending.text()).isEqualTo(unknown.text());
    }

    // ---- invites through the API ----

    @Test
    void inviteFlowCreatesAUserWhoChoosesTheOwnPassword() {
        Client admin = adminClient();
        ApiResponse created = admin.write("POST", "/admin/users", Map.of("username", "lena", "displayName", "Lena"));
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        JsonNode body = created.jsonBody();
        assertThat(body.get("user").get("allowedRoots")).extracting(JsonNode::asText).containsExactly("main");
        assertThat(body.get("user").get("downloadAllowed").asBoolean()).isFalse();
        assertThat(body.get("user").get("hasPassword").asBoolean()).isFalse();
        assertThat(body.get("user").get("pendingInvite").asBoolean()).isTrue();
        assertThat(body.get("user").has("passwordHash")).isFalse();
        String token = body.get("invite").get("token").asText();
        assertThat(body.get("invite").get("expiresAt").asText())
                .isEqualTo(lib.clock.instant().plus(Duration.ofDays(7)).toString());

        Client anon = new Client();
        JsonNode peek = anon.get("/auth/invite/" + token).jsonBody();
        assertThat(peek.get("username").asText()).isEqualTo("lena");
        assertThat(peek.get("displayName").asText()).isEqualTo("Lena");

        ApiResponse weak = anon.write("POST", "/auth/invite/accept", Map.of("token", token, "password", "short"));
        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.jsonBody().get("code").asText()).isEqualTo("weak_password");

        ApiResponse ok = anon.write("POST", "/auth/invite/accept", Map.of("token", token, "password",
                "lena-chosen-password", "remember", true));
        assertThat(ok.status()).as(ok.text()).isEqualTo(200);
        assertThat(ok.jsonBody().get("role").asText()).isEqualTo("user");
        assertThat(ok.jsonBody().get("mustChange").asBoolean()).isFalse();
        assertThat(ok.setCookies()).hasSize(2);
        assertThat(anon.get("/me").jsonBody().get("username").asText()).isEqualTo("lena");

        // one-time
        ApiResponse reuse = new Client().write("POST", "/auth/invite/accept", Map.of("token", token, "password",
                "someone-elses-password"));
        assertThat(reuse.status()).isEqualTo(410);
        assertThat(reuse.jsonBody().get("code").asText()).isEqualTo("invalid_invite");
        assertThat(new Client().get("/auth/invite/" + token).status()).isEqualTo(410);
        assertThat(new Client().login("lena", "lena-chosen-password", false).status()).isEqualTo(200);
    }

    @Test
    void anExpiredInviteIsRefused() {
        Client admin = adminClient();
        String token = admin.write("POST", "/admin/users", Map.of("username", "lena")).jsonBody().get("invite")
                .get("token").asText();
        lib.clock.advance(Duration.ofDays(7).plusSeconds(1));
        assertThat(new Client().get("/auth/invite/" + token).status()).isEqualTo(410);
        ApiResponse r = new Client().write("POST", "/auth/invite/accept", Map.of("token", token, "password",
                "lena-chosen-password"));
        assertThat(r.status()).isEqualTo(410);
        // a fresh invite works
        admin = new Client();   // the admin session itself expired meanwhile
        admin.login("admin", "admin-chosen-password", false);
        String fresh = admin.write("POST", "/admin/users/" + idOf(admin, "lena") + "/invite", Map.of()).jsonBody()
                .get("invite").get("token").asText();
        assertThat(new Client().write("POST", "/auth/invite/accept", Map.of("token", fresh, "password",
                "lena-chosen-password")).status()).isEqualTo(200);
    }

    @Test
    void guessingInviteTokensIsThrottledPerAddress() {
        for (int i = 0; i < LoginThrottle.DEFAULT_MAX_FAILURES; i++) {
            assertThat(new Client().write("POST", "/auth/invite/accept", Map.of("token", "guess" + i, "password",
                    "some-long-password")).status()).isEqualTo(410);
        }
        assertThat(new Client().write("POST", "/auth/invite/accept", Map.of("token", "guess", "password",
                "some-long-password")).status()).isEqualTo(429);
    }

    @Test
    void temporaryPasswordForcesAChange() {
        Client admin = adminClient();
        ApiResponse created = admin.write("POST", "/admin/users", Map.of("username", "lena", "credential",
                "temporaryPassword"));
        String temp = created.jsonBody().get("temporaryPassword").asText();
        assertThat(temp).hasSize(20);
        assertThat(created.jsonBody().has("invite")).isFalse();

        Client c = new Client();
        ApiResponse login = c.login("lena", temp, false);
        assertThat(login.jsonBody().get("mustChange").asBoolean()).isTrue();
        // only /me and /auth work until the password is changed
        assertThat(c.get("/library/roots").status()).isEqualTo(403);
        assertThat(c.get("/library/roots").jsonBody().get("code").asText()).isEqualTo("password_change_required");
        assertThat(c.get("/me").status()).isEqualTo(200);
        assertThat(c.write("POST", "/me/password", Map.of("currentPassword", temp, "newPassword",
                "lena-own-password")).status()).isEqualTo(200);
        assertThat(c.get("/library/roots").status()).isEqualTo(200);
    }

    // ---- role enforcement ----

    private record Endpoint(String method, String path, Map<String, String> query, Object body) {
        static Endpoint of(String method, String path, Object body, String... q) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < q.length; i += 2) {
                m.put(q[i], q[i + 1]);
            }
            return new Endpoint(method, path, m, body);
        }
    }

    private static List<Endpoint> adminEndpoints() {
        return List.of(
                Endpoint.of("GET", "/admin/folders/tree", null),
                Endpoint.of("GET", "/admin/folders/contents", null, "path", "main/Album"),
                Endpoint.of("GET", "/admin/folders/settings", null, "path", "main/Album"),
                Endpoint.of("PUT", "/admin/folders/settings", Map.of("sort", "title"), "path", "main/Album"),
                Endpoint.of("POST", "/admin/folders/sync-from-tags", null, "path", "main/Album"),
                Endpoint.of("POST", "/admin/folders/sync-to-tags", Map.of("files", List.of("01 one.wav")), "path",
                        "main/Album"),
                Endpoint.of("GET", "/admin/folders/art", null, "path", "main/Album", "kind", "front"),
                Endpoint.of("PUT", "/admin/folders/art", null, "path", "main/Album", "kind", "front"),
                Endpoint.of("GET", "/admin/folders/list", null, "path", "main/Album"),
                Endpoint.of("PUT", "/admin/folders/list", Map.of("entries", List.of()), "path", "main/Album"),
                Endpoint.of("POST", "/admin/folders/list/generate", null, "path", "main/Album"),
                Endpoint.of("GET", "/admin/folders/list/file", null, "path", "main/Album/01 one.wav"),
                Endpoint.of("PUT", "/admin/folders/list/file", Map.of("entries", List.of()), "path",
                        "main/Album/01 one.wav"),
                Endpoint.of("POST", "/admin/folders/list/file/generate", null, "path", "main/Album/01 one.wav"),
                Endpoint.of("GET", "/admin/tracks", null, "path", "main/Album/01 one.wav"),
                Endpoint.of("PUT", "/admin/tracks", Map.of("title", "x"), "path", "main/Album/01 one.wav"),
                Endpoint.of("POST", "/admin/tracks/batch", Map.of("paths", List.of("main/Album/01 one.wav"), "edit",
                        Map.of("genre", "x"))),
                Endpoint.of("GET", "/admin/search", null, "q", "one"),
                Endpoint.of("GET", "/admin/playlists", null),
                Endpoint.of("POST", "/admin/playlists", Map.of("name", "x")),
                Endpoint.of("GET", "/admin/playlists/x", null),
                Endpoint.of("PUT", "/admin/playlists/x", Map.of("name", "y")),
                Endpoint.of("DELETE", "/admin/playlists/x", null),
                Endpoint.of("POST", "/admin/playlists/x/songs", Map.of("paths", List.of())),
                Endpoint.of("POST", "/admin/playlists/x/fix-path", Map.of("index", 0, "path", "main/x")),
                Endpoint.of("POST", "/admin/rescan", null),
                Endpoint.of("GET", "/admin/scan", null),
                Endpoint.of("GET", "/admin/unsupported", null),
                Endpoint.of("GET", "/admin/invalid-settings", null),
                Endpoint.of("GET", "/admin/streams", null),
                Endpoint.of("GET", "/admin/snapshots", null),
                Endpoint.of("GET", "/admin/version", null),
                Endpoint.of("GET", "/admin/roots", null),
                Endpoint.of("GET", "/admin/users", null),
                Endpoint.of("POST", "/admin/users", Map.of("username", "evil")),
                Endpoint.of("GET", "/admin/users/someone", null),
                Endpoint.of("PUT", "/admin/users/someone", Map.of("role", "admin")),
                Endpoint.of("DELETE", "/admin/users/someone", null),
                Endpoint.of("POST", "/admin/users/someone/invite", Map.of()),
                Endpoint.of("POST", "/admin/users/someone/temporary-password", Map.of()),
                Endpoint.of("GET", "/admin/does-not-exist", null));
    }

    private ApiResponse call(Client c, Endpoint e) {
        ApiRequest r = ApiRequest.of(e.method(), e.path());
        for (var q : e.query().entrySet()) {
            r = r.withQuery(q.getKey(), q.getValue());
        }
        return c.send(e.body() == null ? r : r.withJson(e.body()));
    }

    @Test
    void everyAdminEndpointRefusesAnonymousCallers() {
        for (Endpoint e : adminEndpoints()) {
            ApiResponse r = call(new Client(), e);
            assertThat(r.status()).as(e.method() + " " + e.path()).isEqualTo(401);
            assertThat(r.jsonBody().get("code").asText()).isEqualTo("unauthenticated");
        }
    }

    @Test
    void everyAdminEndpointRefusesRegularUsersWith403() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), true);
        for (Endpoint e : adminEndpoints()) {
            ApiResponse r = call(lena, e);
            assertThat(r.status()).as(e.method() + " " + e.path() + " -> " + r.text()).isEqualTo(403);
            assertThat(r.jsonBody().get("code").asText()).as(e.path()).isEqualTo("forbidden");
        }
        // nothing happened
        assertThat(lib.catalog.current().playlists()).isEmpty();
        assertThat(admin.get("/admin/users").jsonBody().get("users")).hasSize(2);
    }

    @Test
    void theAdminReachesEveryAdminEndpoint() {
        Client admin = adminClient();
        for (Endpoint e : adminEndpoints()) {
            if (e.method().equals("DELETE") && e.path().startsWith("/admin/users")) {
                continue;   // would delete real data; covered below
            }
            ApiResponse r = call(admin, e);
            assertThat(r.status()).as(e.method() + " " + e.path() + " -> " + r.text()).isNotIn(401, 403);
        }
    }

    @Test
    void writesStillNeedTheCsrfTokenForEveryRole() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), false);
        admin.csrf = "wrong";
        lena.csrf = "wrong";
        assertThat(admin.write("POST", "/admin/users", Map.of("username", "bob")).status()).isEqualTo(403);
        assertThat(admin.write("POST", "/admin/users", Map.of("username", "bob")).jsonBody().get("code").asText())
                .isEqualTo("csrf");
        assertThat(lena.write("POST", "/me/password", Map.of("currentPassword", "lena-password-1", "newPassword",
                "lena-another-password")).status()).isEqualTo(403);
    }

    @Test
    void theOldUnversionedPathsAreGone() {
        Client admin = adminClient();
        for (String p : new String[] { "/folders/tree", "/session", "/playlists", "/admin/../session" }) {
            assertThat(admin.get(p).status()).as(p).isEqualTo(404);
        }
        assertThat(admin.get("/admin/folders/tree").status()).isEqualTo(200);
    }

    @Test
    void userEndpointsWorkForUsersAndAdmins() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), false);
        assertThat(lena.get("/me").jsonBody().get("role").asText()).isEqualTo("user");
        assertThat(lena.get("/library/roots").status()).isEqualTo(200);
        assertThat(admin.get("/library/roots").status()).isEqualTo(200);
        assertThat(lena.get("/auth/session").jsonBody().get("authenticated").asBoolean()).isTrue();
    }

    // ---- allowed roots ----

    @Test
    void usersSeeOnlyTheirAllowedRootsAndGet403ElsewhereAdminsSeeAll() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), false);
        Client kim = userClient(admin, "kim", List.of("extra"), false);

        assertThat(rootsOf(lena)).containsExactly("main");
        assertThat(rootsOf(kim)).containsExactly("extra");
        assertThat(rootsOf(admin)).containsExactlyInAnyOrder("main", "extra");

        assertThat(lena.get("/library/folders/contents", "path", "main/Album").status()).isEqualTo(200);
        assertThat(lena.get("/library/folders/contents", "path", "main").jsonBody().get("children")).isNotEmpty();
        assertThat(lena.get("/library/tracks", "path", "main/Album/01 one.wav").status()).isEqualTo(200);

        for (String path : new String[] { "extra", "extra/Disc", "extra/Disc/x.wav", "nonexistent", "nonexistent/x" }) {
            ApiResponse f = lena.get("/library/folders/contents", "path", path);
            assertThat(f.status()).as(path).isEqualTo(403);
            assertThat(f.jsonBody().get("code").asText()).isEqualTo("forbidden_root");
            assertThat(lena.get("/library/tracks", "path", path).status()).as(path).isEqualTo(403);
        }
        assertThat(kim.get("/library/folders/contents", "path", "extra/Disc").status()).isEqualTo(200);
        assertThat(kim.get("/library/folders/contents", "path", "main/Album").status()).isEqualTo(403);
        assertThat(kim.get("/library/tracks", "path", "main/Album/01 one.wav").status()).isEqualTo(403);

        assertThat(admin.get("/library/folders/contents", "path", "extra/Disc").status()).isEqualTo(200);
        assertThat(admin.get("/library/folders/contents", "path", "main/Album").status()).isEqualTo(200);
        assertThat(admin.get("/library/folders/contents", "path", "nonexistent").status()).isEqualTo(404);

        assertThat(lena.get("/library/folders/contents").status()).isEqualTo(400);
        assertThat(lena.get("/library/folders/contents", "path", "main/../extra").status()).isEqualTo(400);
    }

    private List<String> rootsOf(Client c) {
        List<String> out = new ArrayList<>();
        c.get("/library/roots").jsonBody().get("roots").forEach(n -> out.add(n.get("path").asText()));
        return out;
    }

    @Test
    void changingAllowedRootsTakesEffectImmediately() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), false);
        String id = idOf(admin, "lena");
        assertThat(lena.get("/library/folders/contents", "path", "extra/Disc").status()).isEqualTo(403);

        ApiResponse r = admin.write("PUT", "/admin/users/" + id, Map.of("allowedRoots", List.of("main", "extra")));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(lena.get("/library/folders/contents", "path", "extra/Disc").status()).isEqualTo(200);

        admin.write("PUT", "/admin/users/" + id, Map.of("allowedRoots", List.of("extra")));
        assertThat(lena.get("/library/folders/contents", "path", "main/Album").status()).isEqualTo(403);
        assertThat(rootsOf(lena)).containsExactly("extra");
    }

    @Test
    void removingARootFromTheConfigurationStripsItFromUsers() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main", "extra"), false);
        assertThat(rootsOf(lena)).containsExactlyInAnyOrder("main", "extra");

        identity.syncRoots(java.util.Set.of("main"));

        assertThat(rootsOf(lena)).containsExactly("main");
        assertThat(lena.get("/library/folders/contents", "path", "extra/Disc").status()).isEqualTo(403);
        JsonNode u = admin.get("/admin/users/" + idOf(admin, "lena")).jsonBody().get("user");
        assertThat(u.get("allowedRoots")).extracting(JsonNode::asText).containsExactly("main");
    }

    // ---- user management ----

    @Test
    void userManagementEndpoints() {
        Client admin = adminClient();
        JsonNode list = admin.get("/admin/users").jsonBody();
        assertThat(list.get("roots")).extracting(JsonNode::asText).containsExactly("main", "extra");
        assertThat(list.get("mainRoot").asText()).isEqualTo("main");
        assertThat(list.get("users")).hasSize(1);
        assertThat(list.get("users").get(0).get("role").asText()).isEqualTo("admin");

        // validation and conflicts
        assertThat(admin.write("POST", "/admin/users", Map.of("username", "ADMIN")).status()).isEqualTo(409);
        assertThat(admin.write("POST", "/admin/users", Map.of("username", "a b")).status()).isEqualTo(400);
        assertThat(admin.write("POST", "/admin/users", Map.of("username", "bob", "allowedRoots", List.of("zzz")))
                .status()).isEqualTo(400);
        assertThat(admin.write("POST", "/admin/users", Map.of("username", "bob", "role", "root")).status())
                .isEqualTo(400);
        assertThat(admin.write("POST", "/admin/users", Map.of("username", "bob", "credential", "x")).status())
                .isEqualTo(400);
        assertThat(admin.get("/admin/users").jsonBody().get("users")).as("nothing half-created").hasSize(1);

        Client lena = userClient(admin, "lena", List.of("main"), false);
        String id = idOf(admin, "lena");
        ApiResponse upd = admin.write("PUT", "/admin/users/" + id, Map.of("displayName", "Lena L.", "downloadAllowed",
                true));
        assertThat(upd.jsonBody().get("user").get("displayName").asText()).isEqualTo("Lena L.");
        assertThat(upd.jsonBody().get("user").get("downloadAllowed").asBoolean()).isTrue();
        assertThat(lena.get("/me").jsonBody().get("downloadAllowed").asBoolean()).isTrue();

        // disabling ends the session at once and blocks login
        assertThat(admin.write("PUT", "/admin/users/" + id, Map.of("enabled", false)).status()).isEqualTo(200);
        assertThat(lena.get("/library/roots").status()).isEqualTo(401);
        assertThat(new Client().login("lena", "lena-password-1", false).status()).isEqualTo(401);
        admin.write("PUT", "/admin/users/" + id, Map.of("enabled", true));
        assertThat(new Client().login("lena", "lena-password-1", false).status()).isEqualTo(200);

        // a new invite retires the password and the sessions
        ApiResponse inv = admin.write("POST", "/admin/users/" + id + "/invite", Map.of());
        assertThat(inv.jsonBody().get("invite").get("path").asText()).startsWith("/app/#/invite/");
        assertThat(new Client().login("lena", "lena-password-1", false).status()).isEqualTo(401);

        // temporary password
        ApiResponse tmpPw = admin.write("POST", "/admin/users/" + id + "/temporary-password", Map.of());
        assertThat(new Client().login("lena", tmpPw.jsonBody().get("temporaryPassword").asText(), false).status())
                .isEqualTo(200);

        // delete
        assertThat(admin.write("DELETE", "/admin/users/" + id, null).status()).isEqualTo(204);
        assertThat(admin.get("/admin/users/" + id).status()).isEqualTo(404);
        assertThat(new Client().login("lena", "lena-password-1", false).status()).isEqualTo(401);
    }

    @Test
    void anAdminCannotLockTheSystemOut() {
        Client admin = adminClient();
        String me = idOf(admin, "admin");
        assertThat(admin.write("DELETE", "/admin/users/" + me, null).status()).isEqualTo(409);
        assertThat(admin.write("PUT", "/admin/users/" + me, Map.of("role", "user")).status()).isEqualTo(409);
        assertThat(admin.write("PUT", "/admin/users/" + me, Map.of("enabled", false)).status()).isEqualTo(409);
        assertThat(admin.get("/admin/version").status()).isEqualTo(200);
    }

    @Test
    void aSecondAdminHasTheSamePowers() {
        Client admin = adminClient();
        ApiResponse r = admin.write("POST", "/admin/users", Map.of("username", "boss", "role", "admin"));
        String token = r.jsonBody().get("invite").get("token").asText();
        Client boss = new Client();
        assertThat(boss.write("POST", "/auth/invite/accept", Map.of("token", token, "password", "boss-password-1"))
                .status()).isEqualTo(200);
        assertThat(boss.get("/admin/users").status()).isEqualTo(200);
        assertThat(boss.get("/admin/folders/tree").status()).isEqualTo(200);
        assertThat(rootsOf(boss)).containsExactlyInAnyOrder("main", "extra");
    }

    @Test
    void passwordChangeEndsTheOtherSessionsOfThatUser() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), false);
        Client second = new Client();
        assertThat(second.login("lena", "lena-password-1", true).status()).isEqualTo(200);
        ApiResponse r = lena.write("POST", "/me/password", Map.of("currentPassword", "lena-password-1",
                "newPassword", "lena-another-password"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(lena.get("/library/roots").status()).isEqualTo(200);
        assertThat(second.get("/library/roots").status()).isEqualTo(401);
        assertThat(second.write("POST", "/auth/refresh", Map.of()).status()).as("remember tokens too").isEqualTo(401);
        assertThat(lena.write("POST", "/me/password", Map.of("currentPassword", "wrong", "newPassword",
                "yet-another-password")).jsonBody().get("code").asText()).isEqualTo("wrong_password");
    }

    @Test
    void theMainRootIsConfigurableAndDefaultsToTheFirst() {
        assertThat(identity.mainRoot()).isEqualTo("main");
    }
}
