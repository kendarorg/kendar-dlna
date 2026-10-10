package org.kendar.dlna.personal;

/**
 * The version a client based its edit on. {@code fromHeader} (If-Match) turns a mismatch into
 * {@code precondition_failed} (412), a version in the body into {@code conflict} (409). No version = no check.
 */
public record Precondition(Long version, boolean fromHeader) {

    public static final Precondition NONE = new Precondition(null, false);

    /** Parses an If-Match value such as {@code "3"} or {@code W/"3"}; unparsable values never match. */
    public static Precondition ifMatch(String header) {
        if (header == null || header.isBlank()) {
            return NONE;
        }
        String s = header.strip();
        if (s.startsWith("W/")) {
            s = s.substring(2);
        }
        s = s.replace("\"", "");
        try {
            return new Precondition(Long.parseLong(s), true);
        } catch (NumberFormatException e) {
            return new Precondition(-1L, true);
        }
    }

    public static Precondition body(Long version) {
        return version == null ? NONE : new Precondition(version, false);
    }

    void check(long current) {
        if (version != null && version != current) {
            String code = fromHeader ? "precondition_failed" : "conflict";
            throw new PersonalException(code, "The resource changed (current version " + current + ")",
                    java.util.Map.of("currentVersion", current));
        }
    }
}
