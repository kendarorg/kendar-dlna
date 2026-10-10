package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything that gives per-file metadata in one directory: its {@code list.json} and the per-file sidecars
 * ({@link SidecarJson}). {@link #forFile} is the list to use for one audio file (sidecar first, else the directory
 * list.json) and {@link #hash} changes whenever any of them changes, created or deleted.
 */
public final class DirectoryLists {

    /** A sidecar found next to an audio file of the directory. */
    public record Side(String fileName, String sidecarName, ListJson.Result result) {
    }

    private final ListJson.Result directory;
    private final Map<String, Side> sidecars;

    private DirectoryLists(ListJson.Result directory, Map<String, Side> sidecars) {
        this.directory = directory;
        this.sidecars = sidecars;
    }

    public static DirectoryLists read(Path dir) {
        Map<String, String> names = new HashMap<>();
        List<String> candidates = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String n = p.getFileName().toString();
                names.put(ListJson.key(n), n);
                if (SidecarJson.isSidecarName(n)) {
                    candidates.add(n);
                }
            }
        } catch (IOException e) {
            return new DirectoryLists(ListJson.forDirectory(dir), Map.of());
        }
        Map<String, Side> found = new TreeMap<>();
        for (String c : candidates) {
            String audio = names.get(ListJson.key(c.substring(0, c.length() - SidecarJson.SUFFIX.length())));
            if (audio == null) {
                continue;   // a stray x.flac.json without x.flac
            }
            found.put(ListJson.key(audio), new Side(audio, c, SidecarJson.parseFile(dir.resolve(c), audio)));
        }
        return new DirectoryLists(ListJson.forDirectory(dir), found);
    }

    /** The directory's own list.json (never the sidecars). */
    public ListJson.Result directory() {
        return directory;
    }

    /** The sidecars by file key (files that have one, usable or not). */
    public Map<String, Side> sidecars() {
        return sidecars;
    }

    /** The sidecar of {@code fileName} when it has entries; an empty or unreadable one counts as none. */
    public ListJson.Result sidecar(String fileName) {
        Side s = sidecars.get(ListJson.key(fileName));
        return s != null && s.result().error() == null && !s.result().entries().isEmpty() ? s.result() : null;
    }

    /** The list to use for the file: its sidecar if usable, else the directory list.json. */
    public ListJson.Result forFile(String fileName) {
        ListJson.Result sc = sidecar(fileName);
        return sc != null ? sc : directory;
    }

    /** Names of the files that have a usable sidecar, in file name order. */
    public List<String> sidecarFiles() {
        List<String> out = new ArrayList<>();
        for (Side s : sidecars.values()) {
            if (sidecar(s.fileName()) != null) {
                out.add(s.fileName());
            }
        }
        out.sort(org.kendar.dlna.shared.Normalizer::compareNatural);
        return out;
    }

    /**
     * Identifies the content of all the lists (null = none at all). Equal to the directory list.json hash when
     * there are no sidecars, so directories without them behave as before.
     */
    public String hash() {
        if (sidecars.isEmpty()) {
            return directory.hash();
        }
        StringBuilder sb = new StringBuilder(String.valueOf(directory.hash()));
        sidecars.forEach((k, s) -> sb.append('\n').append(k).append(':').append(s.result().hash()));
        return ListJson.hash(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Problems worth a log line: the sidecar errors and warnings, prefixed with the sidecar name. */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        sidecars.values().forEach(s -> {
            if (s.result().error() != null) {
                out.add(s.sidecarName() + ": " + s.result().error());
            }
            s.result().warnings().forEach(w -> out.add(s.sidecarName() + ": " + w));
        });
        return out;
    }
}
