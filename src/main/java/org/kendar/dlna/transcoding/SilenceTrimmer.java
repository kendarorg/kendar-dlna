package org.kendar.dlna.transcoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Tail-silence handling for rendered MIDI. The synthesizer stream is endless, so rendering continues past the last
 * MIDI event until the output has stayed at or below {@code threshold} for {@code holdFrames}, but never longer than
 * {@code maxTailFrames} beyond the event end. The result is trimmed to the last audible frame plus
 * {@code keepFrames} (a short natural fade margin). Leading silence is kept.
 */
public final class SilenceTrimmer {

    /** @param threshold absolute 16-bit amplitude regarded as silence (16 is about -66 dBFS) */
    public record Policy(int threshold, int holdFrames, int maxTailFrames, int keepFrames) {
        public static Policy forRate(int rate) {
            return new Policy(16, rate / 2, rate * 10, rate / 20);
        }
    }

    private SilenceTrimmer() {
    }

    /**
     * Reads interleaved s16le PCM from {@code pcm} and returns the trimmed bytes.
     *
     * @param eventEndFrame frame position of the last MIDI event; reading never stops before it and the tail cap
     *                      counts from there
     */
    public static byte[] readTrimmed(InputStream pcm, int channels, long eventEndFrame, Policy p) throws IOException {
        int frameBytes = channels * 2;
        ByteArrayOutputStream all = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[frameBytes * 4096];
        long frames = 0;
        long lastAudible = -1;
        long silentRun = 0;
        while (true) {
            if (frames >= eventEndFrame && (silentRun >= p.holdFrames() || frames - eventEndFrame >= p.maxTailFrames())) {
                break;
            }
            int want = buf.length;
            if (frames < eventEndFrame) {
                want = (int) Math.min(want, (eventEndFrame - frames) * frameBytes);
            }
            int n = pcm.readNBytes(buf, 0, want);
            n -= n % frameBytes;
            if (n <= 0) {
                break;
            }
            for (int f = 0; f < n / frameBytes; f++) {
                boolean audible = false;
                for (int c = 0; c < channels; c++) {
                    int i = f * frameBytes + c * 2;
                    int s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                    if (Math.abs(s) > p.threshold()) {
                        audible = true;
                    }
                }
                if (audible) {
                    lastAudible = frames + f;
                    silentRun = 0;
                } else {
                    silentRun++;
                }
            }
            all.write(buf, 0, n);
            frames += n / frameBytes;
            if (n < want) {
                break;
            }
        }
        long keep = lastAudible < 0 ? 0 : Math.min(frames, lastAudible + 1 + p.keepFrames());
        return Arrays.copyOf(all.toByteArray(), (int) (keep * frameBytes));
    }
}
