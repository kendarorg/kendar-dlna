package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Apple Lossless in MP4/M4A: demux by {@link Mp4Audio}, decode by {@link AlacFrames}. Frames are independent, so a
 * seek decodes only the frame holding the target. 24-bit is reduced to 16-bit with rounding, mono is duplicated.
 * The exact length is the stts total (or the gapless window when the file has an edit list or iTunSMPB).
 */
final class AlacDecoder extends FixedLengthDecoder {

    private final Mp4Audio src;
    private final RandomAccessFile raf;
    private final AlacFrames frames;
    private final int[][] samples;
    private int nextUnit;
    private long discard;
    private byte[] pending = new byte[0];
    private int pendingPos;

    static AlacDecoder open(Path file) throws IOException {
        Mp4Audio m = Mp4Audio.open(file);
        if (m.codec != Mp4Audio.Codec.ALAC) {
            throw new IOException("not an ALAC file: " + file.getFileName());
        }
        if (m.length <= 0 || m.count() == 0) {
            throw new IOException("ALAC stream without audio: " + file.getFileName());
        }
        AlacFrames f = new AlacFrames(m.config);
        AlacDecoder d = new AlacDecoder(m, f);
        d.startAt(0);
        return d;
    }

    private AlacDecoder(Mp4Audio src, AlacFrames frames) throws IOException {
        super(src.length);
        this.src = src;
        this.frames = frames;
        this.raf = new RandomAccessFile(src.file.toFile(), "r");
        this.samples = new int[2][frames.frameLength()];
    }

    @Override
    public int sampleRate() {
        return src.sampleRate;
    }

    private void startAt(long timelinePos) {
        long t = timelinePos + src.skip;
        int idx = Arrays.binarySearch(src.starts, t);
        int unit = idx >= 0 ? idx : -idx - 2;
        unit = Math.max(0, Math.min(unit, src.count()));
        nextUnit = unit;
        discard = t - src.starts[unit];
        pending = new byte[0];
        pendingPos = 0;
    }

    @Override
    protected void doSeek(long frame) {
        startAt(frame);
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) throws IOException {
        while (pendingPos >= pending.length) {
            if (nextUnit >= src.count()) {
                return -1;
            }
            int n = frames.decode(src.read(raf, nextUnit++), samples);
            int skipFrames = (int) Math.min(discard, n);
            discard -= skipFrames;
            int bits = frames.bitDepth();
            byte[] out = new byte[(n - skipFrames) * 4];
            for (int i = skipFrames; i < n; i++) {
                StereoPcm.put(out, (i - skipFrames) * 4, StereoPcm.to16(samples[0][i], bits),
                        StereoPcm.to16(samples[frames.channels() > 1 ? 1 : 0][i], bits));
            }
            pending = out;
            pendingPos = 0;
        }
        int n = Math.min(len, pending.length - pendingPos);
        System.arraycopy(pending, pendingPos, buf, off, n);
        pendingPos += n;
        return n;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
