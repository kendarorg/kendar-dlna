package org.kendar.dlna.libraryquery;

/** A failed library query: {@code not_found} (unknown location, track or group) or {@code bad_request}. */
public final class QueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public QueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    static QueryException notFound(String message) {
        return new QueryException("not_found", message);
    }
}
