package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** The HTTP access shared by the online providers: one GET that returns the body, retrying transient errors. */
public final class LyricsHttp {

    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final int ATTEMPTS = 3;
    private static final long RETRY_PAUSE_MILLIS = 1000;

    private LyricsHttp() {
    }

    /** Fetches a URL's body; replaceable in tests. A 404 is reported as {@link NotFound}. */
    public interface Get {
        String get(URI uri) throws IOException;
    }

    /** A 404 from the service: nothing found, not a failure. */
    public static final class NotFound extends IOException {
        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    /**
     * The real thing. The public services have hiccups (a 503 on one endpoint while another works): a transient
     * answer (429, 500, 502, 503, 504) is retried a couple of times with a growing pause before it is a failure.
     */
    public static Get real(Duration timeout, String userAgent) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return uri -> {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", userAgent)
                    .header("Accept", "application/json, text/plain, */*").GET().build();
            try {
                for (int attempt = 1;; attempt++) {
                    HttpResponse<java.io.InputStream> r = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    int status = r.statusCode();
                    if (status == 404) {
                        r.body().close();
                        throw new NotFound("not found");
                    }
                    if (status != 200) {
                        r.body().close();
                        boolean transientError = status == 429 || status == 500 || status == 502 || status == 503
                                || status == 504;
                        if (transientError && attempt < ATTEMPTS) {
                            Thread.sleep(RETRY_PAUSE_MILLIS * attempt);
                            continue;
                        }
                        throw new IOException("lyrics service answered " + status
                                + (transientError ? " (temporarily unavailable, try again in a moment)" : ""));
                    }
                    try (java.io.InputStream in = r.body()) {
                        byte[] bytes = in.readNBytes(MAX_BODY + 1);
                        if (bytes.length > MAX_BODY) {
                            throw new IOException("lyrics answer too large");
                        }
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
