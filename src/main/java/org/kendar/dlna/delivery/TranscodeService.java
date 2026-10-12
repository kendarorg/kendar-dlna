package org.kendar.dlna.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.transcoding.LpcmStream;
import org.kendar.dlna.transcoding.SourceFormat;
import org.kendar.dlna.transcoding.Transcoder;

/**
 * Production side of transcoded streams: exact lengths, the transcode cache, live encoding, MIDI rendering.
 *
 * <ul>
 *   <li>A cached transcode is served byte-exactly from its file (any Range).</li>
 *   <li>An uncached transcode is encoded live, from the requested offset (a fresh encoder at that frame).
 *       The bytes of a live stream from offset 0 are identical to the cached ones.</li>
 *   <li>MIDI is always rendered fully into the cache before the first byte (its length is only known then); the
 *       measured length goes to the {@link RenderedLengthSink}.</li>
 * </ul>
 */
public final class TranscodeService implements PrefetchTarget {

    /** Pipeline version in every cache key; bump to invalidate caches when the encoder output changes. */
    private static final String PIPELINE = "v1";
    private static final int MIDI_RATE = 48_000;
    private static final int MEMO_LIMIT = 20_000;

    /** Sample rate and exact frame count of a source. */
    public record SourceLength(int sampleRate, long samples) {
    }

