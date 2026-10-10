package org.kendar.dlna.ingestion;

import java.nio.file.Path;

import org.kendar.dlna.catalog.Format;

/** Port: reads tags and technical properties from an audio file. */
public interface TagReader {

    /** Whether this reader can handle the format; unsupported files are recorded, not served. */
    boolean supports(Format format);

    /** Whether this reader is responsible for the format even when it cannot serve it right now. */
    default boolean owns(Format format) {
        return supports(format);
    }

    /** Why {@link #supports} is false for a format (shown in the unsupported-files list). */
    default String unsupportedReason(Format format) {
        return format + " is not supported yet";
    }

    TrackMeta read(Path file, Format format) throws TagReadException;

    /** The file could not be read as the expected format, or holds audio this server cannot play. */
    class TagReadException extends Exception {
        private static final long serialVersionUID = 1L;

        private final boolean unsupported;

        public TagReadException(String message, Throwable cause) {
            this(message, cause, false);
        }

        /** @param unsupported the file is fine but its content is not supported (message is the reason shown) */
        public TagReadException(String message, Throwable cause, boolean unsupported) {
            super(message, cause);
            this.unsupported = unsupported;
        }

        public boolean unsupported() {
            return unsupported;
        }
    }
}
