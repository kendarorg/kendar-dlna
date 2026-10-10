package org.kendar.dlna.delivery;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** What a stream response sends: a byte source of exactly known length, with its content type and DLNA features. */
public interface StreamBody {

    long length();

    String mimeType();

    /** The {@code contentFeatures.dlna.org} value. */
    String contentFeatures();

    /** Sends exactly {@code length} bytes starting at {@code start}; fails when the source is shorter. */
    void writeTo(long start, long length, OutputStream out) throws IOException;

    /** The backing file when the body is a plain file (direct or cached), else null. */
    default Path file() {
        return null;
    }

    /** A file served as it is. */
    record FileBody(Path path, long length, String mimeType, String contentFeatures) implements StreamBody {
        @Override
        public void writeTo(long start, long len, OutputStream out) throws IOException {
            FileSender.copy(path, start, len, out);
        }

        @Override
        public Path file() {
            return path;
        }
    }
}
