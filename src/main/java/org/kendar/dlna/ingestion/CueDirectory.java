package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.shared.Normalizer;

/** The cue sheets of one directory, each FILE entry matched to the audio file of the directory it names. */
public final class CueDirectory {

    private static final Logger LOG = Logger.getLogger(CueDirectory.class.getName());

    /** A cue sheet FILE entry matched to an audio file of the directory. */
    public record Match(String cueName, CueSheet sheet, CueSheet.CueFile file, String hash) {
    }

    private CueDirectory() {
    }

    /** Cue sheets of the directory, keyed like list.json names, each matched to an audio file it names. */
    public static Map<String, Match> read(Path dir) {
        Map<String, Match> out = new java.util.HashMap<>();
        List<Path> cues = new ArrayList<>();
        Map<String, String> audio = new java.util.TreeMap<>();   // key -> real name
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(".") || !Files.isRegularFile(p)) {
                    continue;
                }
                if (n.toLowerCase(Locale.ROOT).endsWith(".cue")) {
                    cues.add(p);
                } else if (Format.fromFileName(n).isPresent()) {
                    audio.put(ListJson.key(n), n);
                }
            }
        } catch (IOException e) {
            return out;
        }
        if (cues.isEmpty()) {
            return out;
        }
        cues.sort(java.util.Comparator.comparing(p -> p.getFileName().toString(), Normalizer.NATURAL));
        for (Path cue : cues) {
            String cueName = cue.getFileName().toString();
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(cue);
            } catch (IOException | RuntimeException e) {
                LOG.warning("Cannot read " + cue + ": " + e);
                continue;
            }
            CueSheet sheet = CueParser.parse(bytes);
            for (String w : sheet.warnings()) {
                LOG.warning(cueName + ": " + w);
            }
            String hash = ListJson.hash(bytes);
            String cueBase = cueName.substring(0, cueName.length() - 4);
            for (CueSheet.CueFile f : sheet.files()) {
                String real = locate(f.name(), cueBase, sheet.files().size() == 1, audio);
                if (real == null) {
                    LOG.warning(cueName + ": audio file '" + f.name() + "' not found in the directory");
                    continue;
                }
                if (out.putIfAbsent(ListJson.key(real), new Match(cueName, sheet, f, hash)) != null) {
                    LOG.warning(cueName + ": " + real + " is already described by another cue sheet, ignored");
                }
            }
        }
        return out;
    }

    private static String locate(String name, String cueBase, boolean only, Map<String, String> audio) {
        if (name != null) {
            String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
            String exact = audio.get(ListJson.key(base));
            if (exact != null) {
                return exact;
            }
            String stem = stem(ListJson.key(base));
            for (Map.Entry<String, String> e : audio.entrySet()) {
                if (stem(e.getKey()).equals(stem)) {
                    return e.getValue();   // the cue names .wav, the file is .flac
                }
            }
        }
        if (name == null || only) {
            String stem = ListJson.key(cueBase);
            for (Map.Entry<String, String> e : audio.entrySet()) {
                if (stem(e.getKey()).equals(stem)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String stem(String key) {
        int dot = key.lastIndexOf('.');
        return dot > 0 ? key.substring(0, dot) : key;
    }

}
