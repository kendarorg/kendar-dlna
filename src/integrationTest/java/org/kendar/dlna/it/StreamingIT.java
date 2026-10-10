package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jupnp.support.model.Res;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The {@code res} elements of every item: HEAD, GET, Range (206 / 416), Content-Length against the delivered bytes,
 * DLNA headers, and the structure of the transcoded MP3 variant (constant frame size, so no decoder is needed).
 */
@Order(30)
@TestMethodOrder(MethodOrderer.MethodName.class)
class StreamingIT {

    /** One {@code res}: the URL as reachable from here and what DIDL says about it. */
    record Resource(String itemPath, String itemId, String url, String key, Res res) {
        String mime() {
            return res.getProtocolInfo().getContentFormat();
        }

        String additionalInfo() {
            return res.getProtocolInfo().getAdditionalInfo();
        }

        boolean transcodedMp3() {
            String[] seg = url.split("\\?")[0].split("/");
            return seg[seg.length - 1].startsWith("mp3");
        }
    }

    private final Server server = Server.get();

    private List<Resource> all;
    private Map<String, Resource> unique;

    private void load() {
        if (all != null) {
            return;
        }
        all = new ArrayList<>();
        unique = new LinkedHashMap<>();
        for (Tree.Node n : Tree.shared().items()) {
            for (Res res : n.resources()) {
                String url = server.rebase(res.getValue());
                String[] seg = url.split("\\?")[0].split("/stream/", 2)[1].split("/");
                assertThat(seg.length).as("stream url %s", url).isBetween(2, 3);
                String key = seg[1] + "/" + (seg.length == 3 ? seg[2] : "native");
                Resource r = new Resource(n.path(), n.id(), url, key, res);
                all.add(r);
                unique.putIfAbsent(key, r);
            }
        }
        assertThat(all).isNotEmpty();
    }

    /** The unique resources to stream; an external server may be big, so it is capped there. */
    private List<Resource> uniqueResources() {
        load();
        List<Resource> list = new ArrayList<>(unique.values());
        if (!server.managed() && list.size() > ItEnv.maxStreamedItems()) {
            // spread the sample over the library instead of taking the first N
            List<Resource> sample = new ArrayList<>();
            double step = (double) list.size() / ItEnv.maxStreamedItems();
            for (int i = 0; i < ItEnv.maxStreamedItems(); i++) {
                sample.add(list.get((int) (i * step)));
            }
            return sample;
        }
        return list;
    }

    private static String baseMime(String contentType) {
        return contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
    }

    @Test
    void everyResourceAnswersHeadWithTheDeclaredLengthAndDlnaHeaders() {
        load();
        List<Resource> sample = server.managed() ? all : uniqueResources();
        for (Resource r : sample) {
            Http.Response h = Http.head(r.url(), "getcontentFeatures.dlna.org", "1", "transferMode.dlna.org",
                    "Streaming");
            assertThat(h.status()).as("HEAD %s (%s)", r.url(), r.itemPath()).isEqualTo(200);
            assertThat(h.header("content-length")).as("Content-Length of %s", r.url()).isPresent();
            long length = Long.parseLong(h.header("content-length").orElseThrow());
            assertThat(length).isPositive();
            if (r.res().getSize() != null) {
                assertThat(length).as("declared size of %s", r.url()).isEqualTo(r.res().getSize());
            }
            assertThat(h.header("content-type")).as("Content-Type of %s", r.url()).isPresent();
            assertThat(baseMime(h.header("content-type").orElseThrow())).as("mime of %s", r.url())
                    .isEqualTo(r.mime().toLowerCase(Locale.ROOT));
            assertThat(h.header("accept-ranges")).as("Accept-Ranges of %s", r.url()).hasValue("bytes");
            assertThat(h.header("transfermode.dlna.org")).as("transferMode of %s", r.url()).isPresent();
            assertThat(h.header("contentfeatures.dlna.org")).as("contentFeatures of %s", r.url()).isPresent();
            assertThat(h.body()).isEmpty();
        }
    }

