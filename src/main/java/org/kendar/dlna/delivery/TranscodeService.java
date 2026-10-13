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
import org.kendar.dlna.transcoding.Ffmpeg;
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
    private static final String PIPELINE = "v2";
    private static final int MIDI_RATE = 48_000;
    private static final int MEMO_LIMIT = 20_000;

    /** Sample rate and exact frame count of a source. */
    public record SourceLength(int sampleRate, long samples) {
    }

    private final Transcoder transcoder;
    private final TranscodeCache cache;
    private final boolean enabled;
    private final int defaultBitrateKbps;
    private final int defaultBits;
    private final int defaultMaxRate;
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
        this(transcoder, cache, enabled, defaultBitrateKbps, 16, 0, midiStamp, sink);
    }

    /**
     * @param defaultBits    sample size of the PCM based forms offered by default: 16 or 24
     * @param defaultMaxRate sources faster than this are resampled in the PCM based forms; 0 = never
     */
    public TranscodeService(Transcoder transcoder, TranscodeCache cache, boolean enabled, int defaultBitrateKbps,
            int defaultBits, int defaultMaxRate, String midiStamp, RenderedLengthSink sink) {
        this.defaultBits = defaultBits >= 24 ? 24 : 16;
        this.defaultMaxRate = Math.max(0, defaultMaxRate);
        this.transcoder = transcoder;
        this.cache = cache;
        this.enabled = enabled;
        this.defaultBitrateKbps = defaultBitrateKbps;
        this.midiStamp = midiStamp == null ? "" : midiStamp;
        this.sink = sink == null ? RenderedLengthSink.NONE : sink;
    }

    private volatile java.util.Set<String> passthrough = java.util.Set.of();
    private final Map<String, org.kendar.dlna.transcoding.Slices.Slice> slices = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, org.kendar.dlna.transcoding.Slices.Slice> e) {
                    return size() > 2_000;
                }
            });

    /** Formats ("wav", "mp3") whose virtual tracks are served as a cut of the original instead of re-encoded. */
    public TranscodeService passthrough(java.util.Collection<String> formats) {
        this.passthrough = java.util.Set.copyOf(formats);
        return this;
    }

    /** Whether a virtual track is served as a cut of its original file. */
    public boolean canSlice(Track t) {
        if (!t.isSegment() || t.format().rendered()) {
            return false;
        }
        return (t.format() == Format.WAV && passthrough.contains("wav"))
                || (t.format() == Format.MP3 && passthrough.contains("mp3"));
    }

    /** The body of a virtual track cut from the original (see {@link #canSlice}). */
    public StreamBody sliceBody(Track t, Path file) throws IOException {
        String key = memoKey(t) + "|" + t.segment().startSample() + "-" + t.segment().endSample();
        org.kendar.dlna.transcoding.Slices.Slice slice = slices.get(key);
        if (slice == null) {
            slice = t.format() == Format.WAV
                    ? org.kendar.dlna.transcoding.Slices.wav(file, t.segment().startSample(), t.segment().endSample())
                    : org.kendar.dlna.transcoding.Slices.mp3(file, t.segment().startSample(), t.segment().endSample());
            slices.put(key, slice);
        }
        return new SliceBody(file, slice, MediaTypes.mimeType(t.format()), MediaTypes.contentFeatures(t.format()));
    }

    public boolean enabled() {
        return enabled;
    }

    public int defaultBitrateKbps() {
        return defaultBitrateKbps;
    }

    public int defaultBits() {
        return defaultBits;
    }

    public int defaultMaxRate() {
        return defaultMaxRate;
    }

    public TranscodeCache cache() {
        return cache;
    }

    /** Whether files of the format can be offered as MP3 / LPCM at all. */
    public boolean canTranscode(Format format) {
        return enabled && (!format.rendered() || transcoder.midiAvailable())
                && (!format.needsFfmpeg() || transcoder.ffmpegAvailable());
    }

    /**
     * Whether the variant can be produced for the format: the lossless encodings need ffmpeg, and DSD delivered as DoP
     * only exists as 24-bit PCM.
     */
    public boolean canProduce(Format format, StreamVariant v) {
        if (!canTranscode(format)) {
            return false;
        }
        if ((v.kind() == StreamVariant.Kind.FLAC || v.kind() == StreamVariant.Kind.ALAC) && !transcoder.ffmpegAvailable()) {
            return false;
        }
        return !(format == Format.DSD && Ffmpeg.dsdDop() && (!v.kind().pcm() || v.bits() < 24));
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

    /** Exact byte length of the transcoded form for a source length; -1 when only known after encoding (FLAC, ALAC). */
    public static long lengthOf(SourceLength src, StreamVariant resolved) {
        return Transcoder.length(resolved.output(), src.sampleRate(), src.samples());
    }

    /** The sample rate of the PCM a transcode of a source at {@code rate} delivers (MP3 is always 48 kHz). */
    public static int outputRate(int rate, StreamVariant v) {
        if (v.kind() == StreamVariant.Kind.MP3) {
            return 48_000;
        }
        return v.maxRate() > 0 && rate > v.maxRate() ? v.maxRate() : rate;
    }

    // ---- bodies --------------------------------------------------------------------------------------------------

    private String cacheKey(Track t, StreamVariant v) {
        return TranscodeCache.key(PIPELINE, t.ref().qualified(), t.mtime(), t.size(), v.path(),
                t.format().rendered() ? midiStamp
                : (t.isSegment() ? t.segment().startSample() + "-" + t.segment().endSample() : "")
                        + (t.format() == Format.DSD ? "dsd" + Ffmpeg.dsdPcmRate() + (Ffmpeg.dsdDop() ? "dop" : "") : ""));
    }

    private static TranscodeCache.Source cacheSource(Track t, StreamVariant v) {
        return new TranscodeCache.Source(t.ref().qualified(), t.mtime(), t.size(), v.path());
    }

    private static String mimeOf(StreamVariant v, int rate) {
        return MediaTypes.mimeType(v, outputRate(rate, v));
    }

    private static String featuresOf(StreamVariant v) {
        return MediaTypes.features(v);
    }

    /**
     * The body of a transcoded request. For MIDI this renders and caches first (can take seconds).
     *
     * @throws IOException when the source cannot be decoded or rendered
     */
    public StreamBody body(Track t, Path file, StreamVariant requested) throws IOException {
        StreamVariant v = requested.resolved(defaultBitrateKbps);
        String key = cacheKey(t, v);
        if (t.format().rendered() || !v.kind().fixedLength()) {
            int rate = t.format().rendered() ? MIDI_RATE : sourceOf(t, file).sampleRate();
            Path cached = cache.getOrCreate(key, cacheSource(t, v), (out, cancelled) -> produce(t, file, v, out,
                    cancelled), () -> false);
            return new StreamBody.FileBody(cached, Files.size(cached), mimeOf(v, rate), featuresOf(v));
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
        long s = t.isSegment() ? t.segment().startSample() : -1;
        long e = t.isSegment() ? t.segment().endSample() : -1;
        return transcoder.open(sourceFormat(t.format()), file, v.output(), offset, s, e);
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
            in = Transcoder.open(r, v.output(), 0);
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
        if (!requested.transcoded() || !canProduce(t.format(), requested)) {
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
