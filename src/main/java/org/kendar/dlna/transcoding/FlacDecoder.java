package org.kendar.dlna.transcoding;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

/**
 * FLAC through JFLAC. The exact length is {@code StreamInfo.totalSamples}. 8, 16 and 24-bit streams are supported
 * (24-bit is reduced to 16-bit with rounding); mono is duplicated, extra channels are dropped. Seeking decodes and
 * discards from the start of the stream (a 5-minute track takes well under a second).
 */
final class FlacDecoder extends FixedLengthDecoder {

    private final Path file;
    private final int sampleRate;
    private final int channels;
    private final int bits;
    private final boolean wide;
    private InputStream in;
    private FLACDecoder decoder;
    private final ByteData frameData = new ByteData(1 << 16);
    private byte[] pending = new byte[0];
    private int pendingPos;
    private long discard;
    private boolean ended;

    static FlacDecoder open(Path file) throws IOException {
        return open(file, false);
    }

    /** @param wide deliver stereo s24le (6-byte frames) instead of s16le */
    static FlacDecoder open(Path file, boolean wide) throws IOException {
        StreamInfo info;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            FLACDecoder d = new FLACDecoder(in);
            info = d.readStreamInfo();
        }
        if (info.getTotalSamples() <= 0) {
            throw new IOException("FLAC stream without a known length: " + file.getFileName());
        }
        int bps = info.getBitsPerSample();
        if (bps != 8 && bps != 16 && bps != 24) {
            throw new IOException("Unsupported FLAC bit depth " + bps);
        }
        return new FlacDecoder(file, info, wide);
    }

    private FlacDecoder(Path file, StreamInfo info, boolean wide) throws IOException {
        super(info.getTotalSamples(), wide ? 6 : 4);
        this.wide = wide;
        this.file = file;
        this.sampleRate = info.getSampleRate();
        this.channels = info.getChannels();
        this.bits = info.getBitsPerSample();
        start();
    }

    private void start() throws IOException {
        closeQuietly();
        in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        decoder = new FLACDecoder(in);
        decoder.readMetadata();
        pending = new byte[0];
        pendingPos = 0;
        ended = false;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    protected void doSeek(long frame) throws IOException {
        start();
        discard = frame;
        // whole frames before the target are decoded and dropped by the read loop
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) throws IOException {
        while (pendingPos >= pending.length) {
            if (ended || !nextFrame()) {
                return -1;
            }
        }
        int n = Math.min(len, pending.length - pendingPos);
        System.arraycopy(pending, pendingPos, buf, off, n);
        pendingPos += n;
        return n;
    }

    private boolean nextFrame() throws IOException {
        Frame f = decoder.readNextFrame();
        if (f == null) {
            ended = true;
            return false;
        }
        int block = f.header.blockSize;
        if (discard >= block) {
            discard -= block;
            pending = new byte[0];
            pendingPos = 0;
            return true;
        }
        ByteData data = decoder.decodeFrame(f, frameData);
        byte[] raw = data.getData();
        int bytesPerSample = (bits + 7) / 8;
        if (bits == 8) {
            for (int i = 0; i < block * channels; i++) {
                raw[i] ^= (byte) 0x80;   // JFLAC emits unsigned 8-bit
            }
        }
        int fb = frameBytes();
        byte[] out = new byte[block * fb];
        if (wide) {
            StereoPcm.toStereo24(raw, 0, block, channels, bytesPerSample, bits, out, 0);
        } else {
            StereoPcm.toStereo16(raw, 0, block, channels, bytesPerSample, bits, out, 0);
        }
        int skip = (int) Math.min(discard, block);
        discard -= skip;
        pending = skip == 0 ? out : java.util.Arrays.copyOfRange(out, skip * fb, out.length);
        pendingPos = 0;
        return true;
    }

    private void closeQuietly() {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                // ignore
            }
        }
        in = null;
    }

    @Override
    public void close() {
        closeQuietly();
    }
}
