package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.kendar.dlna.shared.Json;

/** An API response without any servlet/Jetty type. */
public record ApiResponse(int status, String contentType, byte[] body, Map<String, String> headers,
        List<String> setCookies, Streamed streamed) {

    /**
     * A body sent straight from a source (stream, download) instead of from memory: {@code length} bytes from
     * {@code start}; {@code done} is run exactly once when the sending ended (releases leases and limits).
     */
    public record Streamed(org.kendar.dlna.delivery.StreamBody body, long start, long length, Runnable done) {
    }

    public ApiResponse(int status, String contentType, byte[] body, Map<String, String> headers,
            List<String> setCookies) {
        this(status, contentType, body, headers, setCookies, null);
    }

    public ApiResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        setCookies = setCookies == null ? List.of() : List.copyOf(setCookies);
        body = body == null ? new byte[0] : body;
    }

    public static ApiResponse json(int status, Object value) {
        try {
            return new ApiResponse(status, "application/json; charset=utf-8", Json.mapper().writeValueAsBytes(value),
                    null, null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize response", e);
        }
    }

    public static ApiResponse ok(Object value) {
        return json(200, value);
    }

    public static ApiResponse noContent() {
        return new ApiResponse(204, null, null, null, null);
    }

    public static ApiResponse bytes(String contentType, byte[] body) {
        return new ApiResponse(200, contentType, body, null, null);
    }

    public static ApiResponse error(int status, String code, String message, Object details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("details", details);
        return json(status, m);
    }

    public ApiResponse withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new ApiResponse(status, contentType, body, h, setCookies, streamed);
    }

    public ApiResponse withCookie(String cookie) {
        List<String> c = new ArrayList<>(setCookies);
        c.add(cookie);
        return new ApiResponse(status, contentType, body, headers, c, streamed);
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public com.fasterxml.jackson.databind.JsonNode jsonBody() {
        try {
            return Json.mapper().readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
