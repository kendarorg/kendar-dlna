package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.InputStream;

/**
 * The MP3 form of a source as an {@link InputStream}: decoder, optional streaming upsampler (sources below 32 kHz),
 * {@link Mp3Encoder} (CBR, 48 kHz, no reservoir, no Xing/ID3). The stream holds exactly
 * {@code frameCount(samples at 48 kHz) * frameSize - startFrame * frameSize} bytes: surplus frames are cut and
 * missing ones padded with encoder-generated silence.
 *
 * <p>A start frame k seeks the decoder to source sample {@code k * 1152 * rate / 48000} and starts a fresh encoder
 * there, so the audio is aligned within a sample or two and every frame stays independently decodable. Two streams
 * with the same arguments are byte-identical.
 */
public final class Mp3Stream extends InputStream {

    private static final int CHUNK = 32 * 1024;

    private final AudioDecoder decoder;
    private final Mp3Encoder encoder;
    private final StreamingUpsampler upsampler;
    private final int frameSize;
    private final long framesToDeliver;
    private final byte[] in = new byte[CHUNK];
    private long sourceLeft;      // source frames still to read
    private long framesOut;
    private byte[] out = new byte[0];
    private int outPos;
    private boolean sourceDone;
    private boolean finished;
    private boolean closed;
    private long skipBytes;

    /** Number of MP3 frames of the whole stream for a source of {@code samples} frames at {@code rate}. */
    public static long totalFrames(long samples, int rate) {
        return Mp3Frames.frameCount(Mp3Frames.samplesAt48k(samples, rate));
    }

    /** Exact Content-Length of the whole MP3 stream. */
    public static long length(long samples, int rate, int bitrateKbps) {
        return totalFrames(samples, rate) * Mp3Frames.frameSize(bitrateKbps);
    }

    /**
     * @param byteOffset where the stream starts within the whole MP3 (any value; frames before it are not
     *                   produced, the rest of the containing frame is skipped)
     */
    public Mp3Stream(AudioDecoder decoder, int bitrateKbps, long byteOffset) throws IOException {
        this.decoder = decoder;
        this.frameSize = Mp3Frames.frameSize(bitrateKbps);
        int rate = decoder.sampleRate();
        long total = totalFrames(decoder.totalSamples(), rate);
        long length = total * frameSize;
        long offset = Math.max(0, Math.min(byteOffset, length));
        long startFrame = offset / frameSize;
        this.skipBytes = offset % frameSize;
        this.framesToDeliver = total - startFrame;
        long startSample = startFrame * Mp3Encoder.SAMPLES_PER_FRAME * rate / Mp3Encoder.OUTPUT_RATE;
        startSample = Math.min(startSample, decoder.totalSamples());
        this.sourceLeft = decoder.totalSamples() - startSample;
        try {
            decoder.seek(startSample);
            int encRate = rate;
            if (rate < LowRateUpsampler.MIN_ENCODER_RATE) {
                upsampler = new StreamingUpsampler(rate);
                encRate = rate * upsampler.factor();
            } else {
                upsampler = null;
            }
            this.encoder = new Mp3Encoder(encRate, bitrateKbps);
        } catch (IOException | RuntimeException e) {
            decoder.close();
            throw e;
        }
    }

    private void produce() throws IOException {
        if (!sourceDone) {
            int want = (int) Math.min(CHUNK, sourceLeft * 4);
            int n = 0;
            while (n < want) {
                int r = decoder.read(in, n, want - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            n &= ~3;
            if (n < want || n == 0) {
                sourceDone = true;
            }
            sourceLeft -= n / 4;
            if (sourceLeft <= 0) {
                sourceDone = true;
            }
            if (n > 0) {
                byte[] mp3 = upsampler == null ? encoder.encode(in, 0, n)
                        : encodeUpsampled(upsampler.process(in, 0, n));
                set(mp3);
            }
            return;
        }
        if (upsampler != null) {
            byte[] tail = upsampler.flush();
            if (tail.length > 0) {
                set(encoder.encode(tail, 0, tail.length));
                if (outPos < out.length) {
                    return;
                }
            }
        }
        set(encoder.finish(framesToDeliver));
        finished = true;
    }

    private byte[] encodeUpsampled(byte[] pcm) {
        return pcm.length == 0 ? new byte[0] : encoder.encode(pcm, 0, pcm.length);
    }

    /** Queues encoder output, cutting it at the exact frame count. */
    private void set(byte[] mp3) {
        long allowed = framesToDeliver - framesOut;
        int bytes = (int) Math.min(mp3.length, allowed * frameSize);
        bytes -= bytes % frameSize;
        framesOut += bytes / frameSize;
        if (bytes == mp3.length) {
            out = mp3;
        } else {
            out = java.util.Arrays.copyOf(mp3, bytes);
        }
        outPos = 0;
        if (skipBytes > 0) {
            int s = (int) Math.min(skipBytes, out.length);
            outPos = s;
            skipBytes -= s;
        }
    }

    private boolean ensure() throws IOException {
        while (outPos >= out.length) {
            if (finished || closed) {
                return false;
            }
            produce();
        }
        return true;
    }

    @Override
    public int read() throws IOException {
        if (!ensure()) {
            return -1;
        }
        return out[outPos++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!ensure()) {
            return -1;
        }
        int n = Math.min(len, out.length - outPos);
        System.arraycopy(out, outPos, b, off, n);
        outPos += n;
        return n;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            encoder.close();
            decoder.close();
        }
    }
}
