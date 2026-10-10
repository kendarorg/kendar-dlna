package org.kendar.dlna.ingestion;

import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

/** Extracts the embedded cover picture of an audio file with jaudiotagger. */
public final class EmbeddedArtExtractor {

    static {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    /** @return the first embedded image's bytes; empty when there is none or the file is unreadable */
    public Optional<byte[]> extract(Path file) {
        try {
            Tag tag = AudioFileIO.read(file.toFile()).getTag();
            if (tag == null) {
                return Optional.empty();
            }
            Artwork art = tag.getFirstArtwork();
            byte[] data = art == null ? null : art.getBinaryData();
            return data == null || data.length == 0 ? Optional.empty() : Optional.of(data);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
