package org.kendar.dlna.clients;

import java.util.List;

/** The profile table is invalid; the message lists every problem. */
public final class InvalidProfilesException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> errors;

    public InvalidProfilesException(List<String> errors) {
        super("Invalid client profiles: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
