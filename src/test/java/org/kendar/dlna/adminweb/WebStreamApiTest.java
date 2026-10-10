package org.kendar.dlna.adminweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.curation.FolderService;
import org.kendar.dlna.curation.PerFormatTagWriter;
import org.kendar.dlna.curation.PlaylistService;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.delivery.Prefetcher;
import org.kendar.dlna.delivery.StreamResolver;
import org.kendar.dlna.delivery.TranscodeCache;
import org.kendar.dlna.delivery.TranscodeService;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.PasswordHasher;
import org.kendar.dlna.identity.SessionStore;
import org.kendar.dlna.ingestion.ScanTrigger;
import org.kendar.dlna.libraryquery.LibraryQuery;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.testutil.LibraryFixture;
import org.kendar.dlna.transcoding.MidiRenderer;
import org.kendar.dlna.transcoding.Transcoder;
import org.kendar.dlna.views.Views;

/**
 * {@code /api/v1/stream/...} through the REST seam on a real library: allowed roots on every endpoint, download
 * permission, Range (native and transcoded), format negotiation, prefetch and the per-user transcode cap.
 */
class WebStreamApiTest {

    static final String FLAC = "main/Album/02 two.flac";
    static final String WAV = "main/Album/01 one.wav";
    static final String MP3 = "main/Album/03 three.mp3";
    static final String MID = "main/Album/04 four.mid";
    static final String OTHER = "extra/Disc/x.wav";

    @TempDir
    Path tmp;
    LibraryFixture lib;
    IdentityService identity;
    AdminApi api;
    TranscodeCache cache;
    Prefetcher prefetcher;
    ActiveStreams active = new ActiveStreams();
    final List<String> announced = new ArrayList<>();
    String adminPassword;

    @BeforeEach
    void setUp() throws IOException {
        lib = new LibraryFixture(tmp);
        Path extra = Files.createDirectories(tmp.resolve("extra/Disc"));
        AudioFiles.fixture("sweep.wav", extra.resolve("x.wav"));
        lib.roots.put("extra", tmp.resolve("extra"));
        lib.rescans.rescanNow(ScanTrigger.MANUAL, null);
        identity = new IdentityService(tmp.resolve("users"), List.of("main", "extra"), null,
                new PasswordHasher(1000, new SecureRandom()), new SessionStore(lib.clock), lib.clock,
                new SecureRandom(), announced::add);
        identity.bootstrap(Optional.empty());
        adminPassword = announced.get(0).lines().filter(l -> l.contains("password:")).findFirst().orElseThrow()
                .replaceFirst(".*password:\\s*", "").trim();
        TagService tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
        AdminServices svc = new AdminServices(lib.catalog, lib.rescans, tags,
                new FolderService(lib.catalog, lib.files, tags), new PlaylistService(lib.catalog, lib.playlists),
                new Shuffle(lib.catalog::current), active, null, () -> 0,
                new BuildInfo("1.2.3", "abc123", "2026-01-01T00:00:00Z"), lib.clock);
        cache = new TranscodeCache(tmp.resolve("cache"), 64L << 20, Duration.ofHours(1), Clock.systemUTC());
        TranscodeService transcode = new TranscodeService(new Transcoder(new MidiRenderer(null)), cache, true, 128,
                "builtin", (t, samples, millis) -> {
                });
        StreamResolver resolver = new StreamResolver(lib.catalog::current, lib.files::resolve, ref -> {
        }, ContainerNavigation.NONE, transcode, null);
        prefetcher = new Prefetcher(transcode, ContainerNavigation.NONE, lib.files::resolve,
                Prefetcher.lowPriorityPool(1));
        api = new AdminApi(svc, identity, LibraryQuery.basic(lib.catalog::current),
                new WebStream(lib.catalog::current, lib.files::resolve, resolver, transcode, prefetcher, active));
    }

