package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;
import net.sourceforge.jaad.aac.AACException;
import net.sourceforge.jaad.aac.Decoder;

/**
 * AAC-LC from MP4/M4A or raw ADTS through JAAD. The exact length comes from {@link Mp4Audio} (edit list or
 * iTunSMPB gapless window, else every decoded sample). 1 to 7 channel configurations are folded to stereo. HE-AAC
 * (SBR, PS) is refused up front (JAAD's SBR decoder fails on real streams). Seeking restarts the decoder two access units (2048 samples) before the target:
 * AAC-LC only depends on the previous frame through the overlap-add, so the result equals the continuous decode.
 */
final class AacDecoder extends FixedLengthDecoder {

    private static final int FRAME = 1024;
    private static final int PREROLL = 2;

    private final Mp4Audio src;
    private final RandomAccessFile raf;
    private Decoder decoder;
    private int nextUnit;
    private long discard;
    private byte[] pending = new byte[0];
    private int pendingPos;
    private final float[] mix;

    static AacDecoder open(Path file) throws IOException {
        Mp4Audio m = Mp4Audio.openAny(file);
        if (m.codec != Mp4Audio.Codec.AAC) {
            throw new IOException("not an AAC file: " + file.getFileName());
        }
        if (m.length <= 0 || m.count() == 0) {
            throw new IOException("AAC stream without audio: " + file.getFileName());
        }
        AacDecoder d = new AacDecoder(m);
        d.verifyNoSbr();
        d.startAt(0);
        return d;
    }

    private AacDecoder(Mp4Audio src) throws IOException {
        super(src.length);
        this.src = src;
        this.raf = new RandomAccessFile(src.file.toFile(), "r");
        this.mix = mixMatrix(src.channelConfig);
    }

    @Override
    public int sampleRate() {
        return src.sampleRate;
    }

    /** Decodes the first access units to prove the stream is plain AAC-LC (SBR doubles the output frame). */
    private void verifyNoSbr() throws IOException {
        Decoder d = newDecoder();
        int probe = Math.min(4, src.count());
        for (int i = 0; i < probe; i++) {
            decodeUnit(d, i);
        }
    }

    private Decoder newDecoder() throws IOException {
        try {
            return Decoder.create(src.config);
        } catch (RuntimeException e) {
            throw new IOException("Cannot initialise the AAC decoder: " + e.getMessage(), e);
        }
    }

    private float[][] lastChannels;
    private int lastLength;

    private void decodeUnit(Decoder d, int unit) throws IOException {
        byte[] au = src.read(raf, unit);
        try {
            d.decodeFrame(au, (List<float[]> channels, int length, int rate) -> {
                lastChannels = channels.toArray(new float[0][]);
                lastLength = length;
            });
        } catch (AACException e) {
            throw new IOException("AAC decode error in unit " + unit + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("AAC decode error in unit " + unit + ": " + e, e);
        }
        if (lastLength != FRAME) {
            throw new UnsupportedAudioException("HE-AAC (SBR/PS) is not supported, only AAC-LC");
        }
    }

    private void startAt(long timelinePos) throws IOException {
        long t = timelinePos + src.skip;
        int unit = (int) Math.min(t / FRAME, src.count());
        int first = Math.max(0, unit - PREROLL);
        decoder = newDecoder();
        nextUnit = first;
        discard = t - (long) first * FRAME;
        pending = new byte[0];
        pendingPos = 0;
    }

    @Override
    protected void doSeek(long frame) throws IOException {
        startAt(frame);
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) throws IOException {
        while (pendingPos >= pending.length) {
            if (nextUnit >= src.count()) {
                return -1;
            }
            decodeUnit(decoder, nextUnit++);
            byte[] out = new byte[FRAME * 4];
            convert(out);
            int skipFrames = (int) Math.min(discard, FRAME);
            discard -= skipFrames;
            pending = skipFrames == 0 ? out : java.util.Arrays.copyOfRange(out, skipFrames * 4, out.length);
            pendingPos = 0;
        }
        int n = Math.min(len, pending.length - pendingPos);
        System.arraycopy(pending, pendingPos, buf, off, n);
        pendingPos += n;
        return n;
    }

    private void convert(byte[] out) {
        int ch = lastChannels.length;
        for (int i = 0; i < FRAME; i++) {
            float l;
            float r;
            if (ch == 1) {
                l = r = lastChannels[0][i];
            } else if (ch == 2) {
                l = lastChannels[0][i];
                r = lastChannels[1][i];
            } else {
                l = 0;
                r = 0;
                for (int c = 0; c < ch && c * 2 + 1 < mix.length; c++) {
                    float v = lastChannels[c][i];
                    l += v * mix[c * 2];
                    r += v * mix[c * 2 + 1];
                }
            }
            StereoPcm.put(out, i * 4, StereoPcm.clamp16(Math.round(l)), StereoPcm.clamp16(Math.round(r)));
        }
    }

    /**
     * Left/right gains per channel in the order the decoder delivers them (AAC channel configuration order: C, L,
     * R, then surrounds, LFE last). The LFE is dropped; gains are normalised so a full-scale signal in every
     * channel cannot exceed full scale.
     */
    static float[] mixMatrix(int channelConfig) {
        final float c = 0.7071f;
        float[] m = switch (channelConfig) {
            case 3 -> new float[] {c, c, 1, 0, 0, 1};
            case 4 -> new float[] {c, c, 1, 0, 0, 1, 0.5f, 0.5f};
            case 5 -> new float[] {c, c, 1, 0, 0, 1, c, 0, 0, c};
            case 6 -> new float[] {c, c, 1, 0, 0, 1, c, 0, 0, c, 0, 0};
            case 7 -> new float[] {c, c, 1, 0, 0, 1, c, 0, 0, c, 0.5f, 0, 0, 0.5f, 0, 0};
            default -> new float[] {1, 1};
        };
        float sumL = 0;
        float sumR = 0;
        for (int i = 0; i + 1 < m.length; i += 2) {
            sumL += m[i];
            sumR += m[i + 1];
        }
        float norm = Math.max(sumL, sumR);
        for (int i = 0; i < m.length; i++) {
            m[i] /= norm;
        }
        return m;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
