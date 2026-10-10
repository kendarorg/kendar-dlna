package org.kendar.dlna.identity;

import org.kendar.dlna.shared.RootRef;

/**
 * The authorization checks, in one place: later milestones (library query, stream, personal) call these and
 * never compare roles or roots themselves. Violations are {@link IdentityException}s with code {@code forbidden}
 * or {@code forbidden_root} (the REST adapter answers 403).
 */
public final class Access {

    private Access() {
    }

    public static void requireRole(User user, Role required) {
        if (required == Role.ADMIN && !user.isAdmin()) {
            throw new IdentityException("forbidden", "This action needs an administrator");
        }
    }

    public static void requireRoot(User user, String root) {
        if (!user.mayAccess(root)) {
            throw new IdentityException("forbidden_root", "No access to this library root");
        }
    }

    public static void requireRoot(User user, RootRef ref) {
        requireRoot(user, ref.root());
    }

    public static void requireDownload(User user) {
        if (!user.mayDownload()) {
            throw new IdentityException("forbidden", "Downloading original files is not permitted for this account");
        }
    }
}
