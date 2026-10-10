package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.kendar.dlna.catalog.Format;

/**
 * The per-file sections list: {@code <full audio file name>.json} next to the audio file (for example
 * {@code concert.flac.json} for {@code concert.flac}). It uses the entry schema of {@link ListJson} (an array of
 * {@code {filename, index, start, author, partOf, title, transcriber}} objects) and is parsed as tolerantly. The
 * {@code filename} of an entry is optional (it is the audio file) and, when given, must name that file (an entry
 * about another file is skipped with a warning). An entry without {@code index} / {@code start} is about the whole
 * file (album level {@code partOf} / {@code author}); entries with one are about a section.
 *
 * <p>Precedence for the metadata of a file's sections: its sidecar, then the entries of the directory's
 * {@code list.json}, then the cue sheet / embedded chapters, then the tags. When a file has a usable sidecar the
 * directory list.json is not consulted for that file at all.
 */
public final class SidecarJson {

    public static final String SUFFIX = ".json";

    private SidecarJson() {
    }

    /** The sidecar path of an audio file (it may not exist). */
    public static Path path(Path audio) {
        return audio.resolveSibling(audio.getFileName().toString() + SUFFIX);
    }

    /** True when {@code fileName} looks like the sidecar of an audio file: an audio file name plus {@code .json}. */
    public static boolean isSidecarName(String fileName) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) {
            return false;
        }
        String stem = fileName.substring(0, fileName.length() - SUFFIX.length());
        return !stem.isEmpty() && !stem.startsWith(".") && Format.fromFileName(stem).isPresent();
    }

    /** The existing sidecar of {@code audio} (any letter case of the suffix or name), or empty. */
    public static Optional<Path> locate(Path audio) {
        Path exact = path(audio);
        if (Files.isRegularFile(exact)) {
            return Optional.of(exact);
        }
        Path dir = audio.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String wanted = ListJson.key(audio.getFileName().toString() + SUFFIX);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (ListJson.key(p.getFileName().toString()).equals(wanted) && Files.isRegularFile(p)) {
                    return Optional.of(p);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Parses the sidecar of {@code audio}; {@link ListJson.Result#NONE} when it has none. Never throws. */
    public static ListJson.Result read(Path audio) {
        return locate(audio).map(p -> parseFile(p, audio.getFileName().toString())).orElse(ListJson.Result.NONE);
    }

    public static ListJson.Result parseFile(Path sidecar, String audioName) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(sidecar);
        } catch (IOException | RuntimeException e) {
            return new ListJson.Result(List.of(), List.of(), "cannot read file: " + e.getMessage(),
                    ListJson.hash(new byte[0]));
        }
        return parse(bytes, audioName);
    }

    /** Parses sidecar text for the audio file {@code audioName}; entries about other files are dropped. */
    public static ListJson.Result parse(byte[] bytes, String audioName) {
        ListJson.Result raw = ListJson.parse(bytes, audioName);
        String own = ListJson.key(audioName);
        List<ListJson.Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>(raw.warnings());
        for (ListJson.Entry e : raw.entries()) {
            String k = ListJson.key(e.filename());
            if (k.equals(own)) {
                entries.add(new ListJson.Entry(audioName, e.author(), e.partOf(), e.title(), e.transcriber(),
                        e.index(), e.start()));
            } else if (k.startsWith(own + "#") && k.substring(own.length() + 1).matches("\\d+")) {
                entries.add(e);   // the legacy "file.flac#2" spelling of file + index
            } else {
                warnings.add("entry for '" + e.filename() + "' ignored: this file describes only " + audioName);
            }
        }
        return new ListJson.Result(entries, warnings, raw.error(), raw.hash());
    }
}
