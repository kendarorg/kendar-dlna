package org.kendar.dlna.clients;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** What identifies a renderer on a request: remote address and headers (names case-insensitive). */
public record ClientRequest(String remoteIp, Map<String, List<String>> headers) {

    public ClientRequest {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(k, List.copyOf(v));
                }
            });
        }
        headers = Collections.unmodifiableMap(copy);
        remoteIp = remoteIp == null ? "" : remoteIp.strip();
    }

    public static ClientRequest of(String ip, String userAgent) {
        return new ClientRequest(ip, userAgent == null || userAgent.isBlank() ? Map.of()
                : Map.of("User-Agent", List.of(userAgent)));
    }

    public Optional<String> header(String name) {
        List<String> v = headers.get(name);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
    }

    public List<String> headerValues(String name) {
        return headers.getOrDefault(name, List.of());
    }
}
