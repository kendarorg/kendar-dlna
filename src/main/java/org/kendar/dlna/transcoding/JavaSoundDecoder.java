package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * WAV and AIFF through Java Sound: converted to signed 16-bit little-endian at the source channel count, then to
 * stereo. The exact length is the file's frame length; seeking skips bytes of the converted stream.
 */
final class JavaSoundDecoder extends FixedLengthDecoder {

    private final Path file;
    private final int sampleRate;
    private final int channels;
    private final int bytesPerSample;
    private AudioInputStream stream;
    private byte[] raw = new byte[0];

    static JavaSoundDecoder open(Path file) throws IOException {
        return open(file, false);
    }

    /** @param wide deliver stereo s24le (6-byte frames) instead of s16le */
    static JavaSoundDecoder open(Path file, boolean wide) throws IOException {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat f = ais.getFormat();
            long frames = ais.getFrameLength();
            if (frames <= 0) {
                throw new IOException("Audio file without a known length: " + file.getFileName());
            }
            return new JavaSoundDecoder(file, Math.round(f.getSampleRate()), f.getChannels(), frames, wide);
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio file " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private JavaSoundDecoder(Path file, int rate, int channels, long frames, boolean wide) throws IOException {
        super(frames, wide ? 6 : 4);
        this.bytesPerSample = wide ? 3 : 2;
        this.file = file;
        this.sampleRate = rate;
        this.channels = channels;
        start();
    }

    private void start() throws IOException {
        closeQuietly();
        try {
            AudioInputStream src = AudioSystem.getAudioInputStream(file.toFile());
            AudioFormat f = src.getFormat();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.getSampleRate(), bytesPerSample * 8,
                    f.getChannels(), f.getChannels() * bytesPerSample, f.getSampleRate(), false);
            if (f.matches(target)) {
                stream = src;
            } else {
                try {
                    stream = AudioSystem.getAudioInputStream(target, src);
                } catch (IllegalArgumentException e) {
                    src.close();
                    throw new IOException("Unsupported audio encoding " + f + " in " + file.getFileName(), e);
                }
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio file " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    protected void doSeek(long frame) throws IOException {
        start();
        long left = frame * channels * bytesPerSample;
        while (left > 0) {
            long skipped = stream.skip(left);
            if (skipped <= 0) {
                if (stream.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) throws IOException {
        int frameBytes = channels * bytesPerSample;
        int frames = len / frameBytes();
        if (raw.length < frames * frameBytes) {
            raw = new byte[frames * frameBytes];
        }
        int n = stream.readNBytes(raw, 0, frames * frameBytes);
        int got = n / frameBytes;
        if (got == 0) {
            return -1;
        }
        return bytesPerSample == 3 ? StereoPcm.toStereo24(raw, 0, got, channels, 3, 24, buf, off)
                : StereoPcm.toStereo16(raw, 0, got, channels, 2, 16, buf, off);
    }

    private void closeQuietly() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                // ignore
            }
        }
        stream = null;
    }

    @Override
    public void close() {
        closeQuietly();
    }
}
