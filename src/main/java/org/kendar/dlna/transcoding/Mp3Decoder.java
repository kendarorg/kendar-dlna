package org.kendar.dlna.transcoding;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * MP3 through JLayer's direct API. The exact length is {@code frames * samplesPerFrame} from a header-only walk
 * (what a decoder emits; the LAME gapless tag is ignored on purpose, a transcode only needs a self-consistent
 * count). Seeking skips headers to four frames before the target (bit reservoir warm-up) and discards.
 */
final class Mp3Decoder extends FixedLengthDecoder {

    private static final int WARM_UP_FRAMES = 4;

    /** Facts of an MP3 file gathered by a header walk. */
    record Info(int sampleRate, int samplesPerFrame, long frames) {
        long samples() {
            return frames * samplesPerFrame;
        }
    }

    private final Path file;
    private final int sampleRate;
    private final int samplesPerFrame;
    private InputStream in;
    private Bitstream bits;
    private Decoder decoder;
    private byte[] pending = new byte[0];
    private int pendingPos;
    private long discard;   // frames of output to drop after a seek
    private boolean ended;

    static Info probe(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            Bitstream bs = new Bitstream(in);
            long frames = 0;
            int rate = 0;
            int spf = 0;
            try {
                Header h;
                while ((h = bs.readFrame()) != null) {
                    if (frames == 0) {
                        rate = h.frequency();
                        spf = h.version() == Header.MPEG1 ? 1152 : 576;
                    }
                    frames++;
                    bs.closeFrame();
                }
            } catch (BitstreamException e) {
                // trailing garbage or truncation: the frames counted so far are the ones decoded
            }
            if (frames == 0) {
                throw new IOException("No MP3 frames in " + file.getFileName());
            }
            bs.close();
            return new Info(rate, spf, frames);
        } catch (BitstreamException e) {
            throw new IOException("Cannot read MP3 " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    static Mp3Decoder open(Path file) throws IOException {
        return new Mp3Decoder(file, probe(file));
    }

    private Mp3Decoder(Path file, Info info) throws IOException {
        super(info.samples());
        this.file = file;
        this.sampleRate = info.sampleRate();
        this.samplesPerFrame = info.samplesPerFrame();
        start(0);
    }

    private void start(long skipFrames) throws IOException {
        closeQuietly();
        in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        bits = new Bitstream(in);
        decoder = new Decoder();
        pending = new byte[0];
        pendingPos = 0;
        ended = false;
        try {
            for (long i = 0; i < skipFrames; i++) {
                if (bits.readFrame() == null) {
                    ended = true;
                    break;
                }
                bits.closeFrame();
            }
        } catch (BitstreamException e) {
            ended = true;
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    protected void doSeek(long frame) throws IOException {
        long targetFrame = frame / samplesPerFrame;
        long from = Math.max(0, targetFrame - WARM_UP_FRAMES);
        start(from);
        // decode (and drop) the warm-up frames and the part of the target frame before the sample
        discard = (targetFrame - from) * samplesPerFrame + frame % samplesPerFrame;
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) throws IOException {
        while (pendingPos >= pending.length) {
            if (ended || !decodeFrame()) {
                return -1;
            }
        }
        int n = Math.min(len, pending.length - pendingPos);
        System.arraycopy(pending, pendingPos, buf, off, n);
        pendingPos += n;
        return n;
    }

    private boolean decodeFrame() throws IOException {
        try {
            Header h = bits.readFrame();
            if (h == null) {
                ended = true;
                return false;
            }
            byte[] out;
            try {
                SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bits);
                short[] s = sb.getBuffer();
                int channels = sb.getChannelCount();
                int frames = sb.getBufferLength() / channels;
                out = new byte[frames * 4];
                for (int f = 0; f < frames; f++) {
                    int l = s[f * channels];
                    int r = channels > 1 ? s[f * channels + 1] : l;
                    StereoPcm.put(out, f * 4, l, r);
                }
            } catch (DecoderException | RuntimeException e) {
                out = new byte[samplesPerFrame * 4];   // undecodable frame: silence keeps the count exact
            }
            bits.closeFrame();
            if (out.length != samplesPerFrame * 4) {
                // the first frames after a mid-stream start have no main data yet and decode to nothing:
                // silence keeps every frame exactly samplesPerFrame long
                out = java.util.Arrays.copyOf(out, samplesPerFrame * 4);
            }
            if (discard > 0) {
                long drop = Math.min(discard, out.length / 4);
                discard -= drop;
                out = java.util.Arrays.copyOfRange(out, (int) drop * 4, out.length);
            }
            pending = out;
            pendingPos = 0;
            return true;
        } catch (BitstreamException e) {
            ended = true;
            return false;
        }
    }

    private void closeQuietly() {
        if (bits != null) {
            try {
                bits.close();
            } catch (BitstreamException e) {
                // ignore
            }
        }
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                // ignore
            }
        }
        bits = null;
        in = null;
    }

    @Override
    public void close() {
        closeQuietly();
    }
}
