package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.testutil.AudioFiles;

/** A sample range of a decoder is a decoder: exact length, sample exact content, seek, padding. */
class SegmentDecoderTest {

    @TempDir
    Path tmp;

    @Test
    void deliversExactlyTheRangeFromEveryPosition() throws IOException {
        Path wav = AudioFiles.sineWav(tmp.resolve("a.wav"), 44100, 2, 20000, 440);
        byte[] whole;
        try (AudioDecoder full = Decoders.open(SourceFormat.WAV, wav)) {
            whole = DecodersTest.readAll(full);
        }
        try (AudioDecoder d = new SegmentDecoder(Decoders.open(SourceFormat.WAV, wav), 3000, 8001)) {
            assertThat(d.totalSamples()).isEqualTo(5001);
            assertThat(d.sampleRate()).isEqualTo(44100);
            byte[] all = DecodersTest.readAll(d);
            assertThat(all).isEqualTo(Arrays.copyOfRange(whole, 3000 * 4, 8001 * 4));
            d.seek(1234);
            assertThat(DecodersTest.readAll(d)).isEqualTo(Arrays.copyOfRange(whole, 4234 * 4, 8001 * 4));
            d.seek(5001);
            assertThat(d.read(new byte[100], 0, 100)).isEqualTo(-1);
        }
    }

    @Test
    void padsWithSilenceWhenTheSourceEndsBeforeTheRangeDoes() throws IOException {
        Path wav = AudioFiles.sineWav(tmp.resolve("b.wav"), 44100, 2, 1000, 440);
        try (AudioDecoder d = new SegmentDecoder(Decoders.open(SourceFormat.WAV, wav), 900, 1200)) {
            byte[] all = DecodersTest.readAll(d);
            assertThat(all).hasSize(300 * 4);
            assertThat(Arrays.copyOfRange(all, 100 * 4, all.length)).containsOnly((byte) 0);
        }
    }
}