    private final Transcoder transcoder;
    private final TranscodeCache cache;
    private final boolean enabled;
    private final int defaultBitrateKbps;
    private final String midiStamp;
    private final RenderedLengthSink sink;
    private final Map<String, SourceLength> memo = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SourceLength> eldest) {
                    return size() > MEMO_LIMIT;
                }
            });

    /**
     * @param midiStamp identifies the SoundFont in use; part of the cache key of MIDI renders
     */
    public TranscodeService(Transcoder transcoder, TranscodeCache cache, boolean enabled, int defaultBitrateKbps,
            String midiStamp, RenderedLengthSink sink) {
        this.transcoder = transcoder;
        this.cache = cache;
        this.enabled = enabled;
        this.defaultBitrateKbps = defaultBitrateKbps;
        this.midiStamp = midiStamp == null ? "" : midiStamp;
        this.sink = sink == null ? RenderedLengthSink.NONE : sink;
    }

    public boolean enabled() {
        return enabled;
    }

    public int defaultBitrateKbps() {
        return defaultBitrateKbps;
    }

    public TranscodeCache cache() {
        return cache;
    }

    /** Whether files of the format can be offered as MP3 / LPCM at all. */
    public boolean canTranscode(Format format) {
        return enabled && (!format.rendered() || transcoder.midiAvailable());
    }

    private static SourceFormat sourceFormat(Format f) {
        return SourceFormat.valueOf(f.name());
    }

    // ---- lengths -------------------------------------------------------------------------------------------------

    /** The sample rate and count of a transcode source when known without reading the file. */
    public Optional<SourceLength> knownSource(Track t) {
        if (t.format().rendered()) {
            return t.sampleCount() > 0 ? Optional.of(new SourceLength(MIDI_RATE, t.sampleCount())) : Optional.empty();
        }
        if (t.isSegment()) {
            return t.sampleRate() > 0 && t.sampleCount() > 0
                    ? Optional.of(new SourceLength(t.sampleRate(), t.sampleCount())) : Optional.empty();
        }
        if (t.sampleCount() > 0 && t.sampleRate() > 0) {
            return Optional.of(new SourceLength(t.sampleRate(), t.sampleCount()));
        }
        return Optional.ofNullable(memo.get(memoKey(t)));
    }

    private SourceLength sourceOf(Track t, Path file) throws IOException {
        Optional<SourceLength> known = knownSource(t);
        if (known.isPresent()) {
            return known.get();
        }
        Transcoder.SourceInfo info = transcoder.probe(sourceFormat(t.format()), file);
        SourceLength len = new SourceLength(info.sampleRate(), info.totalSamples());
        memo.put(memoKey(t), len);
        return len;
    }

    private static String memoKey(Track t) {
        return t.ref().qualified() + "|" + t.mtime() + "|" + t.size();
    }

    /** Exact byte length of the transcoded form for a source length. */
    public static long lengthOf(SourceLength src, StreamVariant resolved) {
        return resolved.kind() == StreamVariant.Kind.LPCM ? Transcoder.lpcmLength(src.samples())
                : Transcoder.mp3Length(src.samples(), src.sampleRate(), resolved.bitrateKbps());
    }

    // ---- bodies --------------------------------------------------------------------------------------------------

    private String cacheKey(Track t, StreamVariant v) {
        return TranscodeCache.key(PIPELINE, t.ref().qualified(), t.mtime(), t.size(), v.path(),
                t.format().rendered() ? midiStamp
                : t.isSegment() ? t.segment().startSample() + "-" + t.segment().endSample() : "");
    }

    private static TranscodeCache.Source cacheSource(Track t, StreamVariant v) {
        return new TranscodeCache.Source(t.ref().qualified(), t.mtime(), t.size(), v.path());
    }

    private static String mimeOf(StreamVariant v, int rate) {
        return v.kind() == StreamVariant.Kind.LPCM ? LpcmStream.mimeType(rate) : "audio/mpeg";
    }

    private static String featuresOf(StreamVariant v) {
        return v.kind() == StreamVariant.Kind.LPCM ? MediaTypes.lpcmFeatures() : MediaTypes.transcodedMp3Features();
    }

    /**
     * The body of a transcoded request. For MIDI this renders and caches first (can take seconds).
     *
     * @throws IOException when the source cannot be decoded or rendered
     */
    public StreamBody body(Track t, Path file, StreamVariant requested) throws IOException {
        StreamVariant v = requested.resolved(defaultBitrateKbps);
        String key = cacheKey(t, v);
        if (t.format().rendered()) {
            Path cached = cache.getOrCreate(key, cacheSource(t, v), (out, cancelled) -> produce(t, file, v, out,
                    cancelled), () -> false);
            return new StreamBody.FileBody(cached, Files.size(cached), mimeOf(v, MIDI_RATE), featuresOf(v));
        }
        SourceLength src = sourceOf(t, file);
        long length = lengthOf(src, v);
        String mime = mimeOf(v, src.sampleRate());
        Optional<Path> hit = cache.get(key);
        if (hit.isPresent()) {
            if (Files.size(hit.get()) == length) {
                return new StreamBody.FileBody(hit.get(), length, mime, featuresOf(v));
            }
            cache.remove(key);   // wrong size: never serve it
        }
        return new LiveBody(t, file, v, length, mime);
    }

    /** Encodes while the client reads, starting a fresh encoder at the requested offset. */
    private final class LiveBody implements StreamBody {
        private final Track track;
        private final Path file;
        private final StreamVariant variant;
        private final long length;
        private final String mime;

        LiveBody(Track track, Path file, StreamVariant variant, long length, String mime) {
            this.track = track;
            this.file = file;
            this.variant = variant;
            this.length = length;
            this.mime = mime;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public String mimeType() {
            return mime;
        }

        @Override
        public String contentFeatures() {
            return featuresOf(variant);
        }

        @Override
        public void writeTo(long start, long len, OutputStream out) throws IOException {
            try (InputStream in = open(track, file, variant, start)) {
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) {
                        throw new IOException("Transcoded stream ended " + left + " bytes early: " + file.getFileName());
                    }
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
        }
    }

    private InputStream open(Track t, Path file, StreamVariant v, long offset) throws IOException {
        SourceFormat f = sourceFormat(t.format());
        if (t.isSegment()) {
            long s = t.segment().startSample();
            long e = t.segment().endSample();
            return v.kind() == StreamVariant.Kind.LPCM ? transcoder.openLpcm(f, file, offset, s, e)
                    : transcoder.openMp3(f, file, v.bitrateKbps(), offset, s, e);
        }
        return v.kind() == StreamVariant.Kind.LPCM ? transcoder.openLpcm(f, file, offset)
                : transcoder.openMp3(f, file, v.bitrateKbps(), offset);
    }

    /** Writes the whole transcoded form (from offset 0) to {@code out}. */
    private void produce(Track t, Path file, StreamVariant v, OutputStream out, BooleanSupplier cancelled)
            throws IOException {
        InputStream in;
        if (t.format().rendered()) {
            Transcoder.Rendered r = t.format() == Format.ABC
                    ? transcoder.renderAbc(file, t.isSegment() ? t.segment().index() : 1)
                    : transcoder.renderMidi(file);
            sink.rendered(t, r.frames(), Math.round(r.frames() * 1000.0 / MIDI_RATE));
            in = v.kind() == StreamVariant.Kind.LPCM ? Transcoder.openLpcm(r, 0)
                    : Transcoder.openMp3(r, v.bitrateKbps(), 0);
        } else {
            in = open(t, file, v, 0);
        }
        try (in) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Transcode cancelled: " + file.getFileName());
                }
                out.write(buf, 0, n);
            }
        }
    }

    // ---- prefetch ------------------------------------------------------------------------------------------------

    @Override
    public boolean needsPrefetch(Track t, StreamVariant requested) {
        if (!requested.transcoded() || !canTranscode(t.format())) {
            return false;
        }
        return !cache.contains(cacheKey(t, requested.resolved(defaultBitrateKbps)));
    }

    @Override
    public void prefetch(Track t, Path file, StreamVariant requested, BooleanSupplier cancelled) throws IOException {
        StreamVariant v = requested.resolved(defaultBitrateKbps);
        cache.getOrCreate(cacheKey(t, v), cacheSource(t, v), (out, c) -> produce(t, file, v, out, c), cancelled);
    }
}
