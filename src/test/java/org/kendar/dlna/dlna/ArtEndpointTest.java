package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.shared.RootRef;

class ArtEndpointTest {

    @TempDir
    Path tmp;

    ArtEndpoint endpoint;
    Folder album;
    Track track;
    byte[] jpg;

    @BeforeEach
    void setUp() throws IOException {
        Path music = Files.createDirectories(tmp.resolve("music/alb"));
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", b);
        jpg = b.toByteArray();
        Files.write(music.resolve("front.jpg"), jpg);
        album = new Folder(RootRef.parse("r/alb"), 0, DirectorySettings.DEFAULT, null, true, false);
        Folder root = new Folder(RootRef.parse("r"), 0, DirectorySettings.DEFAULT, null, false, false);
        track = new Track(RootRef.parse("r/alb/a.mp3"), 1, 10, Format.MP3, TrackTags.EMPTY, 1000, 128, 44100, 2,
                false);
        Snapshot s = new Snapshot(1, Instant.EPOCH, List.of(root, album), List.of(track), List.of());
        ArtworkService svc = new ArtworkService(new SnapshotHolder(s), ref -> tmp.resolve("music").resolve(ref.rel()),
                ref -> Optional.empty(), tmp.resolve("thumbs"));
        endpoint = new ArtEndpoint(svc);
    }

    @Test
    void servesOriginalWithCachingAndDlnaHeaders() {
        ArtEndpoint.Response r = endpoint.handle("GET", "/f/" + album.id(), null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(jpg);
        assertThat(r.headers()).containsEntry("Content-Type", "image/jpeg")
                .containsEntry("Content-Length", Integer.toString(jpg.length))
                .containsEntry("Cache-Control", ArtEndpoint.CACHE_CONTROL)
                .containsEntry("transferMode.dlna.org", "Interactive")
                .containsKeys("ETag", "Last-Modified");
        assertThat(r.headers().get("contentFeatures.dlna.org")).doesNotContain("JPEG_TN").contains("DLNA.ORG_OP=00");
    }

    @Test
    void thumbnailCarriesJpegTnProfile() throws Exception {
        ArtEndpoint.Response r = endpoint.handle("GET", "/t/" + track.id() + "/tn", null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.headers().get("contentFeatures.dlna.org")).startsWith("DLNA.ORG_PN=JPEG_TN;");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(r.body())).getWidth()).isEqualTo(160);
        assertThat(r.headers()).containsEntry("Content-Length", Integer.toString(r.body().length));
    }

    @Test
    void headHasLengthButNoBody() {
        ArtEndpoint.Response r = endpoint.handle("HEAD", "/f/" + album.id() + "/tn", null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isNull();
        assertThat(r.headers()).containsKey("Content-Length");
    }

    @Test
    void etagRevalidationYields304() {
        String etag = endpoint.handle("GET", "/f/" + album.id(), null).headers().get("ETag");

        ArtEndpoint.Response r = endpoint.handle("GET", "/f/" + album.id(), "\"other\", " + etag);

        assertThat(r.status()).isEqualTo(304);
        assertThat(r.body()).isNull();
        assertThat(r.headers()).containsEntry("ETag", etag).doesNotContainKey("Content-Type");
        assertThat(endpoint.handle("GET", "/f/" + album.id(), "\"other\"").status()).isEqualTo(200);
    }

    @Test
    void badRequestsAre404Or405() {
        assertThat(endpoint.handle("GET", "/f/unknown", null).status()).isEqualTo(404);
        assertThat(endpoint.handle("GET", "/x/" + album.id(), null).status()).isEqualTo(404);
        assertThat(endpoint.handle("GET", "/f/" + album.id() + "/big", null).status()).isEqualTo(404);
        assertThat(endpoint.handle("GET", "/f", null).status()).isEqualTo(404);
        assertThat(endpoint.handle("GET", "/f/a/tn/extra", null).status()).isEqualTo(404);
        assertThat(endpoint.handle("GET", null, null).status()).isEqualTo(404);
        ArtEndpoint.Response post = endpoint.handle("POST", "/f/" + album.id(), null);
        assertThat(post.status()).isEqualTo(405);
        assertThat(post.headers()).containsEntry("Allow", "GET, HEAD");
    }

    @Test
    void pathBuilders() {
        assertThat(ArtEndpoint.trackPath("abc", true)).isEqualTo("/art/t/abc/tn");
        assertThat(ArtEndpoint.folderPath("abc", false)).isEqualTo("/art/f/abc");
    }
}
