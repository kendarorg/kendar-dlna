package org.kendar.dlna.adminweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.curation.FolderService;
import org.kendar.dlna.curation.PlaylistService;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.PasswordHasher;
import org.kendar.dlna.identity.SessionStore;
import org.kendar.dlna.ingestion.ScanTrigger;
import org.kendar.dlna.libraryquery.LibraryQuery;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.testutil.LibraryFixture;
import org.kendar.dlna.views.Views;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code /api/v1/library/...} through the REST seam: roles, 403 forbidden_root per resource, paging, artwork, OpenAPI. */
class LibraryQueryApiTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
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
        BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 1000, 1000);
        g.dispose();
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jpg);
        Files.write(lib.music.resolve("Album/front.jpg"), jpg.toByteArray());
        lib.rescans.rescanNow(ScanTrigger.MANUAL, null);
        SessionStore sessions = new SessionStore(lib.clock);
        identity = new IdentityService(tmp.resolve("users"), List.of("main", "extra"), null,
                new PasswordHasher(1000, new SecureRandom()), sessions, lib.clock, new SecureRandom(), announced::add);
        identity.bootstrap(Optional.empty());
        adminPassword = announced.get(0).lines().filter(l -> l.contains("password:")).findFirst().orElseThrow()
                .replaceFirst(".*password:\\s*", "").trim();
        TagService tags = new TagService(lib.catalog, lib.files, new org.kendar.dlna.curation.PerFormatTagWriter(),
                lib.reader);
        AdminServices svc = new AdminServices(lib.catalog, lib.rescans, tags,
                new FolderService(lib.catalog, lib.files, tags), new PlaylistService(lib.catalog, lib.playlists),
                new Shuffle(lib.catalog::current), new ActiveStreams(), null, () -> 0,
                new BuildInfo("1.2.3", "abc123", "2026-01-01T00:00:00Z"), lib.clock);
        ArtworkService artwork = new ArtworkService(lib.catalog.holder(), lib.files::resolve, ref -> Optional.empty(),
                tmp.resolve("thumbs"));
        api = new AdminApi(svc, identity, new LibraryQuery(lib.catalog::current, new Views(lib.catalog::current),
                artwork));
    }

    final class Client {
        final Map<String, String> jar = new LinkedHashMap<>();
        String csrf;

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
            }
            return out;
        }

        ApiResponse get(String path, String... query) {
            return get(path, Map.of(), query);
        }

        ApiResponse get(String path, Map<String, String> headers, String... query) {
            ApiRequest r = ApiRequest.of("GET", path);
            for (int i = 0; i < query.length; i += 2) {
                r = r.withQuery(query[i], query[i + 1]);
            }
            for (var h : headers.entrySet()) {
                r = r.withHeader(h.getKey(), h.getValue());
            }
            return send(r);
        }

        ApiResponse write(String method, String path, Object body) {
            ApiRequest r = ApiRequest.of(method, path);
            return send(body == null ? r : r.withJson(body));
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

    @Test
    void everyResourceIsAvailableToUsersAndAdminsAndNeedsALogin() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        for (Client c : List.of(admin, lena)) {
            for (String p : new String[] { "/library/roots", "/library/albums", "/library/artists", "/library/genres",
                    "/library/songs", "/library/recent", "/library/random", "/library/search/suggest" }) {
                ApiResponse r = c.get(p);
                assertThat(r.status()).as(p + " " + r.text()).isEqualTo(200);
                assertThat(r.jsonBody().get("snapshot").asLong()).isEqualTo(lib.catalog.current().updateId());
            }
            assertThat(c.get("/library/search", "q", "one").status()).isEqualTo(200);
            assertThat(c.get("/library/folders", "path", "main/Album").status()).isEqualTo(200);
            assertThat(c.get("/library/tracks", "path", "main/Album/01 one.wav").status()).isEqualTo(200);
        }
        Client anonymous = new Client();
        for (String p : new String[] { "/library/roots", "/library/albums", "/library/songs", "/library/random",
                "/library/search", "/library/artwork" }) {
            assertThat(anonymous.get(p).status()).as(p).isEqualTo(401);
        }
    }

    @Test
    void everyPathTakingResourceAnswers403ForbiddenRootOutsideTheAllowedRoots() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        String[][] calls = { { "/library/folders", "path", "extra/Disc" }, { "/library/folders/contents", "path", "extra" },
                { "/library/tracks", "path", "extra/Disc/x.wav" }, { "/library/random", "path", "extra/Disc" },
                { "/library/search", "q", "x", "scope", "extra" },
                { "/library/artwork", "kind", "track", "path", "extra/Disc/x.wav" },
                { "/library/artwork", "kind", "folder", "path", "extra/Disc" },
                { "/library/folders", "path", "nowhere" } };
        for (String[] c : calls) {
            String[] query = java.util.Arrays.copyOfRange(c, 1, c.length);
            ApiResponse r = lena.get(c[0], query);
            assertThat(r.status()).as(String.join(" ", c)).isEqualTo(403);
            assertThat(r.jsonBody().get("code").asText()).isEqualTo("forbidden_root");
        }
        // the lists simply do not contain the other root
        for (String p : new String[] { "/library/roots", "/library/songs", "/library/recent", "/library/random",
                "/library/albums", "/library/artists" }) {
            assertThat(lena.get(p, "limit", "200").text()).as(p).doesNotContain("extra");
        }
        assertThat(lena.get("/library/search", "q", "x").text()).doesNotContain("extra");
        assertThat(admin.get("/library/songs").text()).contains("extra/Disc/x.wav");
    }

    @Test
    void errorsAndRoutingFollowTheApiConventions() {
        Client admin = adminClient();
        assertThat(admin.get("/library/folders").status()).isEqualTo(400);
        assertThat(admin.get("/library/folders", "path", "main/../extra").status()).isEqualTo(400);
        assertThat(admin.get("/library/folders", "path", "nowhere").status()).isEqualTo(404);
        assertThat(admin.get("/library/songs", "limit", "x").status()).isEqualTo(400);
        assertThat(admin.get("/library/songs", "offset", "-1").status()).isEqualTo(400);
        assertThat(admin.get("/library/search").status()).isEqualTo(400);
        assertThat(admin.get("/library/albums/unknown").status()).isEqualTo(404);
        assertThat(admin.get("/library/bogus").status()).isEqualTo(404);
        assertThat(admin.write("POST", "/library/songs", Map.of()).status()).isEqualTo(404);
        assertThat(admin.get("/library/tracks", "path", "main/Album/missing.mp3").status()).isEqualTo(404);
    }

    @Test
    void paginationReachesEveryTrackOnce() {
        Client admin = adminClient();
        List<String> seen = new ArrayList<>();
        for (int off = 0; off < 10; off += 2) {
            JsonNode page = admin.get("/library/songs", "offset", String.valueOf(off), "limit", "2").jsonBody();
            assertThat(page.get("total").asInt()).isEqualTo(5);
            page.get("items").forEach(n -> seen.add(n.get("path").asText()));
        }
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void artworkIsServedInThreeSizesWithETagRevalidationAndRootChecks() throws IOException {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"));
        String[] sizes = { "small", "medium", "large" };
        int[] expected = { 160, 300, 800 };
        for (int i = 0; i < 3; i++) {
            ApiResponse r = lena.get("/library/artwork", "kind", "folder", "path", "main/Album", "size", sizes[i]);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.contentType()).isEqualTo("image/jpeg");
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(r.body()));
            assertThat(img.getWidth()).isEqualTo(expected[i]);
        }
        ApiResponse first = lena.get("/library/artwork", "kind", "track", "path", "main/Album/01 one.wav");
        String etag = first.headers().get("ETag");
        assertThat(etag).isNotBlank();
        ApiResponse again = lena.get("/library/artwork", Map.of("if-none-match", etag), "kind", "track", "path",
                "main/Album/01 one.wav");
        assertThat(again.status()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
        assertThat(lena.get("/library/artwork", "kind", "folder", "path", "main/Album", "size", "huge").status())
                .isEqualTo(400);
        assertThat(lena.get("/library/artwork", "kind", "folder", "path", "main/Other").status()).isEqualTo(404);
        // the art reference of a folder listing leads to the picture
        String art = lena.get("/library/folders", "path", "main").jsonBody().get("children").get(0).get("art")
                .asText();
        assertThat(art).startsWith("/api/v1/library/artwork?kind=folder&path=");
    }

    @Test
    void theOpenApiDescriptionIsServedWithoutLogin() {
        ApiResponse r = new Client().get("/openapi.yaml");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.contentType()).startsWith("application/yaml");
        String yaml = r.text();
        assertThat(yaml).startsWith("openapi: 3.");
        for (String path : new String[] { "/library/roots", "/library/folders", "/library/albums",
                "/library/artists", "/library/genres", "/library/songs", "/library/tracks", "/library/search",
                "/library/search/suggest", "/library/recent", "/library/random", "/library/artwork",
                "/library/albums/{id}", "/library/artists/{id}/tracks" }) {
            assertThat(yaml).as(path).contains("\n  " + path + ":");
        }
    }
}
