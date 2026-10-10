package org.kendar.dlna.personal;

/**
 * A personal-data rule violation. Codes: {@code not_found}, {@code invalid}, {@code conflict} (body version stale,
 * 409), {@code precondition_failed} (If-Match stale, 412), {@code rate_limited} (429, details carry
 * {@code retryAfterSeconds}), {@code too_many}.
 */
public final class PersonalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Object details;

    public PersonalException(String code, String message) {
        this(code, message, null);
    }

    public PersonalException(String code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
