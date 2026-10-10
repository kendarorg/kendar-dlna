package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.Format;

class StreamPlanTest {

    private static StreamPlan get(String range, long size) {
        return StreamPlan.of("GET", range, size, Format.MP3);
    }

    @Test
    void fullGetHasExactLengthAndDlnaHeaders() {
        StreamPlan p = get(null, 1000);

        assertThat(p.status()).isEqualTo(200);
        assertThat(p.start()).isZero();
        assertThat(p.length()).isEqualTo(1000);
        assertThat(p.sendBody()).isTrue();
        assertThat(p.headers()).containsEntry("Content-Length", "1000")
                .containsEntry("Content-Type", "audio/mpeg")
                .containsEntry("Accept-Ranges", "bytes")
                .containsEntry("transferMode.dlna.org", "Streaming");
        assertThat(p.headers().get("contentFeatures.dlna.org")).startsWith("DLNA.ORG_PN=MP3;DLNA.ORG_OP=01");
        assertThat(p.headers()).doesNotContainKey("Content-Range");
    }

    @Test
    void headHasHeadersButNoBody() {
        StreamPlan p = StreamPlan.of("HEAD", null, 1000, Format.FLAC);

        assertThat(p.status()).isEqualTo(200);
        assertThat(p.sendBody()).isFalse();
        assertThat(p.headers()).containsEntry("Content-Length", "1000").containsEntry("Content-Type", "audio/flac");
    }

    @Test
    void rangeVariants() {
        assertRange(get("bytes=0-99", 1000), 0, 100, "bytes 0-99/1000");
        assertRange(get("bytes=900-", 1000), 900, 100, "bytes 900-999/1000");
        assertRange(get("bytes=-100", 1000), 900, 100, "bytes 900-999/1000");
        assertRange(get("bytes=-5000", 1000), 0, 1000, "bytes 0-999/1000");
        assertRange(get("bytes=990-5000", 1000), 990, 10, "bytes 990-999/1000");
        assertRange(get("bytes=999-999", 1000), 999, 1, "bytes 999-999/1000");
        assertRange(get("BYTES=5-6", 1000), 5, 2, "bytes 5-6/1000");
    }

    private static void assertRange(StreamPlan p, long start, long len, String contentRange) {
        assertThat(p.status()).isEqualTo(206);
        assertThat(p.start()).isEqualTo(start);
        assertThat(p.length()).isEqualTo(len);
        assertThat(p.headers()).containsEntry("Content-Range", contentRange)
                .containsEntry("Content-Length", Long.toString(len));
    }

    @Test
    void headWithRangeIs206WithoutBody() {
        StreamPlan p = StreamPlan.of("HEAD", "bytes=10-19", 1000, Format.MP3);
        assertThat(p.status()).isEqualTo(206);
        assertThat(p.sendBody()).isFalse();
        assertThat(p.headers()).containsEntry("Content-Length", "10");
    }

    @Test
    void unsatisfiableRangeIs416() {
        for (String r : new String[] { "bytes=1000-", "bytes=2000-3000", "bytes=-0" }) {
            StreamPlan p = get(r, 1000);
            assertThat(p.status()).as(r).isEqualTo(416);
            assertThat(p.headers()).containsEntry("Content-Range", "bytes */1000");
            assertThat(p.sendBody()).isFalse();
        }
        assertThat(get("bytes=0-1", 0).status()).isEqualTo(416);
    }

    @Test
    void malformedOrMultiRangeFallsBackToFullEntity() {
        for (String r : new String[] { "bytes=abc", "bytes=5-2", "items=0-5", "bytes=0-1,5-6", "bytes=", "bytes=-" }) {
            StreamPlan p = get(r, 1000);
            assertThat(p.status()).as(r).isEqualTo(200);
            assertThat(p.length()).isEqualTo(1000);
        }
    }

    @Test
    void emptyFileIsServedAsEmpty200() {
        StreamPlan p = get(null, 0);
        assertThat(p.status()).isEqualTo(200);
        assertThat(p.length()).isZero();
        assertThat(p.headers()).containsEntry("Content-Length", "0");
    }

    @Test
    void otherMethodsAre405() {
        assertThat(StreamPlan.of("POST", null, 10, Format.MP3).status()).isEqualTo(405);
    }
}
