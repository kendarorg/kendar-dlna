package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.kendar.dlna.identity.IdentityService;

/**
 * The lost-password trigger: an (empty) file in the config directory. {@link #check()} runs at startup and about
 * every 30 s. The file is deleted first; when that fails an error is logged and nothing is reset (otherwise the
 * password would be reset again and again).
 */
public final class ResetPasswordWatcher {

    public static final String FILE_NAME = "reset-password";
    public static final long INTERVAL_SECONDS = 30;

    private static final Logger LOG = Logger.getLogger(ResetPasswordWatcher.class.getName());

    private final Path file;
    private final IdentityService identity;

    public ResetPasswordWatcher(Path configDir, IdentityService identity) {
        this.file = configDir.resolve(FILE_NAME);
        this.identity = identity;
    }

    /** @return true when a reset was performed */
    public boolean check() {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            try {
                Files.delete(file);
            } catch (IOException e) {
                LOG.severe("Password reset requested by " + file + " but the file cannot be deleted (" + e
                        + "): no reset performed. Fix the permissions of the config directory.");
                return false;
            }
            LOG.warning("Password reset requested by " + file);
            identity.resetAdminPassword();
            return true;
        } catch (RuntimeException e) {
            LOG.severe("Password reset failed: " + e);
            return false;
        }
    }
}
