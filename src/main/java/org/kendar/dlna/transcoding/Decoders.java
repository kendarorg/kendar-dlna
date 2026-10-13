package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.nio.file.Path;

/** Opens the decoder of a source format (direct codec APIs, no Java Sound service providers for MP3/Vorbis). */
public final class Decoders {

    private Decoders() {
    }

    /** Stereo s16le decoder. MIDI is rendered, not decoded: see {@link Transcoder#renderMidi}. */
    public static AudioDecoder open(SourceFormat format, Path file) throws IOException {
        return open(format, file, 16);
    }

    /**
     * @param bits 16 (stereo s16le, 4-byte frames) or 24 (stereo s24le, 6-byte frames; formats whose decoder is only 16
     *             bit are padded with a zero low byte)
     */
    public static AudioDecoder open(SourceFormat format, Path file, int bits) throws IOException {
        boolean wide = bits > 16;
        return switch (format) {
            case MP3 -> pad(Mp3Decoder.open(file), wide);
            case OGG -> pad(VorbisDecoder.open(file), wide);
            case FLAC -> FlacDecoder.open(file, wide);
            case WAV, AIFF -> JavaSoundDecoder.open(file, wide);
            case OPUS -> pad(OpusFileDecoder.open(file), wide);
            case AAC, ADTS -> withFfmpegFallback(file, wide, () -> pad(AacDecoder.open(file), wide));
            case ALAC -> withFfmpegFallback(file, wide, () -> AlacDecoder.open(file, wide));
            case DSD -> Ffmpeg.dsdDop() ? dop(file, wide) : FfmpegDecoder.open(file, wide);
            case WMA, APE, WAVPACK, MPC, M4B -> FfmpegDecoder.open(file, wide);
            case MIDI, ABC -> throw new IOException(format + " has no decoder; it is rendered");
        };
    }

    /** The same decoder at {@code maxRate} when it runs faster than that (no change otherwise or when maxRate is 0). */
    public static AudioDecoder limitRate(AudioDecoder d, int maxRate) {
        return maxRate > 0 && d.sampleRate() > maxRate ? new ResamplingDecoder(d, maxRate) : d;
    }

    /** The sample rate and length a source of {@code rate} / {@code samples} has after {@link #limitRate}. */
    public static Transcoder.SourceInfo limited(int rate, long samples, int maxRate) {
        if (maxRate > 0 && rate > maxRate) {
            return new Transcoder.SourceInfo(maxRate, ResamplingDecoder.outputSamples(samples, rate, maxRate));
        }
        return new Transcoder.SourceInfo(rate, samples);
    }

    private static AudioDecoder dop(Path file, boolean wide) throws IOException {
        if (!wide) {
            throw new IOException("DSD over PCM (DoP) needs 24-bit output: " + file.getFileName());
        }
        return DsdDopDecoder.open(file);
    }

    private static AudioDecoder pad(AudioDecoder d, boolean wide) {
        return wide ? new PadTo24Decoder(d) : d;
    }

    private interface Opener {
        AudioDecoder open() throws IOException;
    }

    /** HE-AAC and other profiles the Java decoders refuse are decoded by ffmpeg when it is available. */
    private static AudioDecoder withFfmpegFallback(Path file, boolean wide, Opener java) throws IOException {
        try {
            return java.open();
        } catch (UnsupportedAudioException e) {
            if (!Ffmpeg.available()) {
                throw e;
            }
            return FfmpegDecoder.open(file, wide);
        }
    }

    /** Rate and exact sample count without decoding audio (MP3: header walk). */
    public static Transcoder.SourceInfo probe(SourceFormat format, Path file) throws IOException {
        if (format == SourceFormat.MP3) {
            Mp3Decoder.Info i = Mp3Decoder.probe(file);
            return new Transcoder.SourceInfo(i.sampleRate(), i.samples());
        }
        // DoP changes the rate and length of DSD files, and only exists as 24-bit
        try (AudioDecoder d = open(format, file, format == SourceFormat.DSD && Ffmpeg.dsdDop() ? 24 : 16)) {
            return new Transcoder.SourceInfo(d.sampleRate(), d.totalSamples());
        }
    }
}
