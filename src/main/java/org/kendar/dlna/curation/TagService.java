package org.kendar.dlna.curation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.ingestion.CatalogService;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.ingestion.TrackMeta;
import org.kendar.dlna.shared.RootRef;

/**
 * Tag writes (single and batch). Each file is written safely: copied to a hidden temp file in the same
 * directory, tagged there, atomically renamed over the original. All successfully written files then enter the
 * catalog in one commit (which persists the index and, through the change listeners, invalidates stale
 * transcode cache entries and bumps the update ids).
 */
public final class TagService {

    private static final Logger LOG = Logger.getLogger(TagService.class.getName());

    /** Outcome for one file of a batch. */
    public record Result(RootRef ref, boolean ok, String error, String code) {
    }

    private final CatalogService catalog;
    private final LibraryFiles files;
    private final TagWriter writer;
    private final TagReader reader;

    public TagService(CatalogService catalog, LibraryFiles files, TagWriter writer, TagReader reader) {
        this.catalog = catalog;
        this.files = files;
        this.writer = writer;
        this.reader = reader;
    }

    /** The reader used to re-read files after a write (also used by the list.json editor). */
    public TagReader reader() {
        return reader;
    }

    /** Writes one file; a failure is an exception. */
    public Track writeOne(RootRef ref, TagEdit edit) {
        Result r = write(List.of(ref), edit).get(0);
        if (!r.ok()) {
            throw new CurationException(r.code(), r.error());
        }
        return catalog.current().track(ref).orElseThrow();
    }

    /**
     * Writes the same change to many files. Invalid input (empty or malformed edit, no files) fails as a
     * whole; per-file problems (unknown, MIDI/Opus, unwritable) are reported in the results while the other
     * files are still written.
     */
    public synchronized List<Result> write(List<RootRef> refs, TagEdit edit) {
        if (refs == null || refs.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "No files given");
        }
        if (edit == null || edit.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "Nothing to change");
        }
        List<String> problems = edit.problems();
        if (!problems.isEmpty()) {
            throw new CurationException(CurationException.INVALID, String.join("; ", problems), problems);
        }
        List<Result> results = new ArrayList<>();
        Map<RootRef, Track> updated = new HashMap<>();
        for (RootRef ref : refs) {
            try {
                updated.put(ref, writeFile(ref, edit));
                results.add(new Result(ref, true, null, null));
            } catch (CurationException e) {
                results.add(new Result(ref, false, e.getMessage(), e.code()));
            }
        }
        if (!updated.isEmpty()) {
            catalog.update(cur -> {
                List<Track> tracks = new ArrayList<>(cur.tracks());
                tracks.replaceAll(t -> updated.getOrDefault(t.ref(), t));
                return CatalogEdits.withTracks(cur, tracks);
            });
        }
        return results;
    }

    private Track writeFile(RootRef ref, TagEdit edit) {
        Track old = catalog.current().track(ref)
                .orElseThrow(() -> new CurationException(CurationException.NOT_FOUND, "Unknown track: " + ref));
        if (old.isSegment()) {
            throw new CurationException(CurationException.NOT_EDITABLE,
                    "A cue sheet or chapter track has no tags of its own, edit the cue sheet / list.json: " + ref);
        }
        if (!old.format().tagsEditable()) {
            throw new CurationException(CurationException.NOT_EDITABLE,
                    "Tags of " + old.format() + " files cannot be edited: " + ref);
        }
        if (edit.restrictedTo(old.format().editableFields()).isEmpty()) {
            return old;   // nothing in the edit applies to this format (e.g. an album for a MIDI file): skipped
        }
        Path file = files.resolve(ref);
        if (file == null || !Files.isRegularFile(file)) {
            throw new CurationException(CurationException.NOT_FOUND, "File is missing on disk: " + ref);
        }
        Path tmp = AtomicFiles.tempSibling(file);
        try {
            Files.copy(file, tmp);
            writer.write(tmp, old.format(), edit);
            AtomicFiles.replace(tmp, file);
        } catch (TagWriter.TagWriteException | IOException e) {
            LOG.warning("Tag write failed for " + ref + ": " + e);
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot write tags of " + ref + ": " + e.getMessage(), null, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                LOG.warning("Cannot delete temp file " + tmp + ": " + e);
            }
        }
        try {
            // a MIDI file's persisted rendered length belongs to the old content: reset, measured again on next render
            boolean midi = old.format() == org.kendar.dlna.catalog.Format.MIDI;
            TrackMeta meta = reader.read(file, old.format());
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new Track(ref, attrs.lastModifiedTime().toMillis(), attrs.size(), old.format(),
                    org.kendar.dlna.ingestion.ListJson.applyFromDisk(file, meta.tags()), old.durationMillis(), old.bitrateKbps(), old.sampleRate(), old.channels(), meta.embeddedArt(),
                    midi ? 0 : old.sampleCount(), midi ? 0 : old.renderedMillis(), old.addedAt());
        } catch (TagReader.TagReadException | IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Tags written but the file could not be re-read (rescan to refresh): " + ref + ": "
                            + e.getMessage(), null, e);
        }
    }
}
