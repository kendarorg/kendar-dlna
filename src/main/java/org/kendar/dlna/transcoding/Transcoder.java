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

    private final MidiRenderer midi;
    private final Semaphore renders = new Semaphore(1);

    /** @param midi renderer for MIDI sources, or null when MIDI is unavailable */
    public Transcoder(MidiRenderer midi) {
        this.midi = midi;
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

    /** MP3 of an in-memory render (MIDI). */
    public static InputStream openMp3(Rendered render, int bitrateKbps, long byteOffset) throws IOException {
        return new Mp3Stream(render.decoder(), bitrateKbps, byteOffset);
    }

    public static InputStream openLpcm(Rendered render, long byteOffset) throws IOException {
        return new LpcmStream(render.decoder(), byteOffset);
    }

    /** Renders a MIDI file (also {@code .kar}) fully; blocks while another render runs. */
    public Rendered renderMidi(Path file) throws IOException {
        if (midi == null) {
            throw new IOException("MIDI rendering is unavailable");
        }
        try {
            Sequence seq = MidiSystem.getSequence(file.toFile());
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
