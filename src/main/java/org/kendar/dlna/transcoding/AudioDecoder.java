package org.kendar.dlna.transcoding;

import java.io.IOException;

/**
 * Pull decoder delivering interleaved <b>stereo signed 16-bit little-endian</b> PCM at the source sample rate
 * (mono is duplicated, extra channels dropped, 24-bit reduced to 16-bit). The number of sample frames is exactly
 * {@link #totalSamples()} from every start position: shorter streams are padded with silence, longer ones cut.
 * Not thread-safe.
 */
public interface AudioDecoder extends AutoCloseable {

    int sampleRate();

    /** Exact number of sample frames (per channel) the decoder yields from position 0. */
    long totalSamples();

    /** Positions on the sample timeline (0 .. totalSamples); sample accurate. */
    void seek(long frame) throws IOException;

    /**
     * Reads up to {@code len} bytes (a multiple of 4 is always returned) into {@code buf}.
     *
     * @return the number of bytes, or -1 at the end of the stream
     */
    int read(byte[] buf, int off, int len) throws IOException;

    @Override
    void close() throws IOException;
}
