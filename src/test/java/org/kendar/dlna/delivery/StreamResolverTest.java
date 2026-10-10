package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;

class StreamResolverTest {

    @TempDir
    Path dir;

    final List<RootRef> missing = new ArrayList<>();
    Track present;
    Track gone;
    StreamResolver resolver;
    byte[] content;

    @BeforeEach
    void setUp() throws IOException {
        content = new byte[200_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        Files.write(dir.resolve("song.mp3"), content);
        present = track("m/song.mp3");
        gone = track("m/gone.mp3");
        Snapshot snap = snapshot(List.of(folder("m")), List.of(present, gone));
        resolver = new StreamResolver(() -> snap, ref -> ref.root().equals("m") ? dir.resolve(ref.rel()) : null,
                missing::add);
    }

    private String path(Track t) {
        return "/" + org.kendar.dlna.catalog.Folder.idOf(t.parentRef()) + "/" + t.id();
    }

    @Test
    void servesFullFileAndExactRange() throws IOException {
        StreamResolver.Resolved full = resolver.resolve("GET", path(present), null);
        assertThat(full.plan().status()).isEqualTo(200);
        assertThat(send(full)).isEqualTo(content);

        StreamResolver.Resolved part = resolver.resolve("GET", path(present), "bytes=70000-70999");
        assertThat(part.plan().status()).isEqualTo(206);
        byte[] expected = java.util.Arrays.copyOfRange(content, 70000, 71000);
        assertThat(send(part)).isEqualTo(expected);

        StreamResolver.Resolved tail = resolver.resolve("GET", path(present), "bytes=-10");
        assertThat(send(tail)).isEqualTo(java.util.Arrays.copyOfRange(content, content.length - 10, content.length));
    }

    @Test
    void headHasNoFileToSend() {
        StreamResolver.Resolved r = resolver.resolve("HEAD", path(present), null);
        assertThat(r.plan().status()).isEqualTo(200);
        assertThat(r.plan().headers()).containsEntry("Content-Length", Integer.toString(content.length));
        assertThat(r.file()).isNull();
    }

    @Test
    void unknownIdsAndBadPathsAre404() {
        assertThat(resolver.resolve("GET", "/x/unknown", null).plan().status()).isEqualTo(404);
        assertThat(resolver.resolve("GET", "/onlyone", null).plan().status()).isEqualTo(404);
        assertThat(resolver.resolve("GET", "/", null).plan().status()).isEqualTo(404);
        assertThat(resolver.resolve("GET", path(present) + "/mp3", null).plan().status()).isEqualTo(404);
        assertThat(resolver.resolve("GET", path(present) + "/native", null).plan().status()).isEqualTo(200);
        assertThat(resolver.resolve("GET", null, null).plan().status()).isEqualTo(404);
        assertThat(missing).isEmpty();
    }

    @Test
    void missingFileIs404AndReported() {
        StreamResolver.Resolved r = resolver.resolve("GET", path(gone), null);
        assertThat(r.plan().status()).isEqualTo(404);
        assertThat(missing).containsExactly(gone.ref());
    }

    @Test
    void unsatisfiableRangeIs416WithoutFile() {
        StreamResolver.Resolved r = resolver.resolve("GET", path(present), "bytes=999999-");
        assertThat(r.plan().status()).isEqualTo(416);
        assertThat(r.file()).isNull();
    }

    private static byte[] send(StreamResolver.Resolved r) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileSender.copy(r.file(), r.plan().start(), r.plan().length(), out);
        assertThat(out.size()).isEqualTo(r.plan().length());
        return out.toByteArray();
    }
}
