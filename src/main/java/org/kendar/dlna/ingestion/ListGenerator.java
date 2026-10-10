package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Segment;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.RootRef;

/**
 * Proposes {@code list.json} entries for a directory from the cue sheets and embedded chapters of its audio files:
 * one entry per virtual track, keyed by source {@code filename} + {@code index} + {@code start}, with title,
 * author (performer), partOf (album) and transcriber taken from the cue / chapter data (no existing list.json is
 * applied, so this is what the sections themselves say). A file whose cue sheet has a single track covering the
 * whole file yields one whole-file entry. Nothing is written.
 */
public final class ListGenerator {

    private static final Logger LOG = Logger.getLogger(ListGenerator.class.getName());

    /** A file that is split into sections. */
    public record Source(String filename, String origin, String cueFile, int tracks) {
    }

    public record Result(List<ListJson.Entry> entries, List<Source> sources, List<String> warnings) {
        public Result {
            entries = List.copyOf(entries);
            sources = List.copyOf(sources);
            warnings = List.copyOf(warnings);
        }
    }

    private ListGenerator() {
    }

    public static Result generate(RootRef dirRef, Path dir, TagReader reader) {
        List<String> warnings = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                Optional<Format> f = Format.fromFileName(n);
                if (!n.startsWith(".") && f.isPresent() && Files.isRegularFile(p) && reader.supports(f.get())) {
                    names.add(n);
                }
            }
        } catch (IOException e) {
            warnings.add("cannot list the directory: " + e.getMessage());
        }
        names.sort(Normalizer.NATURAL);
        return generate(dirRef, dir, names, warnings, reader);
    }

    /** Like {@link #generate(RootRef, Path, TagReader)} for the one audio file {@code name} of {@code dir}. */
    public static Result generateFile(RootRef dirRef, Path dir, String name, TagReader reader) {
        return generate(dirRef, dir, List.of(name), new ArrayList<>(), reader);
    }

    private static Result generate(RootRef dirRef, Path dir, List<String> names, List<String> warnings,
            TagReader reader) {
        List<ListJson.Entry> entries = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        Map<String, CueDirectory.Match> cues = CueDirectory.read(dir);
        for (String name : names) {
            try {
                Optional<VirtualAlbums.Result> built = build(dirRef, dir, name, reader, cues, null, null);
                if (built.isEmpty()) {
                    continue;
                }
                VirtualAlbums.Result r = built.get();
                for (String w : r.warnings()) {
                    warnings.add(name + ": " + w);
                }
                if (r.virtual()) {
                    for (Track t : r.tracks()) {
                        Segment seg = t.segment();
                        TrackTags tags = t.tags();
                        entries.add(new ListJson.Entry(name, tags.artist(), tags.album(), tags.title(),
                                tags.transcriber(), seg.index(), ListJson.formatTime(seg.startMillis())));
                    }
                    sources.add(new Source(name, r.folder().split().origin(), r.folder().split().cueFile(),
                            r.tracks().size()));
                } else if (r.wholeTags() != null) {
                    TrackTags tags = r.wholeTags();
                    entries.add(new ListJson.Entry(name, tags.artist(), tags.album(), tags.title(),
                            tags.transcriber()));
                }
            } catch (TagReader.TagReadException | IOException | RuntimeException e) {
                LOG.fine("Cannot read sections of " + name + ": " + e);
                warnings.add(name + ": cannot be read (" + e.getMessage() + ")");
            }
        }
        return new Result(entries, sources, warnings);
    }

    /**
     * Builds the virtual album of one audio file of {@code dir} from its cue sheet or embedded chapters, merging
     * {@code list} (may be null) over the sections; empty when the file has no sections.
     */
    public static Optional<VirtualAlbums.Result> build(RootRef dirRef, Path dir, String name, TagReader reader,
            Map<String, CueDirectory.Match> cues, ListJson.Result list, Folder parent)
            throws TagReader.TagReadException, IOException {
        Path file = dir.resolve(name);
        Format format = Format.fromFileName(name).orElseThrow();
        TrackMeta meta = reader.read(file, format);
        Format actual = meta.format() != null ? meta.format() : format;
        CueDirectory.Match cue = cues.get(ListJson.key(name));
        CueSheet sheet;
        CueSheet.CueFile entry;
        String origin;
        String cueFile;
        if (cue != null) {
            sheet = cue.sheet();
            entry = cue.file();
            origin = "cue";
            cueFile = cue.cueName();
        } else {
            Optional<EmbeddedSections.Found> emb = EmbeddedSections.read(file, actual);
            if (emb.isEmpty()) {
                return Optional.empty();
            }
            sheet = emb.get().sheet();
            entry = sheet.files().get(0);
            origin = emb.get().origin();
            cueFile = null;
        }
        VirtualAlbums.Source src = new VirtualAlbums.Source(dirRef.child(name),
                Files.getLastModifiedTime(file).toMillis(), Files.size(file), 0, actual, meta);
        return Optional.of(VirtualAlbums.build(src, sheet, entry, origin, "", cueFile, list, parent));
    }
}
