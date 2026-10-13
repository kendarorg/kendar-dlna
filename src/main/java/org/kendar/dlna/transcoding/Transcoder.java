package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;

/**
 * Entry point of the transcoding context: probes sources, opens MP3 / LPCM streams with exact lengths and offsets,
 * and renders MIDI. Streams are independent objects; the only shared state is the MIDI renderer (one render at a
 * time, a render holds the whole PCM in memory).
 */
public final class Transcoder {

    /** Sample rate and exact frame count of a source. */
    public record SourceInfo(int sampleRate, long totalSamples) {
    }

    /** A finished MIDI render: stereo s16le at {@link #sampleRate()}, already tail-trimmed. */
    public record Rendered(byte[] pcm, int sampleRate) {
        public long frames() {
            return pcm.length / 4;
        }

        public AudioDecoder decoder() {
            return new PcmArrayDecoder(pcm, sampleRate);
        }
    }

    /** Turns the n-th tune (from 1) of an ABC file into a MIDI sequence; supplied by the platform. */
    @FunctionalInterface
    public interface AbcLoader {
        Sequence load(Path file, int tune) throws IOException, InvalidMidiDataException;
    }

    private final MidiRenderer midi;
    private final AbcLoader abc;
    private final Semaphore renders = new Semaphore(1);

    /** @param midi renderer for MIDI sources, or null when MIDI is unavailable */
    public Transcoder(MidiRenderer midi) {
        this(midi, null);
    }

    /** @param abc converter of ABC tunes, or null when ABC files cannot be played */
    public Transcoder(MidiRenderer midi, AbcLoader abc) {
        this.midi = midi;
        this.abc = abc;
    }

    /** Whether WMA and APE can be decoded (ffmpeg was found at startup). */
    public boolean ffmpegAvailable() {
        return Ffmpeg.available();
    }

    public boolean midiAvailable() {
        return midi != null;
    }

    public SourceInfo probe(SourceFormat format, Path file) throws IOException {
        return Decoders.probe(format, file);
    }

    /** The MP3 form of the source from {@code byteOffset}; closing the stream releases the decoder. */
    public InputStream openMp3(SourceFormat format, Path file, int bitrateKbps, long byteOffset) throws IOException {
        return new Mp3Stream(Decoders.open(format, file), bitrateKbps, byteOffset);
    }

    public InputStream openLpcm(SourceFormat format, Path file, long byteOffset) throws IOException {
        return new LpcmStream(Decoders.open(format, file), byteOffset);
    }

    /** The MP3 form of the sample range [startSample, endSample) of the source (a virtual track). */
    public InputStream openMp3(SourceFormat format, Path file, int bitrateKbps, long byteOffset, long startSample,
            long endSample) throws IOException {
        return new Mp3Stream(segment(format, file, startSample, endSample), bitrateKbps, byteOffset);
    }

    /** The LPCM form of the sample range [startSample, endSample) of the source (a virtual track). */
    public InputStream openLpcm(SourceFormat format, Path file, long byteOffset, long startSample, long endSample)
            throws IOException {
        return new LpcmStream(segment(format, file, startSample, endSample), byteOffset);
    }

    private static AudioDecoder segment(SourceFormat format, Path file, long startSample, long endSample)
            throws IOException {
        AudioDecoder d = Decoders.open(format, file);
        try {
            return new SegmentDecoder(d, startSample, endSample);
        } catch (IOException | RuntimeException e) {
            d.close();
            throw e;
        }
    }

    /**
     * Any output form of the source (or of the sample range [startSample, endSample) when {@code startSample >= 0}) from
     * {@code byteOffset}; {@code maxRate} resampling and 24-bit decoding come from {@code output}.
     */
    public InputStream open(SourceFormat format, Path file, TranscodeOutput output, long byteOffset, long startSample,
            long endSample) throws IOException {
        AudioDecoder d = Decoders.open(format, file, output.decoderBits());
        return stream(prepare(d, startSample, endSample, output), output, byteOffset);
    }

