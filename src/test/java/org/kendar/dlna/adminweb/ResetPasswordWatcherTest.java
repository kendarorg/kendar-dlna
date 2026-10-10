package org.kendar.dlna.adminweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.PasswordHasher;
import org.kendar.dlna.identity.SessionStore;
import org.kendar.dlna.testutil.MutableClock;

class ResetPasswordWatcherTest {

    @TempDir
    Path tmp;
    final MutableClock clock = new MutableClock();
    final List<String> announced = new ArrayList<>();

    private IdentityService identity() {
        IdentityService identity = new IdentityService(tmp.resolve("users"), List.of("main"), null,
                new PasswordHasher(1000, new SecureRandom()), new SessionStore(clock), clock, new SecureRandom(),
                announced::add);
        identity.bootstrap(Optional.empty());
        return identity;
    }

    private String password(int index) {
        return announced.get(index).lines().filter(l -> l.trim().startsWith("password:")).findFirst().orElseThrow()
                .replaceFirst(".*password:\\s*", "").trim();
    }

    @Test
    void resetFileIsDeletedThenThePasswordIsReset() throws IOException {
        Path config = Files.createDirectory(tmp.resolve("config"));
        IdentityService id = identity();
        ResetPasswordWatcher watcher = new ResetPasswordWatcher(config, id);
        assertThat(watcher.check()).isFalse();   // no file: nothing happens
        assertThat(announced).hasSize(1);

        Files.createFile(config.resolve("reset-password"));
        assertThat(watcher.check()).isTrue();

        assertThat(config.resolve("reset-password")).doesNotExist();
        assertThat(announced).hasSize(2);
        assertThat(password(1)).isNotEqualTo(password(0));
        assertThat(id.authenticate("admin", password(1))).isPresent();
        assertThat(id.userByName("admin").orElseThrow().mustChange()).isTrue();
        assertThat(watcher.check()).as("the file is gone: no second reset").isFalse();
        assertThat(announced).hasSize(2);
    }

    @Test
    void noResetWhenTheFileCannotBeDeleted() throws IOException {
        Path config = Files.createDirectory(tmp.resolve("config"));
        IdentityService id = identity();
        id.changePassword(id.userByName("admin").orElseThrow().id(), password(0), "my-chosen-password", null);
        // a non-empty directory of that name cannot be deleted with Files.delete
        Path trigger = Files.createDirectory(config.resolve("reset-password"));
        Files.createFile(trigger.resolve("child"));

        assertThat(new ResetPasswordWatcher(config, id).check()).isFalse();

        assertThat(announced).hasSize(1);
        assertThat(id.userByName("admin").orElseThrow().mustChange()).isFalse();
        assertThat(id.authenticate("admin", "my-chosen-password")).isPresent();
    }
}
