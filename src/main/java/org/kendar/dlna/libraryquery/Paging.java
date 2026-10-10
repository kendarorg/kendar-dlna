package org.kendar.dlna.libraryquery;

import java.util.List;
import java.util.Map;

/** {@code offset}/{@code limit} query parameters; the limit is capped at {@link #MAX_LIMIT}. */
record Paging(int offset, int limit) {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    static Paging of(Map<String, String> q) {
        return of(q, DEFAULT_LIMIT);
    }

    static Paging of(Map<String, String> q, int defaultLimit) {
        int offset = number(q, "offset", 0);
        int limit = number(q, "limit", defaultLimit);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return new Paging(offset, Math.min(limit, MAX_LIMIT));
    }

    private static int number(Map<String, String> q, String name, int dflt) {
        String v = q.get(name);
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    <T> List<T> slice(List<T> all) {
        int from = Math.min(offset, all.size());
        return all.subList(from, Math.min(all.size(), from + limit));
    }
}
