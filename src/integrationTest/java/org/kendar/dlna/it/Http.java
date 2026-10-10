package org.kendar.dlna.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Plain HTTP helper for the stream, art and description checks. */
final class Http {

    /** Status, headers (lower-case names, first value) and body of a response. */
    record Response(int status, Map<String, java.util.List<String>> headers, byte[] body) {
        Optional<String> header(String name) {
            java.util.List<String> v = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
        }

        String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private Http() {
    }

    static Response head(String url, String... headers) {
        return send("HEAD", url, headers);
    }

    static Response get(String url, String... headers) {
        return send("GET", url, headers);
    }

    /** @param headers alternating name, value */
    static Response send(String method, String url, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<byte[]> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(r.statusCode(), r.headers().map(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + url + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
