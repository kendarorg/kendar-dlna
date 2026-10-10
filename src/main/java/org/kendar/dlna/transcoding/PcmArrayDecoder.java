package org.kendar.dlna.transcoding;

/** An in-memory stereo s16le buffer as a decoder (rendered MIDI, tests). */
public final class PcmArrayDecoder extends FixedLengthDecoder {

    private final byte[] pcm;
    private final int rate;
    private int pos;

    public PcmArrayDecoder(byte[] stereoS16le, int sampleRate) {
        super(stereoS16le.length / 4);
        this.pcm = stereoS16le;
        this.rate = sampleRate;
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    protected void doSeek(long frame) {
        pos = (int) (frame * 4);
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) {
        int n = Math.min(len, pcm.length - pos);
        if (n <= 0) {
            return -1;
        }
        System.arraycopy(pcm, pos, buf, off, n);
        pos += n;
        return n;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
