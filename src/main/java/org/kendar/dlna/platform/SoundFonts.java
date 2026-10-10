package org.kendar.dlna.platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Finds the SoundFont for MIDI rendering: the configured {@code transcode.soundFontPath}, else the bundled
 * {@code /soundfont/GeneralUser-GS.sf2} (extracted once into the data directory because the synthesizer reads a
 * file), else none, in which case MIDI is unavailable.
 */
final class SoundFonts {

    static final String BUNDLED_RESOURCE = "/soundfont/GeneralUser-GS.sf2";
    private static final Logger LOG = Logger.getLogger(SoundFonts.class.getName());

    private SoundFonts() {
    }

    /** A located SoundFont and a stamp identifying its version (part of the MIDI cache keys). */
    record Located(Path file, String stamp) {
    }

    static Optional<Located> locate(String configured, Path dataDir) {
        return locate(configured, dataDir, SoundFonts.class.getResource(BUNDLED_RESOURCE));
    }

    static Optional<Located> locate(String configured, Path dataDir, URL bundled) {
        if (configured != null) {
            Path p = Path.of(configured);
            if (Files.isRegularFile(p)) {
                return Optional.of(stamped(p));
            }
            LOG.warning("transcode.soundFontPath does not point to a file: " + p
                    + (bundled != null ? " (using the bundled SoundFont)" : ""));
        }
        if (bundled == null) {
            return Optional.empty();
        }
        try {
            Path target = dataDir.resolve("soundfont").resolve("GeneralUser-GS.sf2");
            URLConnection c = bundled.openConnection();
            try (InputStream in = c.getInputStream()) {   // always closed: a file URL keeps the file open
                long size = c.getContentLengthLong();
                if (!Files.isRegularFile(target) || size <= 0 || Files.size(target) != size) {
                    Files.createDirectories(target.getParent());
                    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("Extracted the bundled SoundFont to " + target);
                }
            }
            return Optional.of(stamped(target));
        } catch (IOException e) {
            LOG.warning("Cannot extract the bundled SoundFont: " + e);
            return Optional.empty();
        }
    }

    private static Located stamped(Path p) {
        try {
            return new Located(p, p.toAbsolutePath() + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis());
        } catch (IOException e) {
            return new Located(p, p.toAbsolutePath().toString());
        }
    }
}
