package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.nio.file.Path;

/** Ogg Opus through {@link OggOpusReader} (48 kHz; exact length = last granule minus pre-skip). */
final class OpusFileDecoder extends FixedLengthDecoder {

    private final OggOpusReader reader;
    private short[] samples = new short[0];

    static OpusFileDecoder open(Path file) throws IOException {
        OggOpusReader r = OggOpusReader.open(file);
        if (r.totalSamples() <= 0 || r.channels() < 1) {
            r.close();
            throw new IOException("Opus stream without audio: " + file.getFileName());
        }
        return new OpusFileDecoder(r);
    }

    private OpusFileDecoder(OggOpusReader reader) {
        super(reader.totalSamples());
        this.reader = reader;
    }

    @Override
    public int sampleRate() {
        return OggOpusReader.RATE;
    }

    @Override
    protected void doSeek(long frame) throws IOException {
        reader.seek(frame);
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) throws IOException {
        int ch = reader.channels();
        int frames = len / 4;
        if (samples.length < frames * ch) {
            samples = new short[frames * ch];
        }
        int n = reader.read(samples, frames);
        if (n <= 0) {
            return -1;
        }
        for (int f = 0; f < n; f++) {
            int l = samples[f * ch];
            int r = ch > 1 ? samples[f * ch + 1] : l;
            StereoPcm.put(buf, off + f * 4, l, r);
        }
        return n * 4;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
