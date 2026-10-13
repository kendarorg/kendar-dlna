package org.kendar.dlna.artwork;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.shared.RootRef;

class ArtworkServiceTest {

    @TempDir
    Path tmp;

    Path music;
    Path thumbs;
    final AtomicInteger embeddedReads = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        music = Files.createDirectories(tmp.resolve("music"));
        thumbs = tmp.resolve("thumbs");
    }

    static byte[] image(String format, int w, int h, Color c) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        BufferedImage out = img;
        if (format.equals("jpg")) {
            out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = out.createGraphics();
            g2.drawImage(img, 0, 0, null);
            g2.dispose();
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(out, format, b);
        return b.toByteArray();
    }

    Track track(String q, boolean emb) {
        return new Track(RootRef.parse(q), 5, 100, Format.MP3, TrackTags.EMPTY, 1000, 128, 44100, 2, emb);
    }

    Folder folder(String q, boolean front, boolean rear) {
        return new Folder(RootRef.parse(q), 0, DirectorySettings.DEFAULT, null, front, rear);
    }

    static Snapshot snap(List<Folder> folders, List<Track> tracks) {
        return new Snapshot(1, Instant.EPOCH, folders, tracks, List.of());
    }

    ArtworkService service(List<Folder> folders, List<Track> tracks, byte[] embeddedBytes) {
        ArtFiles files = ref -> music.resolve(ref.rel());
        EmbeddedArtSource emb = ref -> {
            embeddedReads.incrementAndGet();
            return Optional.ofNullable(embeddedBytes);
        };
        return new ArtworkService(new SnapshotHolder(snap(folders, tracks)), files, emb, thumbs);
    }

    @Test
    void itemArtPrefersFrontJpgOverEmbedded() throws Exception {
        Files.createDirectories(music.resolve("alb"));
        byte[] front = image("jpg", 400, 300, Color.RED);
        Files.write(music.resolve("alb/front.jpg"), front);
        Track withEmb = track("r/alb/a.mp3", true);
        ArtworkService svc = service(List.of(folder("r", false, false), folder("r/alb", true, false)),
                List.of(withEmb), image("png", 50, 50, Color.BLUE));

        Art art = svc.trackArt(withEmb.id(), false).orElseThrow();

        assertThat(art.data()).isEqualTo(front);
        assertThat(art.contentType()).isEqualTo("image/jpeg");
        assertThat(embeddedReads).hasValue(0);
    }

    @Test
    void itemArtFallsBackToEmbeddedAndSniffsPng() throws Exception {
        Track t = track("r/a.mp3", true);
        ArtworkService svc = service(List.of(folder("r", false, false)), List.of(t),
                image("png", 50, 50, Color.BLUE));

        Art art = svc.trackArt(t.id(), false).orElseThrow();

        assertThat(art.contentType()).isEqualTo("image/png");
        assertThat(art.etag()).startsWith("\"").endsWith("\"");
    }

    @Test
    void trackWithoutAnyArtHasNone() {
        Track t = track("r/a.mp3", false);
        List<Folder> folders = List.of(folder("r", false, false));
        ArtworkService svc = service(folders, List.of(t), null);

        assertThat(svc.trackHasArt(snap(folders, List.of(t)), t)).isFalse();
        assertThat(svc.trackArt(t.id(), false)).isEmpty();
        assertThat(svc.trackArt(t.id(), true)).isEmpty();
        assertThat(svc.trackArt("unknown", false)).isEmpty();
    }

    @Test
    void folderArtUsesFrontThenFirstEmbeddedTrackDirectThenSubfolders() throws Exception {
        Files.createDirectories(music.resolve("a"));
        Files.write(music.resolve("a/front.jpg"), image("jpg", 10, 10, Color.GREEN));
        List<Folder> folders = List.of(folder("r", false, false), folder("r/a", true, false),
                folder("r/b", false, false), folder("r/b/deep", false, false), folder("r/c", false, false));
        List<Track> tracks = List.of(track("r/b/deep/x.mp3", true), track("r/b/plain.mp3", false),
                track("r/c/y.mp3", false));
        byte[] emb = image("jpg", 20, 20, Color.BLACK);
        ArtworkService svc = service(folders, tracks, emb);
        Snapshot s = snap(folders, tracks);

        assertThat(svc.folderHasArt(s, folders.get(1))).isTrue();      // front.jpg
        assertThat(svc.folderHasArt(s, folders.get(2))).isTrue();      // embedded in a sub-folder
        assertThat(svc.folderHasArt(s, folders.get(4))).isFalse();     // nothing
        assertThat(svc.folderArt(folders.get(1).id(), false).orElseThrow().data()).isNotEqualTo(emb);
        assertThat(svc.folderArt(folders.get(2).id(), false).orElseThrow().data()).isEqualTo(emb);
        assertThat(svc.folderArt(folders.get(4).id(), false)).isEmpty();
    }


    @Test
    void otherCommonFolderArtNamesAndImageTypesAreUsedInPriorityOrder() throws Exception {
        Files.createDirectories(music.resolve("a"));
        byte[] png = image("png", 30, 30, Color.BLUE);
        byte[] jpg = image("jpg", 30, 30, Color.RED);
        Files.write(music.resolve("a/Folder.PNG"), png);
        Folder f = folder("r/a", true, false);
        ArtworkService svc = service(List.of(folder("r", false, false), f), List.of(), null);
        Art art = svc.folderArt(f.id(), false).orElseThrow();
        assertThat(art.data()).isEqualTo(png);
        assertThat(art.contentType()).isEqualTo("image/png");
        assertThat(svc.folderArt(f.id(), true).orElseThrow().contentType()).isEqualTo("image/jpeg");
        Files.write(music.resolve("a/cover.jpg"), jpg);          // cover beats folder
        assertThat(svc.folderArt(f.id(), false).orElseThrow().data()).isEqualTo(jpg);
        Files.write(music.resolve("a/front.png"), png);          // front beats cover, whatever the extension
        assertThat(svc.folderArt(f.id(), false).orElseThrow().data()).isEqualTo(png);
        Files.write(music.resolve("a/Back.jpeg"), jpg);
        Folder withRear = folder("r/a", true, true);
        ArtworkService svc2 = service(List.of(folder("r", false, false), withRear), List.of(), null);
        assertThat(svc2.rear(withRear.id()).orElseThrow().data()).isEqualTo(jpg);
    }
    @Test
    void rearJpgIsOnlyAvailableThroughRearAccessor() throws Exception {
        Files.createDirectories(music.resolve("a"));
        Files.write(music.resolve("a/rear.jpg"), image("jpg", 10, 10, Color.GRAY));
        Folder f = folder("r/a", false, true);
        Track t = track("r/a/t.mp3", false);
        List<Folder> folders = List.of(folder("r", false, false), f);
        ArtworkService svc = service(folders, List.of(t), null);
        Snapshot s = snap(folders, List.of(t));

        assertThat(svc.rear(f.id())).isPresent();
        assertThat(svc.folderHasArt(s, f)).isFalse();
        assertThat(svc.folderArt(f.id(), false)).isEmpty();
        assertThat(svc.trackHasArt(s, t)).isFalse();
        assertThat(svc.trackArt(t.id(), false)).isEmpty();
    }

    @Test
    void thumbnailIsScaledToAtMost160pxKeepingAspectAndCached() throws Exception {
        Files.createDirectories(music.resolve("a"));
        Files.write(music.resolve("a/front.jpg"), image("jpg", 640, 320, Color.ORANGE));
        Folder f = folder("r/a", true, false);
        ArtworkService svc = service(List.of(folder("r", false, false), f), List.of(), null);

        Art tn = svc.folderArt(f.id(), true).orElseThrow();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(tn.data()));

        assertThat(tn.thumbnail()).isTrue();
        assertThat(tn.contentType()).isEqualTo("image/jpeg");
        assertThat(img.getWidth()).isEqualTo(160);
        assertThat(img.getHeight()).isEqualTo(80);
        try (var files = Files.list(thumbs)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).hasSize(1)
                    .allMatch(n -> n.endsWith(".jpg"));
        }
        assertThat(svc.folderArt(f.id(), true).orElseThrow().data()).isEqualTo(tn.data());
        assertThat(svc.folderArt(f.id(), true).orElseThrow().etag()).isEqualTo(tn.etag());
    }

    @Test
    void smallImagesAreNotUpscaledAndPngAlphaIsFlattened() throws Exception {
        byte[] small = Thumbnailer.jpeg(image("png", 40, 30, new Color(0, 0, 0, 0)), 160);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(small));
        assertThat(img.getWidth()).isEqualTo(40);
        assertThat(img.getHeight()).isEqualTo(30);
        assertThat(new Color(img.getRGB(5, 5)).getRed()).isGreaterThan(240);   // transparent -> white
    }

    @Test
    void thumbnailIsRegeneratedWhenSourceChangesAndStaleFilesRemoved() throws Exception {
        Files.createDirectories(music.resolve("a"));
        Path front = music.resolve("a/front.jpg");
        Files.write(front, image("jpg", 300, 300, Color.RED));
        Folder f = folder("r/a", true, false);
        ArtworkService svc = service(List.of(folder("r", false, false), f), List.of(), null);
        Art first = svc.folderArt(f.id(), true).orElseThrow();

        Files.write(front, image("jpg", 500, 250, Color.BLUE));
        Files.setLastModifiedTime(front, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        Art second = svc.folderArt(f.id(), true).orElseThrow();

        assertThat(second.etag()).isNotEqualTo(first.etag());
        assertThat(second.data()).isNotEqualTo(first.data());
        assertThat(ImageIO.read(new ByteArrayInputStream(second.data())).getHeight()).isEqualTo(80);
        try (var files = Files.list(thumbs)) {
            assertThat(files.toList()).hasSize(1);
        }
    }

    @Test
    void corruptImagesYieldNoThumbnailWithoutThrowing() throws Exception {
        Files.createDirectories(music.resolve("a"));
        Files.writeString(music.resolve("a/front.jpg"), "definitely not a jpeg");
        Folder f = folder("r/a", true, false);
        Track t = track("r/a/t.mp3", false);
        ArtworkService svc = service(List.of(folder("r", false, false), f), List.of(t), null);

        assertThat(svc.folderArt(f.id(), true)).isEmpty();
        assertThat(svc.folderArt(f.id(), true)).isEmpty();   // second time: still fine (logged once)
        assertThat(svc.trackArt(t.id(), true)).isEmpty();
    }

    @Test
    void frontJpgListedButDeletedFallsBackToEmbedded() throws Exception {
        Track t = track("r/a.mp3", true);
        byte[] emb = image("jpg", 20, 20, Color.BLACK);
        ArtworkService svc = service(List.of(folder("r", true, false)), List.of(t), emb);

        assertThat(svc.trackArt(t.id(), false).orElseThrow().data()).isEqualTo(emb);
    }
}
