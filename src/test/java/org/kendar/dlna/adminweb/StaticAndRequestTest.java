package org.kendar.dlna.adminweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class StaticAndRequestTest {

    @Test
    void indexAndAssetsAreServedFromTheClasspath() {
        for (String p : new String[] { "", "/", "/index.html" }) {
            var a = StaticResources.find(p);
            assertThat(a).as(p).isPresent();
            assertThat(a.get().contentType()).startsWith("text/html");
            assertThat(new String(a.get().content())).contains("<div id=\"app\">");
        }
        assertThat(StaticResources.find("/js/main.js").orElseThrow().contentType()).startsWith("text/javascript");
        assertThat(StaticResources.find("/manifest.webmanifest").orElseThrow().contentType())
                .startsWith("application/manifest+json");
        assertThat(StaticResources.find("/css/app.css").orElseThrow().contentType()).startsWith("text/css");
        assertThat(StaticResources.find("/js/vendor/vue.esm-browser.prod.js")).isPresent();
        assertThat(StaticResources.find("/js/vendor/vue-router.esm-browser.prod.js")).isPresent();
        assertThat(StaticResources.find("/js/vendor/LICENSE-vue.txt")).isPresent();
        for (String page : new String[] { "login", "library", "settings", "metadata", "playlists", "admin", "browse" }) {
            assertThat(StaticResources.find("/js/pages/" + page + ".js")).as(page).isPresent();
        }
    }

    @Test
    void everyAssetTheIndexReferencesExists() {
        String html = new String(StaticResources.find("/").orElseThrow().content());
        var m = java.util.regex.Pattern.compile("(?:src|href)=\"([^\"]+)\"").matcher(html);
        int n = 0;
        while (m.find()) {
            assertThat(StaticResources.find("/" + m.group(1))).as(m.group(1)).isPresent();
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(5);
    }

    @Test
    void traversalAndOddPathsAreRefused() {
        for (String p : new String[] { "/../build.gradle", "/js/../../x", "/..", "/js/", "//etc/passwd",
                "/js\\app.js", "/nope.js", "/%2e%2e/x", "js/app.js" }) {
            assertThat(StaticResources.find(p)).as(p).isEmpty();
        }
    }

    @Test
    void queryAndCookieParsing() {
        assertThat(ApiRequest.parseQuery("path=main%2FA%20b&kind=front&flag"))
                .containsEntry("path", "main/A b").containsEntry("kind", "front").containsEntry("flag", "");
        assertThat(ApiRequest.parseQuery(null)).isEmpty();
        ApiRequest r = new ApiRequest("get", "/x", null, Map.of("cookie", "a=1; kd_session=tok; b=2"), null, null,
                false);
        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.cookie("kd_session")).isEqualTo("tok");
        assertThat(r.cookie("zzz")).isNull();
        assertThatThrownBy(() -> r.required("q")).isInstanceOf(ApiException.class);
    }

    @Test
    void errorModelHasCodeMessageDetails() {
        ApiResponse r = ApiResponse.error(418, "teapot", "short and stout", Map.of("a", 1));
        assertThat(r.jsonBody().get("code").asText()).isEqualTo("teapot");
        assertThat(r.jsonBody().get("message").asText()).isEqualTo("short and stout");
        assertThat(r.jsonBody().get("details").get("a").asInt()).isEqualTo(1);
    }

    @Test
    void buildInfoFallsBackWhenUnfiltered() {
        BuildInfo b = BuildInfo.load();
        assertThat(b.version()).isNotBlank();
        assertThat(b.commit()).isNotBlank();
    }
}
