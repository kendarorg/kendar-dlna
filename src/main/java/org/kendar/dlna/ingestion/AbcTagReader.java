package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.TrackTags;

/**
 * Metadata of ABC notation files ({@code .abc}). Like MIDI they are only offered rendered, so they are listed as
 * unsupported when MIDI rendering is not available. The tags are those of the first tune: title ({@code T:}),
 * artist and composer ({@code C:}), album ({@code B:}), transcriber ({@code Z:}); a file with several tunes is split
 * into one virtual track per tune by the scanner ({@link AbcAlbums}).
 */
public final class AbcTagReader implements TagReader {

    private final boolean renderingAvailable;
    private final String unavailableReason;

    public AbcTagReader(boolean renderingAvailable, String unavailableReason) {
        this.renderingAvailable = renderingAvailable;
        this.unavailableReason = unavailableReason;
    }

    @Override
    public boolean supports(Format format) {
        return format == Format.ABC && renderingAvailable;
    }

    @Override
    public boolean owns(Format format) {
        return format == Format.ABC;
    }

    @Override
    public String unsupportedReason(Format format) {
        return "ABC rendering unavailable: " + unavailableReason;
    }

    @Override
    public TrackMeta read(Path file, Format format) throws TagReadException {
        List<AbcFile.Tune> tunes;
        try {
            tunes = AbcFile.parse(file);
        } catch (IOException e) {
            throw new TagReadException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        if (tunes.isEmpty()) {
            throw new TagReadException("no playable tune in " + file.getFileName(), null, true);
        }
        AbcFile.Tune first = tunes.get(0);
        return new TrackMeta(tags(first), first.durationMillis(), 0, 0, 0, false);
    }

    static TrackTags tags(AbcFile.Tune t) {
        return new TrackTags(t.title(), t.composer(), t.book(), null, null, null, null, null, null, List.of(),
                List.of(), List.of(), t.composer(), List.of(), t.transcriber());
    }
}
