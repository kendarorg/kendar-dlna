package org.kendar.dlna.ingestion;

import java.nio.file.Path;
import java.util.List;

import org.kendar.dlna.catalog.Format;

/** Delegates each format to the first reader that supports it. */
public final class CompositeTagReader implements TagReader {

    private final List<TagReader> readers;

    public CompositeTagReader(TagReader... readers) {
        this.readers = List.of(readers);
    }

    @Override
    public boolean supports(Format format) {
        return readers.stream().anyMatch(r -> r.supports(format));
    }

    @Override
    public boolean owns(Format format) {
        return readers.stream().anyMatch(r -> r.owns(format));
    }

    @Override
    public String unsupportedReason(Format format) {
        for (TagReader r : readers) {
            if (r.owns(format)) {
                return r.unsupportedReason(format);
            }
        }
        return TagReader.super.unsupportedReason(format);
    }

    @Override
    public TrackMeta read(Path file, Format format) throws TagReadException {
        for (TagReader r : readers) {
            if (r.supports(format)) {
                return r.read(file, format);
            }
        }
        throw new TagReadException("No reader for " + format, null);
    }
}