    @Test
    void protocolInfoOfEveryResourceIsWellFormedDlna() {
        load();
        for (Resource r : all) {
            assertThat(r.mime()).as("mime of %s", r.url()).matches("audio/[a-zA-Z0-9.+-]+");
            assertThat(r.additionalInfo()).as("fourth field of %s", r.url()).contains("DLNA.ORG_OP=01")
                    .contains("DLNA.ORG_FLAGS=");
            if (r.transcodedMp3()) {
                assertThat(r.mime()).isEqualTo("audio/mpeg");
                assertThat(r.additionalInfo()).contains("DLNA.ORG_PN=MP3");
            }
        }
    }

    @Test
    void getDeliversExactlyContentLengthBytes() {
        for (Resource r : uniqueResources()) {
            Http.Response h = Http.head(r.url());
            Http.Response g = Http.get(r.url());
            assertThat(g.status()).as("GET %s (%s)", r.url(), r.itemPath()).isEqualTo(200);
            long declared = Long.parseLong(g.header("content-length").orElseThrow());
            assertThat((long) g.body().length).as("delivered bytes of %s", r.url()).isEqualTo(declared);
            assertThat(declared).as("HEAD vs GET length of %s", r.url())
                    .isEqualTo(Long.parseLong(h.header("content-length").orElseThrow()));
            if (r.res().getSize() != null) {
                assertThat(declared).as("res@size of %s", r.url()).isEqualTo(r.res().getSize());
            }
        }
    }

    @Test
    void rangeRequestsReturn206WithMatchingContentRangeAndBytes() {
        for (Resource r : uniqueResources()) {
            byte[] full = Http.get(r.url()).body();
            long total = full.length;
            long mid = total / 2;
            long[][] ranges = { { 0, 99 }, { 1, 1 }, { mid, mid + 49 }, { total - 10, total - 1 }, { 5, total + 1000 } };
            for (long[] range : ranges) {
                long from = range[0];
                long to = Math.min(range[1], total - 1);
                if (from > to) {
                    continue;
                }
                Http.Response p = Http.get(r.url(), "Range", "bytes=" + range[0] + "-" + range[1]);
                String what = "Range " + range[0] + "-" + range[1] + " of " + r.url();
                assertThat(p.status()).as(what).isEqualTo(206);
                assertThat(p.header("content-range")).as(what).hasValue("bytes " + from + "-" + to + "/" + total);
                assertThat(p.header("content-length")).as(what).hasValue(Long.toString(to - from + 1));
                assertThat((long) p.body().length).as("delivered bytes, " + what).isEqualTo(to - from + 1);
                if (!r.transcodedMp3()) {
                    assertThat(p.body()).as("bytes, " + what).isEqualTo(Arrays.copyOfRange(full, (int) from, (int) to + 1));
                } else if (from % 960 == 0 && p.body().length >= 2) {
                    // an uncached transcode is re-encoded from the frame at the offset: same structure, not the same bytes
                    assertThat(p.body()[0] & 0xFF).as("frame sync, " + what).isEqualTo(0xFF);
                    assertThat(p.body()[1] & 0xE0).as("frame sync, " + what).isEqualTo(0xE0);
                }
            }
            // open ended and suffix ranges
            Http.Response open = Http.get(r.url(), "Range", "bytes=" + (total - 20) + "-");
            assertThat(open.status()).isEqualTo(206);
            assertThat(open.body()).hasSize(20);
            Http.Response suffix = Http.get(r.url(), "Range", "bytes=-30");
            assertThat(suffix.status()).as("suffix range of %s", r.url()).isEqualTo(206);
            assertThat(suffix.body()).hasSize(30);
            Http.Response everything = Http.get(r.url(), "Range", "bytes=0-");
            assertThat(everything.status()).isEqualTo(206);
            assertThat(everything.header("content-range")).hasValue("bytes 0-" + (total - 1) + "/" + total);
            assertThat(everything.body()).hasSize(full.length);
            if (!r.transcodedMp3()) {
                assertThat(open.body()).isEqualTo(Arrays.copyOfRange(full, (int) total - 20, (int) total));
                assertThat(suffix.body()).isEqualTo(Arrays.copyOfRange(full, (int) total - 30, (int) total));
                assertThat(everything.body()).isEqualTo(full);
            }
        }
    }