    /** Any output form of an in-memory render (MIDI, ABC). */
    public static InputStream open(Rendered render, TranscodeOutput output, long byteOffset) throws IOException {
        AudioDecoder d = render.decoder();
        if (output.decoderBits() > 16) {
            d = new PadTo24Decoder(d);
        }
        return stream(prepare(d, -1, -1, output), output, byteOffset);
    }

    private static AudioDecoder prepare(AudioDecoder d, long start, long end, TranscodeOutput output)
            throws IOException {
        try {
            if (start >= 0) {
                d = new SegmentDecoder(d, start, end);
            }
            return Decoders.limitRate(d, output.maxRate());
        } catch (IOException | RuntimeException e) {
            d.close();
            throw e;
        }
    }

    private static InputStream stream(AudioDecoder d, TranscodeOutput output, long byteOffset) throws IOException {
        return switch (output.target()) {
            case MP3 -> new Mp3Stream(d, output.bitrateKbps(), byteOffset);
            case LPCM -> new LpcmStream(d, byteOffset);
            case WAV -> new WavStream(d, byteOffset);
            case FLAC, ALAC -> new EncodedFileStream(d, output.target(), byteOffset);
        };
    }

    /** The rate and length of the PCM a source of {@code rate} / {@code samples} gives under {@code output}. */
    public static SourceInfo outputInfo(int rate, long samples, TranscodeOutput output) {
        return Decoders.limited(rate, samples, output.maxRate());
    }

    /**
     * Exact length in bytes of the output form of a source, or -1 when it is only known after encoding (FLAC, ALAC).
     */
    public static long length(TranscodeOutput output, int rate, long samples) {
        SourceInfo o = outputInfo(rate, samples, output);
        return switch (output.target()) {
            case MP3 -> Mp3Stream.length(samples, rate, output.bitrateKbps());
            case LPCM -> LpcmStream.length(o.totalSamples(), output.bits());
            case WAV -> WavStream.length(o.totalSamples(), output.bits());
            case FLAC, ALAC -> -1;
        };
    }

    /** MP3 of an in-memory render (MIDI). */
    public static InputStream openMp3(Rendered render, int bitrateKbps, long byteOffset) throws IOException {
        return new Mp3Stream(render.decoder(), bitrateKbps, byteOffset);
    }

    public static InputStream openLpcm(Rendered render, long byteOffset) throws IOException {
        return new LpcmStream(render.decoder(), byteOffset);
    }

    /** Renders a MIDI file (also {@code .kar}) fully; blocks while another render runs. */
    public Rendered renderMidi(Path file) throws IOException {
        return render(file, () -> MidiSystem.getSequence(file.toFile()));
    }

    /** Renders the tune {@code tune} (from 1) of an ABC file. */
    public Rendered renderAbc(Path file, int tune) throws IOException {
        if (abc == null) {
            throw new IOException("ABC conversion is unavailable");
        }
        return render(file, () -> abc.load(file, tune));
    }

    private interface SequenceSource {
        Sequence get() throws IOException, InvalidMidiDataException;
    }

    private Rendered render(Path file, SequenceSource source) throws IOException {
        if (midi == null) {
            throw new IOException("MIDI rendering is unavailable");
        }
        try {
            Sequence seq = source.get();
            renders.acquire();
            try {
                MidiRenderer.Result r = midi.render(seq);
                return new Rendered(r.pcm(), r.sampleRate());
            } finally {
                renders.release();
            }
        } catch (InvalidMidiDataException | MidiUnavailableException e) {
            throw new IOException("Cannot render " + file.getFileName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to render " + file.getFileName(), e);
        }
    }

    /** Whole stream length in bytes for a transcode of {@code samples} frames at {@code rate}. */
    public static long mp3Length(long samples, int rate, int bitrateKbps) {
        return Mp3Stream.length(samples, rate, bitrateKbps);
    }

    public static long lpcmLength(long samples) {
        return LpcmStream.length(samples);
    }

}
