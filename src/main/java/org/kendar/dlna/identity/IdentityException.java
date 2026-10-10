package org.kendar.dlna.identity;

/**
 * A refused identity operation. {@code code} is a stable machine code ({@code wrong_password}, {@code weak_password},
 * {@code invalid_invite}, {@code forbidden}, {@code forbidden_root}, {@code user_exists}, {@code not_found},
 * {@code invalid}, {@code last_admin}, {@code self_action}).
 */
public final class IdentityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public IdentityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
