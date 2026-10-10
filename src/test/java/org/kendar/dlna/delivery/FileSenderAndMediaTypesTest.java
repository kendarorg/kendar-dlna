package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;

class FileSenderAndMediaTypesTest {

    @TempDir
    Path dir;

    @Test
    void copiesExactWindow() throws IOException {
        Path f = dir.resolve("f.bin");
        Files.write(f, new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 });
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        FileSender.copy(f, 3, 4, out);

        assertThat(out.toByteArray()).containsExactly(3, 4, 5, 6);
    }

    @Test
    void failsWhenFileIsShorterThanPlanned() throws IOException {
        Path f = dir.resolve("short.bin");
        Files.write(f, new byte[5]);
        assertThatThrownBy(() -> FileSender.copy(f, 0, 10, new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void protocolInfoPerFormat() {
        assertThat(MediaTypes.protocolInfo(Format.MP3))
                .isEqualTo("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_FLAGS="
                        + MediaTypes.DLNA_FLAGS);
        assertThat(MediaTypes.protocolInfo(Format.FLAC)).startsWith("http-get:*:audio/flac:DLNA.ORG_OP=01");
        assertThat(MediaTypes.mimeType(Format.OGG)).isEqualTo("audio/ogg");
        assertThat(MediaTypes.mimeType(Format.OPUS)).isEqualTo("audio/ogg");
        assertThat(MediaTypes.mimeType(Format.WAV)).isEqualTo("audio/wav");
        assertThat(MediaTypes.mimeType(Format.AIFF)).isEqualTo("audio/aiff");
        assertThat(MediaTypes.DLNA_FLAGS).hasSize(32);
    }

    @Test
    void aacAndAlacAreServedAsMp4AudioAndRawAacAsAdtsWithoutAProfileName() {
        assertThat(MediaTypes.mimeType(Format.AAC)).isEqualTo("audio/mp4");
        assertThat(MediaTypes.mimeType(Format.ALAC)).isEqualTo("audio/mp4");
        assertThat(MediaTypes.mimeType(Format.ADTS)).isEqualTo("audio/aac");
        for (Format f : new Format[] {Format.AAC, Format.ALAC, Format.ADTS}) {
            assertThat(MediaTypes.contentFeatures(f)).doesNotContain("DLNA.ORG_PN")
                    .isEqualTo("DLNA.ORG_OP=01;DLNA.ORG_FLAGS=" + MediaTypes.DLNA_FLAGS);
            assertThat(MediaTypes.protocolInfo(f)).startsWith("http-get:*:" + MediaTypes.mimeType(f) + ":");
        }
    }

    @Test
    void urlBuilder() {
        assertThat(new DefaultStreamUrlBuilder().url("http://h:1", "c1", "i1")).isEqualTo("http://h:1/stream/c1/i1");
    }
}