    @Test
    void unsatisfiableRangeIs416WithTheTotalInContentRange() {
        for (Resource r : uniqueResources()) {
            long total = Long.parseLong(Http.head(r.url()).header("content-length").orElseThrow());
            Http.Response p = Http.get(r.url(), "Range", "bytes=" + total + "-");
            assertThat(p.status()).as("start == length for %s", r.url()).isEqualTo(416);
            assertThat(p.header("content-range")).as("Content-Range of the 416").hasValue("bytes */" + total);
            Http.Response far = Http.get(r.url(), "Range", "bytes=" + (total + 5000) + "-" + (total + 6000));
            assertThat(far.status()).isEqualTo(416);
        }
    }

    @Test
    void malformedRangeHeaderIsIgnoredOrRejectedButNeverAnError() {
        Resource r = uniqueResources().get(0);
        for (String bad : new String[] { "bytes=abc", "bytes=5-2", "items=0-5", "bytes=" }) {
            int status = Http.get(r.url(), "Range", bad).status();
            assertThat(status).as("Range: %s", bad).isIn(200, 206, 400, 416);
        }
    }

    @Test
    void unknownStreamsAre404() {
        Resource r = uniqueResources().get(0);
        assertThat(Http.get(server.baseUrl() + "/stream/nosuch/none").status()).isEqualTo(404);
        assertThat(Http.head(server.baseUrl() + "/stream/nosuch/none").status()).isEqualTo(404);
        assertThat(Http.get(r.url().replaceFirst("/native$|/mp3$", "") + "/bogus-variant").status()).isIn(404, 400);
    }

    @Test
    void transcodedMp3VariantIsConstantBitrateWithWholeFrames() {
        load();
        List<Resource> mp3 = unique.values().stream().filter(Resource::transcodedMp3).toList();
        assertThat(mp3).as("transcoded MP3 resources").isNotEmpty();
        int checked = 0;
        for (Resource r : mp3) {
            if (!server.managed() && checked >= 20) {
                break;
            }
            byte[] body = Http.get(r.url()).body();
            int frame = 960; // 320 kbps, 48 kHz, MPEG-1 layer III: 144 * 320000 / 48000, no padding
            String what = r.url() + " (" + r.itemPath() + ")";
            assertThat(body.length % frame).as("size %d of %s is a whole number of frames", body.length, what)
                    .isZero();
            assertThat(body.length).isGreaterThanOrEqualTo(frame);
            if (r.res().getSize() != null) {
                assertThat((long) body.length).as("res@size of %s", what).isEqualTo(r.res().getSize());
            }
            // no ID3v2 / Xing header: the very first bytes are an audio frame
            assertThat(body[0] & 0xFF).as("frame sync of %s", what).isEqualTo(0xFF);
            for (int off = 0; off < body.length; off += frame) {
                int b1 = body[off + 1] & 0xFF;
                int b2 = body[off + 2] & 0xFF;
                assertThat(body[off] & 0xFF).as("sync byte at %d of %s", off, what).isEqualTo(0xFF);
                assertThat(b1 & 0xE0).as("sync bits at %d of %s", off, what).isEqualTo(0xE0);
                assertThat((b1 >> 3) & 3).as("MPEG-1 at %d of %s", off, what).isEqualTo(3);
                assertThat((b1 >> 1) & 3).as("layer III at %d of %s", off, what).isEqualTo(1);
                assertThat(b2 >> 4).as("bitrate index (320 kbps) at %d of %s", off, what).isEqualTo(14);
                assertThat((b2 >> 2) & 3).as("48 kHz at %d of %s", off, what).isEqualTo(1);
                assertThat((b2 >> 1) & 1).as("no padding at %d of %s", off, what).isZero();
            }
            checked++;
        }
    }

