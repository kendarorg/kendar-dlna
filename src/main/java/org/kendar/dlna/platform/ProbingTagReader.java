package org.kendar.dlna.platform;

import java.nio.file.Path;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.ingestion.TrackMeta;
import org.kendar.dlna.transcoding.SourceFormat;
import org.kendar.dlna.transcoding.Transcoder;

/**
 * Adds the exact sample rate and count (what the exact length of transcoded output derives from) to the tag
 * reader's result. Cheap for Vorbis, FLAC, WAV/AIFF, Opus and MP4 audio (headers only); MP3 needs a walk of every
 * frame header and is probed lazily on first use instead. For AAC, ALAC and raw ADTS the probe is mandatory: it is
 * also what finds out that a file holds something the decoders refuse (HE-AAC, multichannel ALAC, an MP4 with a
 * video track), which is then reported as an unsupported file with the reason. Ingestion cannot depend on the
 * transcoding context, hence this decorator in the composition layer.
 */
final class ProbingTagReader implements TagReader {

    private static final Logger LOG = Logger.getLogger(ProbingTagReader.class.getName());

    private final TagReader delegate;
    private final Transcoder transcoder;

    ProbingTagReader(TagReader delegate, Transcoder transcoder) {
        this.delegate = delegate;
        this.transcoder = transcoder;
    }

    @Override
    public boolean supports(Format format) {
        return delegate.supports(format);
    }

    @Override
    public boolean owns(Format format) {
        return delegate.owns(format);
    }

    @Override
    public String unsupportedReason(Format format) {
        return delegate.unsupportedReason(format);
    }

    @Override
    public TrackMeta read(Path file, Format format) throws TagReadException {
        TrackMeta meta = delegate.read(file, format);
        Format actual = meta.format() != null ? meta.format() : format;
        boolean mp4Family = actual == Format.AAC || actual == Format.ALAC || actual == Format.ADTS;
        if (meta.sampleCount() > 0 || actual == Format.MP3 || actual == Format.MIDI) {
            return meta;
        }
        try {
            Transcoder.SourceInfo info = transcoder.probe(SourceFormat.valueOf(actual.name()), file);
            long millis = mp4Family ? Math.round(info.totalSamples() * 1000.0 / info.sampleRate())
                    : meta.durationMillis();
            int bitrate = meta.bitrateKbps();
            if (bitrate <= 0 && mp4Family && millis > 0) {
                bitrate = (int) Math.min(Integer.MAX_VALUE, java.nio.file.Files.size(file) * 8 / millis);   // raw ADTS
            }
            return new TrackMeta(meta.tags(), millis, bitrate, info.sampleRate(), meta.channels(),
                    meta.embeddedArt(), info.totalSamples(), meta.format());
        } catch (java.io.IOException | RuntimeException e) {
            if (mp4Family) {
                boolean unsupported = e instanceof org.kendar.dlna.transcoding.UnsupportedAudioException;
                throw new TagReadException(unsupported ? e.getMessage()
                        : "Cannot read " + file.getFileName() + ": " + e.getMessage(), e, unsupported);
            }
            LOG.fine("No exact sample count for " + file.getFileName() + ": " + e);
            return meta;
        }
    }
}
