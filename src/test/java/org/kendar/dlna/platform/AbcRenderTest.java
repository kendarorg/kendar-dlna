package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.ingestion.AbcFile;
import org.kendar.dlna.transcoding.MidiRenderer;
import org.kendar.dlna.transcoding.Transcoder;

class AbcRenderTest {

    @TempDir
    Path tmp;

    @Test
    void aTuneOfAnAbcFileIsRenderedToAudio() throws Exception {
        assumeTrue(MidiRenderer.isAvailable(), "needs --add-exports java.desktop/com.sun.media.sound");
        Path file = Files.writeString(tmp.resolve("book.abc"),
                "X:1\nT:One\nL:1/4\nQ:1/4=120\nK:C\nCDEF|\n\nX:2\nT:Two\nL:1/4\nQ:1/4=120\nK:G\nGABcdefg|\n");
        Transcoder t = new Transcoder(new MidiRenderer(null), (f, n) -> AbcFile.parse(f).get(n - 1).sequence());
        Transcoder.Rendered one = t.renderAbc(file, 1);
        Transcoder.Rendered two = t.renderAbc(file, 2);
        assertThat(one.sampleRate()).isEqualTo(MidiRenderer.RATE);
        assertThat(one.frames()).isGreaterThan(MidiRenderer.RATE * 3L / 2);
        assertThat(two.frames()).isGreaterThan(one.frames());
    }
}
