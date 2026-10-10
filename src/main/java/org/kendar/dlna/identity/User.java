package org.kendar.dlna.identity;

import java.time.Instant;
import java.util.List;

import org.kendar.dlna.shared.RootRef;

/**
 * A stored account. {@code passwordHash} is null while the user has not accepted the invite yet (no login
 * possible). {@code allowedRoots} is ignored for admins, who see every root. {@code lastLogin} may be null.
 */
public record User(String id, String username, String displayName, Role role, String passwordHash,
        boolean mustChange, List<String> allowedRoots, boolean downloadAllowed, boolean enabled, Instant createdAt,
        Instant lastLogin) {

    public User {
        allowedRoots = allowedRoots == null ? List.of() : List.copyOf(allowedRoots);
        displayName = displayName == null || displayName.isBlank() ? username : displayName;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    /** True when the user may see content of this library root (admins: every root). */
    public boolean mayAccess(String root) {
        return isAdmin() || allowedRoots.contains(root);
    }

    public boolean mayAccess(RootRef ref) {
        return mayAccess(ref.root());
    }

    /** Download of original files: admins always, users with the permission. */
    public boolean mayDownload() {
        return isAdmin() || downloadAllowed;
    }

    public User withPassword(String hash, boolean mustChange) {
        return new User(id, username, displayName, role, hash, mustChange, allowedRoots, downloadAllowed, enabled,
                createdAt, lastLogin);
    }

    public User withProfile(String displayName, Role role, List<String> allowedRoots, boolean downloadAllowed,
            boolean enabled) {
        return new User(id, username, displayName, role, passwordHash, mustChange, allowedRoots, downloadAllowed,
                enabled, createdAt, lastLogin);
    }

    public User withLastLogin(Instant at) {
        return new User(id, username, displayName, role, passwordHash, mustChange, allowedRoots, downloadAllowed,
                enabled, createdAt, at);
    }

    public User withAllowedRoots(List<String> roots) {
        return new User(id, username, displayName, role, passwordHash, mustChange, roots, downloadAllowed, enabled,
                createdAt, lastLogin);
    }
}
