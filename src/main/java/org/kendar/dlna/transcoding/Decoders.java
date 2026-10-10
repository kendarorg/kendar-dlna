package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.nio.file.Path;

/** Opens the decoder of a source format (direct codec APIs, no Java Sound service providers for MP3/Vorbis). */
public final class Decoders {

    private Decoders() {
    }

    /** MIDI is rendered, not decoded: see {@link Transcoder#renderMidi}. */
    public static AudioDecoder open(SourceFormat format, Path file) throws IOException {
        return switch (format) {
            case MP3 -> Mp3Decoder.open(file);
            case OGG -> VorbisDecoder.open(file);
            case FLAC -> FlacDecoder.open(file);
            case WAV, AIFF -> JavaSoundDecoder.open(file);
            case OPUS -> OpusFileDecoder.open(file);
            case AAC -> AacDecoder.open(file);
            case ALAC -> AlacDecoder.open(file);
            case ADTS -> AacDecoder.open(file);
            case MIDI -> throw new IOException("MIDI has no decoder; it is rendered");
        };
    }

    /** Rate and exact sample count without decoding audio (MP3: header walk). */
    public static Transcoder.SourceInfo probe(SourceFormat format, Path file) throws IOException {
        if (format == SourceFormat.MP3) {
            Mp3Decoder.Info i = Mp3Decoder.probe(file);
            return new Transcoder.SourceInfo(i.sampleRate(), i.samples());
        }
        try (AudioDecoder d = open(format, file)) {
            return new Transcoder.SourceInfo(d.sampleRate(), d.totalSamples());
        }
    }
}
