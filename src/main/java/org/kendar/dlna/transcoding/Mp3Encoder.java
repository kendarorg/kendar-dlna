package org.kendar.dlna.transcoding;

import de.sciss.jump3r.lowlevel.LameEncoder;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import javax.sound.sampled.AudioFormat;

/**
 * Spike seed of the MP3 encoder: CBR at a configurable bitrate, always 48 kHz out, bit reservoir off,
 * no Info/Xing frame, no ID3, no ReplayGain. Input is signed 16-bit little-endian stereo PCM at the source rate;
 * the LAME resampler converts to 48 kHz. Not thread-safe; one instance per stream.
 *
 * <p>jump3r's {@code LameEncoder} offers no switch for the reservoir or the Xing frame, so after construction the
 * LAME flags (public fields of {@link LameGlobalFlags}) are rebuilt through reflection on two package-private fields.
 */
public final class Mp3Encoder implements AutoCloseable {

    public static final int OUTPUT_RATE = 48_000;
    public static final int SAMPLES_PER_FRAME = 1152;
    public static final int CHANNELS = 2;

    /** Encodes a whole s16le stereo buffer to exactly the advertised number of frames (see {@link Mp3Frames}). */
    public static byte[] encodeExact(byte[] pcm, int sourceRate, int bitrateKbps) {
        if (sourceRate < LowRateUpsampler.MIN_ENCODER_RATE) {
            return encodeExact(LowRateUpsampler.upsample(pcm, sourceRate), sourceRate * LowRateUpsampler.factor(sourceRate),
                    bitrateKbps);
        }
        long target = Mp3Frames.frameCount(Mp3Frames.samplesAt48k(pcm.length / (CHANNELS * 2), sourceRate));
        try (Mp3Encoder enc = new Mp3Encoder(sourceRate, bitrateKbps)) {
            byte[] body = enc.encode(pcm, 0, pcm.length);
            int size = enc.frameSize();
            byte[] head = body.length > target * size ? java.util.Arrays.copyOf(body, (int) (target * size)) : body;
            byte[] tail = enc.finish(target);
            byte[] all = java.util.Arrays.copyOf(head, head.length + tail.length);
            System.arraycopy(tail, 0, all, head.length, tail.length);
            return all;
        }
    }

    private final LameEncoder encoder;
    private final int bitrateKbps;
    private final int sourceRate;
    private final int frameSize;
    private final byte[] outBuf;
    private long framesEmitted;
    private boolean closed;

    public Mp3Encoder(int sourceRate, int bitrateKbps) {
        this(sourceRate, bitrateKbps, true);
    }

    /** @param reservoirOff false only to demonstrate (in tests) why the reservoir must be disabled */
    public Mp3Encoder(int sourceRate, int bitrateKbps, boolean reservoirOff) {
        if (sourceRate < LowRateUpsampler.MIN_ENCODER_RATE) {
            throw new IllegalArgumentException("LAME lowers the CBR bitrate below 32 kHz input; upsample first: " + sourceRate);
        }
        this.sourceRate = sourceRate;
        this.bitrateKbps = bitrateKbps;
        this.frameSize = Mp3Frames.frameSize(bitrateKbps);
        AudioFormat src = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sourceRate, 16, CHANNELS, CHANNELS * 2,
                sourceRate, false);
        AudioFormat dst = new AudioFormat(LameEncoder.MPEG1L3, OUTPUT_RATE, -1, CHANNELS, -1, -1, false,
                Map.of(LameEncoder.P_BITRATE, bitrateKbps, LameEncoder.P_VBR, false,
                        LameEncoder.P_QUALITY, LameEncoder.QUALITY_MIDDLE));
        this.encoder = new LameEncoder(src, dst);
        reconfigure(encoder, reservoirOff);
        this.outBuf = new byte[encoder.getMP3BufferSize() + 16 * 1024];
    }

    private static void reconfigure(LameEncoder enc, boolean reservoirOff) {
        try {
            Field fLame = LameEncoder.class.getDeclaredField("lame");
            Field fGfp = LameEncoder.class.getDeclaredField("gfp");
            fLame.setAccessible(true);
            fGfp.setAccessible(true);
            Lame lame = (Lame) fLame.get(enc);
            LameGlobalFlags old = (LameGlobalFlags) fGfp.get(enc);
            LameGlobalFlags gfp = lame.lame_init();
            gfp.num_channels = old.num_channels;
            gfp.in_samplerate = old.in_samplerate;
            gfp.out_samplerate = old.out_samplerate;
            gfp.mode = old.mode;
            gfp.VBR = old.VBR;
            gfp.VBR_q = old.VBR_q;
            gfp.brate = old.brate;
            gfp.quality = old.quality;
            gfp.write_id3tag_automatic = false;
            gfp.findReplayGain = false;
            gfp.bWriteVbrTag = false;
            gfp.disable_reservoir = reservoirOff;
            gfp.free_format = false;
            lame.lame_init_params(gfp);
            lame.lame_close(old);
            fGfp.set(enc, gfp);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("jump3r internals changed; cannot configure LAME flags", e);
        }
    }

    public int bitrateKbps() {
        return bitrateKbps;
    }

    public int frameSize() {
        return frameSize;
    }

    public int effectiveBitrateKbps() {
        return encoder.getEffectiveBitRate();
    }

    public int effectiveSampleRate() {
        return encoder.getEffectiveSampleRate();
    }

    public long framesEmitted() {
        return framesEmitted;
    }

    /** Encodes interleaved s16le stereo PCM at the source rate; returns the MP3 bytes completed by this call. */
    public byte[] encode(byte[] pcm, int off, int len) {
        int pos = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(len / 4 + 1024);
        int chunk = encoder.getPCMBufferSize();
        while (pos < len) {
            int n = Math.min(chunk, len - pos);
            n -= n % (CHANNELS * 2);
            if (n <= 0) {
                break;
            }
            int produced = encoder.encodeBuffer(pcm, off + pos, n, outBuf);
            out.write(outBuf, 0, produced);
            pos += n;
        }
        byte[] result = out.toByteArray();
        framesEmitted += result.length / frameSize;
        return result;
    }

    /**
     * Completes the stream with exactly {@code targetFrames} frames in total (counting frames already returned by
     * {@link #encode}): pads by encoding silence, or (when already at or past the target) emits nothing more.
     * Truncation of surplus frames is the caller's job because {@link #encode} has already returned them.
     * Returns the remaining bytes.
     */
    public byte[] finish(long targetFrames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] silence = new byte[(int) ((long) SAMPLES_PER_FRAME * sourceRate / OUTPUT_RATE + 1) * CHANNELS * 2];
        long emitted = framesEmitted;
        int guard = 0;
        while (emitted < targetFrames && guard++ < 100_000) {
            int produced = encoder.encodeBuffer(silence, 0, silence.length, outBuf);
            long frames = produced / frameSize;
            long take = Math.min(frames, targetFrames - emitted);
            out.write(outBuf, 0, (int) (take * frameSize));
            emitted += take;
        }
        framesEmitted = emitted;
        return out.toByteArray();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            encoder.close();
        }
    }
}
