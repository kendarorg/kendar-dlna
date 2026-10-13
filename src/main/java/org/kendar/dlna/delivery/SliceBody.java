package org.kendar.dlna.delivery;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.kendar.dlna.transcoding.Slices;

/** A virtual track served as a cut of the original file: generated head bytes, then a byte range of the file. */
final class SliceBody implements StreamBody {

    private final Path path;
    private final Slices.Slice slice;
    private final String mimeType;
    private final String features;

    SliceBody(Path path, Slices.Slice slice, String mimeType, String features) {
        this.path = path;
        this.slice = slice;
        this.mimeType = mimeType;
        this.features = features;
    }

    @Override
    public long length() {
        return slice.total();
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public String contentFeatures() {
        return features;
    }

    @Override
    public void writeTo(long start, long len, OutputStream out) throws IOException {
        long headLen = slice.head().length;
        long left = len;
        long pos = start;
        if (pos < headLen && left > 0) {
            int n = (int) Math.min(headLen - pos, left);
            out.write(slice.head(), (int) pos, n);
            pos += n;
            left -= n;
        }
        if (left > 0) {
            FileSender.copy(path, slice.offset() + (pos - headLen), left, out);
        }
    }
}