    @Test
    void transcodedResourcesDeclareDurationAndSize() {
        load();
        for (Resource r : unique.values()) {
            if (r.transcodedMp3() && !r.itemPath().endsWith("Beta Midi")) {
                assertThat(r.res().getSize()).as("res@size of the transcode of %s", r.itemPath()).isNotNull();
                assertThat(r.res().getDuration()).as("res@duration of %s", r.itemPath()).isNotBlank();
                assertThat(r.res().getSize() % 960).isZero();
            }
        }
    }

    /** Playing track N encodes track N+1 into the cache; a cached transcode serves byte-exact ranges. */
    @Test
    void prefetchedTranscodeServesByteExactRanges() throws InterruptedException {
        org.junit.jupiter.api.Assumptions.assumeTrue(server.managed(), "needs the generated library");
        Tree t = Tree.shared();
        List<Tree.Node> tracks = t.childrenOf(t.require("Folders/" + LibraryFactory.ROOT + "/Many").id());
        String playing = mp3Url(tracks.get(30));
        String next = mp3Url(tracks.get(31));
        assertThat(Http.get(playing).status()).isEqualTo(200);
        long deadline = System.currentTimeMillis() + 30_000;
        boolean exact = false;
        while (!exact && System.currentTimeMillis() < deadline) {
            byte[] full = Http.get(next).body();
            byte[] slice = Http.get(next, "Range", "bytes=480-1439").body();
            exact = Arrays.equals(slice, Arrays.copyOfRange(full, 480, 1440));
            if (!exact) {
                Thread.sleep(500);
            }
        }
        assertThat(exact).as("range of the prefetched transcode %s is byte-exact", next).isTrue();
    }

    private String mp3Url(Tree.Node n) {
        return n.resources().stream().map(r -> server.rebase(r.getValue()))
                .filter(u -> u.endsWith("/mp3")).findFirst().orElseThrow();
    }

    @Test
    void midiIsOnlyOfferedAsTranscode() {
        org.junit.jupiter.api.Assumptions.assumeTrue(server.managed(), "needs the generated library");
        load();
        List<Resource> midi = all.stream().filter(r -> r.itemPath().endsWith("Beta Midi")).toList();
        assertThat(midi).isNotEmpty().allMatch(Resource::transcodedMp3);
    }

    @Test
    void everyFormatIsOfferedAsTheDocumentationSays() {
        org.junit.jupiter.api.Assumptions.assumeTrue(server.managed(), "needs the generated library");
        load();
        // default client: MP3 original only; OGG/FLAC/WAV/AIFF/Opus: MP3 transcode first, the original second
        for (Tree.Node n : Tree.shared().items()) {
            List<Res> res = n.resources();
            String p = n.path();
            if (p.endsWith("Alpha Four") || p.endsWith("Beta Tagged")) {
                assertThat(res).as(p).hasSize(1);
                assertThat(res.get(0).getProtocolInfo().getContentFormat()).isEqualTo("audio/mpeg");
            } else if (p.endsWith("Beta Midi")) {
                assertThat(res).as(p).hasSize(1);
            } else if (p.endsWith("Alpha Two") || p.endsWith("Alpha Three") || p.endsWith("Opus Title")
                    || p.endsWith("Beta Aiff") || p.endsWith("Beta Wave")) {
                assertThat(res).as(p).hasSize(2);
                assertThat(res.get(0).getProtocolInfo().getContentFormat()).as(p + " first res").isEqualTo("audio/mpeg");
                assertThat(res.get(1).getProtocolInfo().getContentFormat()).as(p + " second res")
                        .isNotEqualTo("audio/mpeg");
            }
        }
    }
}
