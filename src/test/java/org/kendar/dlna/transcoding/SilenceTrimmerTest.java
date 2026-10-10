package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class SilenceTrimmerTest {

    private static final int RATE = 48000;
    private static final SilenceTrimmer.Policy POLICY = new SilenceTrimmer.Policy(16, RATE / 2, RATE * 10, RATE / 20);

    /** Endless stream: samples come from the generator; a negative frame count means "never ends". */
    private static InputStream stream(java.util.function.IntUnaryOperator gen, long frames) {
        return new InputStream() {
            long pos;
            int half;

            @Override
            public int read() {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                int n = 0;
                while (n + 4 <= len && (frames < 0 || pos < frames)) {
                    int s = gen.applyAsInt((int) pos);
                    b[off + n] = (byte) s;
                    b[off + n + 1] = (byte) (s >> 8);
                    b[off + n + 2] = (byte) s;
                    b[off + n + 3] = (byte) (s >> 8);
                    n += 4;
                    pos++;
                }
                return n == 0 ? -1 : n;
            }
        };
    }

    @Test
    void trimsSilenceThatFollowsTheLastAudibleSample() throws IOException {
        // 1 s tone, endless silence after; last MIDI event at 0.8 s
        byte[] out = SilenceTrimmer.readTrimmed(stream(i -> i < RATE ? 8000 : 0, -1), 2, RATE * 8 / 10, POLICY);
        assertThat(out.length / 4).isEqualTo(RATE + RATE / 20);
    }

    @Test
    void decayingTailIsKeptUntilItFallsBelowTheThreshold() throws IOException {
        // exponential release from 0.5 s: amplitude 20000 * 0.9999^n, below 16 after ~ 71_900 samples
        byte[] out = SilenceTrimmer.readTrimmed(
                stream(i -> i < RATE / 2 ? 20000 : (int) (20000 * Math.pow(0.9999, i - RATE / 2)), -1), 2, RATE / 2,
                POLICY);
        int lastAudible = RATE / 2 + (int) Math.ceil(Math.log(16.0 / 20000) / Math.log(0.9999));
        assertThat(out.length / 4).isBetween(lastAudible - 2, lastAudible + RATE / 20 + 2);
    }

    @Test
    void neverEndingToneIsCappedAtEventEndPlusMaxTail() throws IOException {
        byte[] out = SilenceTrimmer.readTrimmed(stream(i -> i % 2 == 0 ? 9000 : -9000, -1), 2, RATE, POLICY);
        // audible to the very end of the capped render; read stops at the first block boundary past the cap
        assertThat(out.length / 4).isBetween(RATE + RATE * 10, RATE + RATE * 10 + 4096);
    }

    @Test
    void allSilenceTrimsToNothing() throws IOException {
        byte[] out = SilenceTrimmer.readTrimmed(stream(i -> 0, -1), 2, RATE, POLICY);
        assertThat(out).isEmpty();
    }

    @Test
    void shortFiniteStreamIsReturnedWithoutTailWaiting() throws IOException {
        byte[] out = SilenceTrimmer.readTrimmed(stream(i -> 5000, 1000), 2, RATE, POLICY);
        assertThat(out.length / 4).isEqualTo(1000);
    }

    @Test
    void leadingSilenceIsKeptAndSmallNoiseBelowThresholdCountsAsSilence() throws IOException {
        byte[] out = SilenceTrimmer.readTrimmed(
                stream(i -> i < 4800 ? 0 : i < 9600 ? 7000 : (i % 2 == 0 ? 10 : -10), -1), 2, 9600, POLICY);
        assertThat(out.length / 4).isEqualTo(9600 + RATE / 20);
    }

    @Test
    void emptyInputGivesEmptyOutput() throws IOException {
        assertThat(SilenceTrimmer.readTrimmed(new ByteArrayInputStream(new byte[0]), 2, 0, POLICY)).isEmpty();
    }
}
