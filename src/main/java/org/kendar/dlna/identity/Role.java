package org.kendar.dlna.identity;

import java.util.Locale;

/** Account class: admin does everything, user is a registered listener (plan section 3). */
public enum Role {
    ADMIN, USER;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role parse(String s) {
        if (s != null) {
            for (Role r : values()) {
                if (r.wire().equals(s.strip().toLowerCase(Locale.ROOT))) {
                    return r;
                }
            }
        }
        throw new IdentityException("invalid", "Unknown role: " + s + " (admin or user)");
    }
}
