package org.kendar.dlna.adminweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.platform.JettyHost;
import org.kendar.dlna.platform.NetworkPolicy;

/** The web app (ES modules, PWA files) as a browser fetches it, through Jetty. */
class WebAppStaticTest {

    private static final Pattern IMPORT = Pattern
            .compile("(?:import|export)\\s*(?:[^'\"();]*?\\s*from\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern DYNAMIC_IMPORT = Pattern.compile("import\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    private JettyHost host;

    @BeforeEach
    void start() throws Exception {
        // the test client is 127.0.0.1, which is not "LAN" here: /app must still be served, /admin must not
        host = new JettyHost(0, new NetworkPolicy(List.of("10.0.0.0/8"), null));
        host.contexts().addHandler(new StaticHandler().asContext());
        host.contexts().addHandler(new StaticHandler(StaticHandler.APP_PREFIX).asContext());
        host.start();
    }

    @AfterEach
    void stop() throws Exception {
        host.stop();
    }

    private HttpResponse<byte[]> get(String path) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String type(HttpResponse<?> r) {
        return r.headers().firstValue("Content-Type").orElse("");
    }

    @Test
    void indexManifestServiceWorkerAndIconsAreServedWithTheRightTypes() throws Exception {
        var index = get("/app/");
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(type(index)).startsWith("text/html");
        String html = new String(index.body());
        assertThat(html).contains("<div id=\"app\">").contains("type=\"module\"").contains("src=\"js/main.js\"")
                .contains("rel=\"manifest\"");
        assertThat(index.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(get("/app").statusCode()).isEqualTo(302);

        var manifest = get("/app/manifest.webmanifest");
        assertThat(manifest.statusCode()).isEqualTo(200);
        assertThat(type(manifest)).startsWith("application/manifest+json");
        String json = new String(manifest.body());
        assertThat(json).contains("\"start_url\"").contains("\"display\": \"standalone\"");
        // every icon the manifest names exists as an image
        Matcher icons = Pattern.compile("\"src\":\\s*\"([^\"]+)\"").matcher(json);
        int n = 0;
        while (icons.find()) {
            var icon = get("/app/" + icons.group(1));
            assertThat(icon.statusCode()).as(icons.group(1)).isEqualTo(200);
            assertThat(type(icon)).startsWith("image/");
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(2);

        var sw = get("/app/sw.js");
        assertThat(sw.statusCode()).isEqualTo(200);
        assertThat(type(sw)).startsWith("text/javascript");
        assertThat(new String(sw.body())).contains("/api/").doesNotContain("audio/");   // never caches data or audio
        assertThat(type(get("/app/css/player.css"))).startsWith("text/css");
        assertThat(type(get("/app/css/personal.css"))).startsWith("text/css");
        assertThat(html).contains("css/personal.css", "css/library.css");
        assertThat(type(get("/app/css/library.css"))).startsWith("text/css");
        // the shell cache is versioned so an updated app replaces the old one (v1 shipped the old library page)
        assertThat(new String(sw.body())).doesNotContain("kd-shell-v1").containsPattern("kd-shell-v[2-9]");
        assertThat(type(get("/app/icons/icon.svg"))).startsWith("image/svg+xml");
        assertThat(type(get("/app/icons/icon-192.png"))).isEqualTo("image/png");
    }

    @Test
    void everyEsModuleOfTheImportGraphIsServedAsJavaScript() throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of("/app/js/main.js"));
        while (!todo.isEmpty()) {
            String path = todo.pop();
            if (!seen.add(path)) {
                continue;
            }
            var r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(type(r)).as(path).startsWith("text/javascript");
            assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            String src = new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
            for (Pattern p : List.of(IMPORT, DYNAMIC_IMPORT)) {
                Matcher m = p.matcher(src);
                while (m.find()) {
                    String spec = m.group(1);
                    assertThat(spec).as("bare import specifier in " + path + " (no import map is used)")
                            .matches("\\.{1,2}/.*");
                    todo.push(URI.create("http://x" + path).resolve(spec).getPath());
                }
            }
        }
        // the graph reaches the vendored builds, the app modules, the pages and the pure core modules
        assertThat(seen).contains("/app/js/vendor/vue.esm-browser.prod.js", "/app/js/vendor/vue-router.esm-browser.prod.js",
                "/app/js/vue.js", "/app/js/router.js", "/app/js/api.js", "/app/js/player/engine.js",
                "/app/js/player/controller.js", "/app/js/core/queue.js", "/app/js/core/formats.js",
                "/app/js/pages/browse.js", "/app/js/pages/library.js", "/app/js/pages/admin.js",
                "/app/js/components/PlayerBar.js", "/app/js/extensions/personal.js", "/app/js/i18n/en.js",
                // personal data (M6/M7) and admin users (M8): pages, runtime glue and the pure modules
                "/app/js/extensions/admin.js", "/app/js/pages/personal.js", "/app/js/pages/admin-users.js",
                "/app/js/personal/runtime.js", "/app/js/personal/favorites.js", "/app/js/personal/loaders.js",
                "/app/js/components/EntryList.js", "/app/js/components/FavButton.js",
                "/app/js/components/AddToPlaylistDialog.js", "/app/js/core/playlist-ops.js",
                "/app/js/core/play-report.js", "/app/js/core/queue-sync.js", "/app/js/core/favorites.js",
                "/app/js/core/user-form.js", "/app/js/core/track.js",
                // list.json editor (page + pure module)
                "/app/js/pages/list-json.js", "/app/js/core/list-json.js",
                // admin library page: tree, row menu and pure logic
                "/app/js/components/DirTree.js", "/app/js/components/RowMenu.js", "/app/js/core/library.js");
        assertThat(seen.size()).isGreaterThan(50);
    }

    @Test
    void theSectionsEditorOfASingleFileIsWiredIntoTheLibraryAndTheListPage() throws Exception {
        java.util.function.Function<String, String> text = p -> {
            try {
                return new String(get(p).body(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(text.apply("/app/js/api.js")).contains("/admin/folders/list/file?",
                "/admin/folders/list/file/generate?", "fileList(", "saveFileList(", "generateFileList(");
        assertThat(text.apply("/app/js/router.js")).contains("path: '/sections'");
        assertThat(text.apply("/app/js/pages/list-json.js")).contains("fileMode", "Api.fileList", "Api.saveFileList",
                "Api.generateFileList", "listjson.file.addSection", "sidecarLinks");
        assertThat(text.apply("/app/js/pages/library.js")).contains("sectionsRoute(c.path)", "listjson.file.open");
        assertThat(text.apply("/app/js/core/list-json.js")).contains("export function sidecarName",
                "export function splitFilePath", "export function rowsForFile", "export function addSection");
        assertThat(text.apply("/app/js/i18n/en.js")).contains("'listjson.file.open'", "'listjson.file.title'",
                "'listjson.file.help'", "'listjson.file.saved'", "'listjson.file.addSection'");
    }

    @Test
    void theLibraryPageUsesThePlayerLookAndKeepsEveryAdminFunction() throws Exception {
        java.util.function.Function<String, String> text = p -> {
            try {
                return new String(get(p).body(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        String page = text.apply("/app/js/pages/library.js");
        assertThat(page).contains("<dir-tree", "class=\"tiles\"", "tracklist lib-tracks", "<cover", "<row-menu",
                "path: '/settings'", "path: '/metadata'", "path: '/list-json'", "sectionsRoute(", "Api.rescan(",
                "lib.forceTags", "lib.reload", "folder.hasRear", "folder.hasFront", "folderBadges", "trackBadges",
                "kd.", "lib.expanded");
        assertThat(text.apply("/app/js/components/DirTree.js")).contains("role=\"tree\"", "role=\"treeitem\"",
                "aria-expanded", "scrollIntoView", "treeKey(");
        String css = text.apply("/app/css/library.css");
        assertThat(css).contains(".lib-tree", "@media (max-width: 860px)", ".lib-row", ".dirtree");
        assertThat(text.apply("/app/js/i18n/en.js")).contains("'lib.rescan'", "'lib.badge.sync'", "'lib.editTags'");
    }

    @Test
    void settingsPageOffersKeepWholeFilesAndListEditorTheGenerateButton() throws Exception {
        String settings = new String(get("/app/js/pages/settings.js").body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(settings).contains("v-model=\"form.keepWholeFiles\"", "keepWholeFiles: !!form.keepWholeFiles",
                "settings.keepWholeFiles.help");
        String editor = new String(get("/app/js/pages/list-json.js").body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(editor).contains("listjson.gen.button", "generateListJson");
        assertThat(new String(get("/app/js/api.js").body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("/admin/folders/list/generate");
        String en = new String(get("/app/js/i18n/en.js").body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(en).contains("'settings.keepWholeFiles'", "'listjson.gen.button'", "'listjson.col.index'",
                "'listjson.col.start'");
    }

    @Test
    void vendoredLibrariesShipWithTheirLicences() throws Exception {
        assertThat(type(get("/app/js/vendor/LICENSE-vue.txt"))).startsWith("text/plain");
        assertThat(get("/app/js/vendor/LICENSE-vue-router.txt").statusCode()).isEqualTo(200);
        // vue-router must import Vue by a relative URL: the CSP forbids the inline import map a bare name would need
        String router = new String(get("/app/js/vendor/vue-router.esm-browser.prod.js").body());
        assertThat(router).contains("from\"./vue.esm-browser.prod.js\"").doesNotContain("from\"vue\"");
    }

    @Test
    void theAppIsOpenToAnyAddressWhileTheAdminMountStaysLanOnly() throws Exception {
        assertThat(get("/app/").statusCode()).isEqualTo(200);
        assertThat(get("/admin/").statusCode()).isEqualTo(403);
        assertThat(get("/admin/js/main.js").statusCode()).isEqualTo(403);
    }

    @Test
    void unknownAssetsAndTraversalAre404() throws Exception {
        assertThat(get("/app/js/nope.js").statusCode()).isEqualTo(404);
        assertThat(get("/app/js/%2e%2e/%2e%2e/build.gradle").statusCode()).isIn(400, 404);
    }
}
