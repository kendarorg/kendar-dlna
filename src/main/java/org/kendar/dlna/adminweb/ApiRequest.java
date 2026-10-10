package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An API request without any servlet/Jetty type. {@code path} is relative to the API mount (e.g.
 * {@code /folders/contents}); header names are lower case.
 */
public record ApiRequest(String method, String path, Map<String, String> query, Map<String, String> headers,
        byte[] body, String remoteAddr, boolean secure) {

    public ApiRequest {
        method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        path = path == null || path.isEmpty() ? "/" : path;
        query = query == null ? Map.of() : Map.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
        remoteAddr = remoteAddr == null ? "unknown" : remoteAddr;
    }

    public static ApiRequest of(String method, String path) {
        return new ApiRequest(method, path, null, null, null, "127.0.0.1", false);
    }

    public ApiRequest withQuery(String key, String value) {
        Map<String, String> q = new LinkedHashMap<>(query);
        q.put(key, value);
        return new ApiRequest(method, path, q, headers, body, remoteAddr, secure);
    }

    public ApiRequest withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name.toLowerCase(Locale.ROOT), value);
        return new ApiRequest(method, path, query, h, body, remoteAddr, secure);
    }

    public ApiRequest withBody(byte[] newBody) {
        return new ApiRequest(method, path, query, headers, newBody, remoteAddr, secure);
    }

    public ApiRequest withJson(Object value) {
        try {
            return withBody(Json.mapper().writeValueAsBytes(value)).withHeader("content-type", "application/json");
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public ApiRequest from(String ip) {
        return new ApiRequest(method, path, query, headers, body, ip, secure);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean isRead() {
        return method.equals("GET") || method.equals("HEAD");
    }

    /** Value of a cookie of the {@code Cookie} header, or null. */
    public String cookie(String name) {
        String h = header("cookie");
        if (h == null) {
            return null;
        }
        for (String part : h.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).strip().equals(name)) {
                return part.substring(eq + 1).strip();
            }
        }
        return null;
    }

    /** A required query parameter. */
    public String required(String name) {
        String v = query.get(name);
        if (v == null || v.isEmpty()) {
            throw new ApiException(400, "bad_request", "Missing query parameter: " + name);
        }
        return v;
    }

    /** The request body as JSON; 400 when missing or malformed. */
    public JsonNode json() {
        if (body.length == 0) {
            throw new ApiException(400, "bad_json", "A JSON body is required");
        }
        try {
            JsonNode n = Json.mapper().readTree(body);
            if (n == null || n.isMissingNode()) {
                throw new ApiException(400, "bad_json", "A JSON body is required");
            }
            return n;
        } catch (IOException e) {
            throw new ApiException(400, "bad_json", "The body is not valid JSON");
        }
    }

    /** Parses {@code a=1&b=x%20y}; later duplicates win. */
    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }
}
