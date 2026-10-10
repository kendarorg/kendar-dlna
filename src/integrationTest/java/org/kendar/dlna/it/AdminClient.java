package org.kendar.dlna.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal client of the admin REST API: session cookie and CSRF token handling. */
final class AdminClient {

    record Reply(int status, String body, Map<String, java.util.List<String>> headers) {
        JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("Not JSON: " + body, e);
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private String csrf;
    private String cookie;

    AdminClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** POST /api/v1/auth/session; stores the CSRF token of the new session. */
    Reply login(String user, String password) {
        Reply r = send("POST", "/api/v1/auth/login", MAPPER.createObjectNode().put("username", user)
                .put("password", password).toString(), false);
        if (r.status() == 200) {
            csrf = r.json().path("csrfToken").asText(null);
            r.headers().getOrDefault("set-cookie", java.util.List.of()).stream()
                    .filter(c -> c.startsWith("kd_session=")).findFirst()
                    .ifPresent(c -> cookie = c.substring(0, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')));
        }
        return r;
    }

    Reply changePassword(String current, String next) {
        return post("/api/v1/me/password",
                MAPPER.createObjectNode().put("currentPassword", current).put("newPassword", next).toString());
    }

    Reply get(String path) {
        return send("GET", path, null, true);
    }

    Reply post(String path, String json) {
        return send("POST", path, json, true);
    }

    Reply put(String path, String json) {
        return send("PUT", path, json, true);
    }

    Reply delete(String path) {
        return send("DELETE", path, null, true);
    }

    /** A write carrying an Origin header (as a browser on another site would send). */
    Reply postWithOrigin(String path, String json, String origin) {
        return send("POST", path, json, true, origin);
    }

    Reply logout() {
        return send("POST", "/api/v1/auth/logout", "{}", true);
    }

    /** Sends without the session cookie handling of this client's jar being bypassed, but without the CSRF header. */
    Reply postWithoutCsrf(String path, String json) {
        return send("POST", path, json, false);
    }

    private Reply send(String method, String path, String body, boolean csrfHeader) {
        return send(method, path, body, csrfHeader, null);
    }

    private Reply send(String method, String path, String body, boolean csrfHeader, String origin) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60))
                .header("X-Requested-With", "kendar-it").header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        if (csrfHeader && csrf != null) {
            b.header("X-CSRF-Token", csrf);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body(), r.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
