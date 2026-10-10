package org.kendar.dlna.delivery;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.kendar.dlna.catalog.Format;

/**
 * What to answer for a stream request: status, headers, and the byte window of the file to send.
 * {@code length} is the exact Content-Length of the response.
 */
public record StreamPlan(int status, Map<String, String> headers, long start, long length, boolean sendBody) {

    /** Plans the response to GET/HEAD with an optional Range header over a file of {@code fileSize} bytes. */
    public static StreamPlan of(String method, String rangeHeader, long fileSize, Format format) {
        return of(method, rangeHeader, fileSize, MediaTypes.mimeType(format), MediaTypes.contentFeatures(format));
    }

    /** Same for any body with its own content type and DLNA features (transcoded output). */
    public static StreamPlan of(String method, String rangeHeader, long fileSize, String mimeType, String features) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        Map<String, String> h = new LinkedHashMap<>();
        if (!m.equals("GET") && !m.equals("HEAD")) {
            h.put("Allow", "GET, HEAD");
            return new StreamPlan(405, h, 0, 0, false);
        }
        boolean body = m.equals("GET");
        h.put("Content-Type", mimeType);
        h.put("Accept-Ranges", "bytes");
        h.put("transferMode.dlna.org", "Streaming");
        h.put("contentFeatures.dlna.org", features);

        long[] range = parseRange(rangeHeader, fileSize);
        if (range == UNSATISFIABLE) {
            h.put("Content-Range", "bytes */" + fileSize);
            h.put("Content-Length", "0");
            return new StreamPlan(416, h, 0, 0, false);
        }
        if (range == null) {
            h.put("Content-Length", Long.toString(fileSize));
            return new StreamPlan(200, h, 0, fileSize, body);
        }
        long len = range[1] - range[0] + 1;
        h.put("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + fileSize);
        h.put("Content-Length", Long.toString(len));
        return new StreamPlan(206, h, range[0], len, body);
    }

    private static final long[] UNSATISFIABLE = new long[0];

    /**
     * @return {@code null} for no (or ignorable: malformed, multi-range) range, {@link #UNSATISFIABLE},
     *         or {@code [first, last]} inclusive
     */
    private static long[] parseRange(String header, long size) {
        if (header == null) {
            return null;
        }
        String v = header.strip();
        if (!v.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }
        String spec = v.substring(6).strip();
        if (spec.contains(",")) {
            return null;   // multi-range: answer with the full entity
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String a = spec.substring(0, dash).strip();
        String b = spec.substring(dash + 1).strip();
        try {
            if (a.isEmpty()) {              // suffix: last N bytes
                if (b.isEmpty()) {
                    return null;
                }
                long n = Long.parseLong(b);
                if (n <= 0 || size == 0) {
                    return UNSATISFIABLE;
                }
                return new long[] { Math.max(0, size - n), size - 1 };
            }
            long first = Long.parseLong(a);
            long last = b.isEmpty() ? size - 1 : Long.parseLong(b);
            if (first < 0 || (!b.isEmpty() && last < first)) {
                return null;                // invalid syntax: ignore
            }
            if (first >= size) {
                return UNSATISFIABLE;
            }
            return new long[] { first, Math.min(last, size - 1) };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