    @AfterEach
    void tearDown() {
        prefetcher.close();
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
            if (out.status() == 200 && out.contentType() != null && out.contentType().startsWith("application/json")
                    && out.text().contains("csrfToken")) {
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

    private Client userClient(Client admin, String name, List<String> roots, boolean download) {
        ApiResponse r = admin.write("POST", "/admin/users",
                Map.of("username", name, "allowedRoots", roots, "downloadAllowed", download));
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        Client c = new Client();
        ApiResponse acc = c.write("POST", "/auth/invite/accept", Map.of("token",
                r.jsonBody().get("invite").get("token").asText(), "password", name + "-password-1"));
        assertThat(acc.status()).as(acc.text()).isEqualTo(200);
        return c;
    }

    /** The bytes a response sends (the window of a streamed body, which also ends the stream). */
    private static byte[] bytesOf(ApiResponse r) throws IOException {
        if (r.streamed() == null) {
            return r.body();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            r.streamed().body().writeTo(r.streamed().start(), r.streamed().length(), out);
        } finally {
            r.streamed().done().run();
        }
        return out.toByteArray();
    }

    private static void finish(ApiResponse r) {
        if (r.streamed() != null) {
            r.streamed().done().run();
        }
    }

    // ---- allowed roots ----

    @Test
    void streamDownloadAndPrefetchRefuseRootsOutsideTheAllowedOnes() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), true);
        for (String p : new String[] { OTHER, "extra/Disc/missing.wav", "nowhere/a.mp3" }) {
            for (String ep : new String[] { "/stream", "/stream/download" }) {
                ApiResponse r = lena.get(ep, "path", p);
                assertThat(r.status()).as(ep + " " + p).isEqualTo(403);
                assertThat(r.jsonBody().get("code").asText()).isEqualTo("forbidden_root");
            }
            ApiResponse pf = lena.write("POST", "/stream/prefetch", Map.of("path", p, "formats", List.of("mp3")));
            assertThat(pf.status()).as("prefetch " + p).isEqualTo(403);
            assertThat(pf.jsonBody().get("code").asText()).isEqualTo("forbidden_root");
        }
        // allowed root, missing file: 404, not 403
        assertThat(lena.get("/stream", "path", "main/Album/missing.wav").status()).isEqualTo(404);
        // the admin reaches every root
        ApiResponse ok = admin.get("/stream", "path", OTHER);
        assertThat(ok.status()).isEqualTo(200);
        finish(ok);
        assertThat(new Client().get("/stream", "path", WAV).status()).isEqualTo(401);
        assertThat(new Client().write("POST", "/stream/prefetch", Map.of("path", WAV)).status()).isEqualTo(401);
    }

    @Test
    void aRootRemovedFromTheUserStopsWorkingImmediately() {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main", "extra"), false);
        ApiResponse before = lena.get("/stream", "path", OTHER);
        assertThat(before.status()).isEqualTo(200);
        finish(before);
        String id = identity.users().stream().filter(u -> u.username().equals("lena")).findFirst().orElseThrow().id();
        assertThat(admin.write("PUT", "/admin/users/" + id, Map.of("allowedRoots", List.of("main"))).status())
                .isEqualTo(200);
        assertThat(lena.get("/stream", "path", OTHER).status()).isEqualTo(403);
    }

    // ---- download ----

    @Test
    void downloadNeedsThePermissionAndSendsTheOriginalWithContentDisposition() throws IOException {
        Client admin = adminClient();
        Client plain = userClient(admin, "plain", List.of("main"), false);
        Client dl = userClient(admin, "dl", List.of("main"), true);
        ApiResponse denied = plain.get("/stream/download", "path", FLAC);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.jsonBody().get("code").asText()).isEqualTo("forbidden");
        // streaming is not affected by the missing permission
        ApiResponse stream = plain.get("/stream", "path", FLAC, "formats", "flac");
        assertThat(stream.status()).isEqualTo(200);
        finish(stream);

        byte[] original = Files.readAllBytes(lib.file(FLAC));
        for (Client c : List.of(dl, admin)) {
            ApiResponse r = c.get("/stream/download", "path", FLAC);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.headers().get("Content-Disposition")).startsWith("attachment; filename=\"02 two.flac\"")
                    .contains("filename*=UTF-8''02%20two.flac");
            assertThat(r.headers().get("Content-Length")).isEqualTo(String.valueOf(original.length));
            assertThat(r.contentType()).isEqualTo("audio/flac");
            assertThat(bytesOf(r)).isEqualTo(original);
        }
        ApiResponse range = dl.get("/stream/download", Map.of("range", "bytes=5-14"), "path", FLAC);
        assertThat(range.status()).isEqualTo(206);
        assertThat(bytesOf(range)).isEqualTo(Arrays.copyOfRange(original, 5, 15));
        // the original of a MIDI file is downloadable too (not its rendering)
        ApiResponse mid = dl.get("/stream/download", "path", MID);
        assertThat(mid.status()).isEqualTo(200);
        assertThat(bytesOf(mid)).isEqualTo(Files.readAllBytes(lib.file(MID)));
        assertThat(dl.get("/stream/download", "path", "main/Album/missing.flac").status()).isEqualTo(404);
        assertThat(dl.get("/stream/download").status()).isEqualTo(400);
    }

    @Test
    void contentDispositionEncodesNonAsciiAndQuotes() {
        String v = StreamResources.contentDisposition("Café \"x\".mp3");
        assertThat(v).isEqualTo("attachment; filename=\"Caf_ 'x'.mp3\"; filename*=UTF-8''Caf%C3%A9%20%22x%22.mp3");
    }

    // ---- Range ----

    @Test
    void nativeStreamsHonorRange() throws IOException {
        Client admin = adminClient();
        byte[] original = Files.readAllBytes(lib.file(MP3));
        ApiResponse full = admin.get("/stream", "path", MP3, "formats", "mp3");
        assertThat(full.status()).isEqualTo(200);
        assertThat(full.headers()).containsEntry("Accept-Ranges", "bytes")
                .containsEntry("Content-Length", String.valueOf(original.length))
                .containsEntry("X-Stream-Transcoded", "false").containsEntry("X-Stream-Format", "mp3");
        assertThat(full.headers()).doesNotContainKey("contentFeatures.dlna.org");
        assertThat(bytesOf(full)).isEqualTo(original);

        ApiResponse part = admin.get("/stream", Map.of("range", "bytes=100-199"), "path", MP3, "formats", "mp3");
        assertThat(part.status()).isEqualTo(206);
        assertThat(part.headers()).containsEntry("Content-Range", "bytes 100-199/" + original.length)
                .containsEntry("Content-Length", "100");
        assertThat(bytesOf(part)).isEqualTo(Arrays.copyOfRange(original, 100, 200));

        ApiResponse suffix = admin.get("/stream", Map.of("range", "bytes=-50"), "path", MP3, "formats", "mp3");
        assertThat(bytesOf(suffix)).isEqualTo(Arrays.copyOfRange(original, original.length - 50, original.length));

        ApiResponse beyond = admin.get("/stream", Map.of("range", "bytes=" + original.length + "-"), "path", MP3,
                "formats", "mp3");
        assertThat(beyond.status()).isEqualTo(416);
        assertThat(beyond.headers()).containsEntry("Content-Range", "bytes */" + original.length);

        ApiResponse head = admin.send(ApiRequest.of("HEAD", "/stream").withQuery("path", MP3)
                .withQuery("formats", "mp3"));
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.streamed()).isNull();
        assertThat(head.headers()).containsEntry("Content-Length", String.valueOf(original.length));
    }

    @Test
    void transcodedStreamsHaveExactLengthAndSeekableRanges() throws Exception {
        Client admin = adminClient();
        ApiResponse full = admin.get("/stream", "path", FLAC, "formats", "mp3");
        assertThat(full.status()).isEqualTo(200);
        assertThat(full.headers()).containsEntry("X-Stream-Format", "mp3").containsEntry("X-Stream-Transcoded", "true");
        assertThat(full.contentType()).isEqualTo("audio/mpeg");
        long length = Long.parseLong(full.headers().get("Content-Length"));
        byte[] all = bytesOf(full);
        assertThat(all).hasSize((int) length);

        // a live seek encodes from a fresh frame: exact window and length, bytes identical once cached
        ApiResponse live = admin.get("/stream", Map.of("range", "bytes=1000-1999"), "path", FLAC, "formats", "mp3");
        assertThat(live.status()).isEqualTo(206);
        assertThat(live.headers()).containsEntry("Content-Range", "bytes 1000-1999/" + length)
                .containsEntry("Content-Length", "1000");
        assertThat(bytesOf(live)).hasSize(1000);
        admin.write("POST", "/stream/prefetch", Map.of("path", FLAC, "formats", List.of("mp3")));
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(60))).isTrue();
        byte[] cached = bytesOf(admin.get("/stream", "path", FLAC, "formats", "mp3"));
        assertThat(cached).hasSize((int) length);
        ApiResponse part = admin.get("/stream", Map.of("range", "bytes=1000-1999"), "path", FLAC, "formats", "mp3");
        assertThat(part.status()).isEqualTo(206);
        assertThat(bytesOf(part)).isEqualTo(Arrays.copyOfRange(cached, 1000, 2000));
        all = cached;

        ApiResponse tail = admin.get("/stream", Map.of("range", "bytes=" + (length - 700) + "-"), "path", FLAC,
                "formats", "mp3");
        assertThat(bytesOf(tail)).isEqualTo(Arrays.copyOfRange(all, (int) length - 700, (int) length));
    }

    // ---- format negotiation ----

    @Test
    void theServerPicksTheNativeFileElseMp3AndMidiIsAlwaysMp3() throws IOException {
        Client admin = adminClient();
        // default list (mp3 ogg flac wav opus): flac and wav native
        for (String p : new String[] { FLAC, WAV, MP3 }) {
            ApiResponse r = admin.get("/stream", "path", p);
            assertThat(r.headers()).as(p).containsEntry("X-Stream-Transcoded", "false");
            finish(r);
        }
        ApiResponse mid = admin.get("/stream", "path", MID, "formats", "mp3,flac,wav,ogg,opus,aac");
        assertThat(mid.status()).isEqualTo(200);
        assertThat(mid.headers()).containsEntry("X-Stream-Format", "mp3").containsEntry("X-Stream-Transcoded", "true");
        assertThat(mid.contentType()).isEqualTo("audio/mpeg");
        assertThat(bytesOf(mid)).hasSize(Integer.parseInt(mid.headers().get("Content-Length")));

        // the list is sent once per session and applies to the following requests
        assertThat(admin.get("/stream/formats").jsonBody().get("formats").toString()).contains("flac");
        ApiResponse set = admin.write("POST", "/stream/formats", Map.of("formats", List.of("MP3", "bogus", "ogg")));
        assertThat(set.status()).isEqualTo(200);
        assertThat(set.jsonBody().get("formats").toString()).isEqualTo("[\"mp3\",\"ogg\"]");
        ApiResponse flac = admin.get("/stream", "path", FLAC);
        assertThat(flac.headers()).containsEntry("X-Stream-Format", "mp3").containsEntry("X-Stream-Transcoded", "true");
        finish(flac);
        ApiResponse mp3 = admin.get("/stream", "path", MP3);
        assertThat(mp3.headers()).containsEntry("X-Stream-Transcoded", "false");
        finish(mp3);
        // an explicit query list overrides the session's
        ApiResponse override = admin.get("/stream", "path", FLAC, "formats", "flac");
        assertThat(override.headers()).containsEntry("X-Stream-Format", "flac");
        finish(override);
        // another session keeps the default
        Client other = adminOtherSession();
        ApiResponse f2 = other.get("/stream", "path", FLAC);
        assertThat(f2.headers()).containsEntry("X-Stream-Transcoded", "false");
        finish(f2);
        assertThat(admin.write("POST", "/stream/formats", Map.of("formats", "mp3")).status()).isEqualTo(400);
    }

    private Client adminOtherSession() {
        Client c = new Client();
        assertThat(c.write("POST", "/auth/login", Map.of("username", "admin", "password", "admin-chosen-password"))
                .status()).isEqualTo(200);
        return c;
    }

    @Test
    void streamsAreListedForTheAdminWhileTheyAreSent() throws IOException {
        Client admin = adminClient();
        ApiResponse r = admin.get("/stream", "path", MP3);
        assertThat(active.count()).isEqualTo(1);
        assertThat(active.list().get(0).containerId()).isEqualTo("web");
        bytesOf(r);
        assertThat(active.count()).isZero();
    }

    // ---- prefetch ----

    @Test
    void prefetchWarmsTheCacheOnlyWhenTranscodingIsNeeded() throws Exception {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), false);
        ApiResponse native1 = lena.write("POST", "/stream/prefetch", Map.of("path", FLAC, "formats", List.of("flac")));
        assertThat(native1.status()).isEqualTo(202);
        assertThat(native1.jsonBody().get("prefetching").asBoolean()).isFalse();
        assertThat(native1.jsonBody().get("reason").asText()).isEqualTo("native");
        assertThat(cache.entryCount()).isZero();

        ApiResponse hint = lena.write("POST", "/stream/prefetch", Map.of("path", FLAC, "formats", List.of("mp3")));
        assertThat(hint.status()).isEqualTo(202);
        assertThat(hint.jsonBody().get("prefetching").asBoolean()).isTrue();
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(60))).isTrue();
        assertThat(cache.entryCount()).isEqualTo(1);

        // now it is cached: nothing to do, and the stream is served from the file
        ApiResponse again = lena.write("POST", "/stream/prefetch", Map.of("path", FLAC, "formats", List.of("mp3")));
        assertThat(again.jsonBody().get("prefetching").asBoolean()).isFalse();
        assertThat(again.jsonBody().get("reason").asText()).isEqualTo("cached");
        ApiResponse stream = lena.get("/stream", "path", FLAC, "formats", "mp3");
        assertThat(stream.streamed().body().file()).isNotNull();
        finish(stream);

        assertThat(lena.write("POST", "/stream/prefetch", Map.of("path", "main/Album/nope.flac")).status())
                .isEqualTo(404);
        assertThat(lena.write("POST", "/stream/prefetch", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void prefetchUsesTheSessionFormats() throws Exception {
        Client admin = adminClient();
        admin.write("POST", "/stream/formats", Map.of("formats", List.of("mp3")));
        ApiResponse hint = admin.write("POST", "/stream/prefetch", Map.of("path", WAV));
        assertThat(hint.jsonBody().get("prefetching").asBoolean()).isTrue();
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(60))).isTrue();
        assertThat(cache.entryCount()).isEqualTo(1);
    }

    // ---- per-user transcode cap ----

    @Test
    void atMostTwoConcurrentTranscodesPerUser() throws IOException {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), false);
        Client ben = userClient(admin, "ben", List.of("main"), false);
        ApiResponse a = lena.get("/stream", "path", FLAC, "formats", "mp3");
        ApiResponse b = lena.get("/stream", "path", WAV, "formats", "mp3");
        assertThat(a.status()).isEqualTo(200);
        assertThat(b.status()).isEqualTo(200);
        assertThat(a.streamed().body().file()).isNull();   // live encodes hold a permit

        ApiResponse c = lena.get("/stream", "path", FLAC, "formats", "mp3");
        assertThat(c.status()).isEqualTo(429);
        assertThat(c.jsonBody().get("code").asText()).isEqualTo("too_many_transcodes");
        // natives are not limited, nor are other users
        ApiResponse nat = lena.get("/stream", "path", MP3, "formats", "mp3");
        assertThat(nat.status()).isEqualTo(200);
        finish(nat);
        ApiResponse other = ben.get("/stream", "path", FLAC, "formats", "mp3");
        assertThat(other.status()).isEqualTo(200);
        finish(other);

        finish(a);   // one stream ends: a place is free again
        ApiResponse d = lena.get("/stream", "path", FLAC, "formats", "mp3");
        assertThat(d.status()).isEqualTo(200);
        assertThat(lena.get("/stream", "path", WAV, "formats", "mp3").status()).isEqualTo(429);
        finish(b);
        finish(d);
        ApiResponse e = lena.get("/stream", "path", FLAC, "formats", "mp3");
        assertThat(e.status()).isEqualTo(200);
        finish(e);
    }

    @Test
    void headAndCachedTranscodesDoNotHoldAPermit() throws Exception {
        Client admin = adminClient();
        Client lena = userClient(admin, "lena", List.of("main"), false);
        for (int i = 0; i < 4; i++) {
            ApiResponse h = lena.send(ApiRequest.of("HEAD", "/stream").withQuery("path", FLAC)
                    .withQuery("formats", "mp3"));
            assertThat(h.status()).isEqualTo(200);
            assertThat(h.headers().get("Content-Length")).isNotNull();
        }
        // once cached, any number of concurrent streams is fine
        assertThat(lena.write("POST", "/stream/prefetch", Map.of("path", FLAC, "formats", List.of("mp3")))
                .status()).isEqualTo(202);
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(60))).isTrue();
        List<ApiResponse> open = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ApiResponse r = lena.get("/stream", "path", FLAC, "formats", "mp3");
            assertThat(r.status()).isEqualTo(200);
            open.add(r);
        }
        open.forEach(WebStreamApiTest::finish);
    }

    @Test
    void trackObjectsAndStreamAgreeOnPlayability() throws IOException {
        Client admin = adminClient();
        String formats = "mp3,ogg";
        String text = admin.get("/library/tracks", "path", FLAC, "formats", formats).text();
        assertThat(text).contains("\"playability\" : \"transcoded\"").contains("\"streamFormat\" : \"mp3\"");
        ApiResponse r = admin.get("/stream", "path", FLAC, "formats", formats);
        assertThat(r.headers()).containsEntry("X-Stream-Format", "mp3");
        finish(r);
    }
}
