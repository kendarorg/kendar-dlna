package org.kendar.dlna.delivery;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies a byte window of a file to an output stream. */
public final class FileSender {

    private static final int BUFFER = 64 * 1024;

    private FileSender() {
    }

    /**
     * Sends exactly {@code length} bytes starting at {@code start}.
     *
     * @throws IOException on read/write failure, or when the file ends early (so the connection is aborted
     *                     instead of delivering a short body)
     */
    public static void copy(Path file, long start, long length, OutputStream out) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            ch.position(start);
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(BUFFER, Math.max(1, length)));
            long remaining = length;
            while (remaining > 0) {
                buf.clear();
                buf.limit((int) Math.min(buf.capacity(), remaining));
                int n = ch.read(buf);
                if (n < 0) {
                    throw new IOException("File shorter than expected: " + file);
                }
                out.write(buf.array(), 0, n);
                remaining -= n;
            }
        }
    }
}
