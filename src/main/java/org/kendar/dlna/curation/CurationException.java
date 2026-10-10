package org.kendar.dlna.curation;

/** A rejected or failed edit; {@code code} is machine readable, {@code details} (nullable) is JSON-serializable. */
public final class CurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String NOT_FOUND = "not_found";
    public static final String INVALID = "invalid";
    public static final String NOT_EDITABLE = "not_editable";
    public static final String CONFLICT = "conflict";
    public static final String IO_ERROR = "io_error";

    private final String code;
    private final transient Object details;

    public CurationException(String code, String message) {
        this(code, message, null, null);
    }

    public CurationException(String code, String message, Object details) {
        this(code, message, details, null);
    }

    public CurationException(String code, String message, Object details, Throwable cause) {
        super(message, cause);
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
