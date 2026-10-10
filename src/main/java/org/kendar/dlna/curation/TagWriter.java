package org.kendar.dlna.curation;

import java.nio.file.Path;

import org.kendar.dlna.catalog.Format;

/** Port: writes tags into an audio file in place (the caller makes the write safe). */
public interface TagWriter {

    /** @throws TagWriteException when the file cannot be written; the file may be left half-written */
    void write(Path file, Format format, TagEdit edit) throws TagWriteException;

    class TagWriteException extends Exception {
        private static final long serialVersionUID = 1L;

        public TagWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
