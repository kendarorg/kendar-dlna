package org.kendar.dlna.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.identity.IdentityService.LegacyAdmin;
import org.kendar.dlna.testutil.MutableClock;

class IdentityServiceTest {

    @TempDir
    Path tmp;
    final MutableClock clock = new MutableClock();
    final PasswordHasher hasher = new PasswordHasher(1000, new SecureRandom());
    final List<String> announced = new ArrayList<>();
    SessionStore sessions;
    IdentityService id;

    @BeforeEach
    void setUp() {
        sessions = new SessionStore(clock);
        id = create(sessions);
    }

    /** A service over the same files: a second one simulates a restart. */
    private IdentityService create(SessionStore s) {
        return new IdentityService(tmp.resolve("users"), List.of("Music", "Jazz", "Kids"), null, hasher, s, clock,
                new SecureRandom(), announced::add);
    }

    private String announcedPassword(int index) {
        return announced.get(index).lines().filter(l -> l.trim().startsWith("password:")).findFirst().orElseThrow()
                .replaceFirst(".*password:\\s*", "").trim();
    }

    private User admin() {
        return id.userByName("admin").orElseThrow();
    }

    private User addUser(String name) {
        User u = id.createUser(name, null, Role.USER, null, false);
        String token = id.invite(u.id()).token();
        return id.acceptInvite(token, "password-of-" + name);
    }

    // ---- bootstrap and migration ----

    @Test
    void firstStartupGeneratesARandomAdminPasswordAnnouncedOnceWithMustChange() throws IOException {
        id.bootstrap(Optional.empty());

        assertThat(announced).hasSize(1);
        assertThat(announced.get(0)).contains("KENDAR DLNA ADMIN PASSWORD").contains("user:     admin");
        String pw = announcedPassword(0);
        assertThat(pw).hasSize(20).matches("[A-Za-z0-9]+");
        User a = admin();
        assertThat(a.role()).isEqualTo(Role.ADMIN);
        assertThat(a.mustChange()).isTrue();
        assertThat(a.passwordHash()).startsWith("pbkdf2-sha256$").doesNotContain(pw);
        assertThat(Files.readString(tmp.resolve("users/users.json"))).doesNotContain(pw);
        assertThat(id.authenticate("admin", pw)).isPresent();
        assertThat(id.authenticate("admin", pw + "x")).isEmpty();
        assertThat(id.authenticate("root", pw)).isEmpty();
    }

    @Test
    void theLegacyConfigAdminIsMigratedWithItsHashAndFlag() {
        String hash = hasher.hash("existing-password");
        id.bootstrap(Optional.of(new LegacyAdmin("boss", hash, false)));

        assertThat(announced).isEmpty();
        User boss = id.userByName("boss").orElseThrow();
        assertThat(boss.role()).isEqualTo(Role.ADMIN);
        assertThat(boss.passwordHash()).isEqualTo(hash);
        assertThat(boss.mustChange()).isFalse();
        assertThat(boss.enabled()).isTrue();
        assertThat(id.authenticate("boss", "existing-password")).isPresent();
        assertThat(id.users()).hasSize(1);
    }

    @Test
    void aLegacyAdminWithoutHashGetsAPasswordAndKeepsItsUserName() {
        id.bootstrap(Optional.of(new LegacyAdmin("boss", null, false)));
        assertThat(announced).hasSize(1);
        assertThat(id.authenticate("boss", announcedPassword(0))).isPresent();
    }

    @Test
    void migrationHappensOnlyOnceAndSurvivesARestart() {
        id.bootstrap(Optional.of(new LegacyAdmin("boss", hasher.hash("first-password"), false)));
        String adminId = id.userByName("boss").orElseThrow().id();

        IdentityService restarted = create(new SessionStore(clock));
        restarted.bootstrap(Optional.of(new LegacyAdmin("other", hasher.hash("other-password"), true)));

        assertThat(restarted.users()).hasSize(1);
        assertThat(restarted.userByName("boss").orElseThrow().id()).isEqualTo(adminId);
        assertThat(restarted.userByName("other")).isEmpty();
        assertThat(restarted.authenticate("boss", "first-password")).isPresent();
        assertThat(announced).isEmpty();
    }

    // ---- passwords ----

    @Test
    void changePasswordNeedsTheCurrentOneAndAMinimumLength() {
        id.bootstrap(Optional.empty());
        String first = announcedPassword(0);
        String uid = admin().id();

        assertThatThrownBy(() -> id.changePassword(uid, "nope", "a-new-password-1", null))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("wrong_password"));
        assertThatThrownBy(() -> id.changePassword(uid, first, "short", null))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("weak_password"));
        assertThatThrownBy(() -> id.changePassword(uid, first, first, null))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("weak_password"));
        assertThat(admin().mustChange()).isTrue();

        id.changePassword(uid, first, "a-brand-new-password", null);

        assertThat(admin().mustChange()).isFalse();
        assertThat(id.authenticate("admin", first)).isEmpty();
        assertThat(id.authenticate("admin", "a-brand-new-password")).isPresent();
        assertThat(announced).hasSize(1);   // a chosen password is never logged
    }

    @Test
    void changePasswordEndsOtherSessionsAndRememberTokensOfThatUserOnly() {
        id.bootstrap(Optional.empty());
        User other = addUser("lena");
        var mine = sessions.create(admin().id());
        var second = sessions.create(admin().id());
        var others = sessions.create(other.id());
        String remember = id.issueRemember(admin());

        id.changePassword(admin().id(), announcedPassword(0), "a-brand-new-password", mine.token());

        assertThat(sessions.find(mine.token())).isPresent();
        assertThat(sessions.find(second.token())).isEmpty();
        assertThat(sessions.find(others.token())).isPresent();
        assertThat(id.refresh(remember)).isEmpty();
    }

    @Test
    void resetSetsMustChangeAnnouncesOnceAndEndsAllSessions() {
        id.bootstrap(Optional.empty());
        id.changePassword(admin().id(), announcedPassword(0), "my-chosen-password", null);
        var s = id.newSession(admin());
        String remember = id.issueRemember(admin());

        id.resetAdminPassword();

        assertThat(announced).hasSize(2);
        assertThat(admin().mustChange()).isTrue();
        assertThat(announcedPassword(1)).isNotEqualTo(announcedPassword(0));
        assertThat(id.authenticate("admin", "my-chosen-password")).isEmpty();
        assertThat(id.authenticate("admin", announcedPassword(1))).isPresent();
        assertThat(sessions.find(s.token())).isEmpty();
        assertThat(id.refresh(remember)).isEmpty();
    }

    // ---- persistence ----

    @Test
    void usersSurviveARestartAndWritesLeaveNoTempFiles() throws IOException {
        id.bootstrap(Optional.empty());
        User u = id.createUser("lena", "Lena L.", Role.USER, List.of("Jazz"), true);

        IdentityService restarted = create(new SessionStore(clock));
        User back = restarted.userByName("LENA").orElseThrow();   // case-insensitive
        assertThat(back.id()).isEqualTo(u.id());
        assertThat(back.displayName()).isEqualTo("Lena L.");
        assertThat(back.allowedRoots()).containsExactly("Jazz");
        assertThat(back.downloadAllowed()).isTrue();
        assertThat(back.enabled()).isTrue();
        assertThat(back.createdAt()).isEqualTo(clock.instant());
        assertThat(back.lastLogin()).isNull();
        assertThat(back.hasPassword()).isFalse();
        try (var files = Files.list(tmp.resolve("users"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("users.json");
        }
    }

    @Test
    void theLastLoginIsRecorded() {
        id.bootstrap(Optional.empty());
        clock.advance(Duration.ofHours(1));
        assertThat(id.authenticate("admin", announcedPassword(0))).isPresent();
        assertThat(create(sessions).userByName("admin").orElseThrow().lastLogin()).isEqualTo(clock.instant());
    }

    // ---- users, roots, permissions ----

    @Test
    void newUsersGetTheMainRootByDefaultAndAdminsSeeEverything() {
        id.bootstrap(Optional.empty());
        User u = id.createUser("lena", null, Role.USER, null, false);
        assertThat(u.allowedRoots()).containsExactly("Music");   // the first configured root
        assertThat(u.downloadAllowed()).isFalse();
        assertThat(u.displayName()).isEqualTo("lena");
        assertThat(u.mayAccess("Music")).isTrue();
        assertThat(u.mayAccess("Jazz")).isFalse();
        assertThat(u.mayDownload()).isFalse();
        assertThat(admin().mayAccess("Kids")).isTrue();
        assertThat(admin().mayDownload()).isTrue();

        IdentityService custom = new IdentityService(tmp.resolve("other"), List.of("Music", "Jazz"), "Jazz", hasher,
                sessions, clock, new SecureRandom(), announced::add);
        assertThat(custom.mainRoot()).isEqualTo("Jazz");
        assertThat(custom.createUser("kim", null, Role.USER, null, false).allowedRoots()).containsExactly("Jazz");
    }

    @Test
    void userNamesAndRootsAreValidated() {
        id.bootstrap(Optional.empty());
        assertThatThrownBy(() -> id.createUser("x", null, Role.USER, null, false))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("invalid"));
        assertThatThrownBy(() -> id.createUser("bad name", null, Role.USER, null, false))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> id.createUser("Admin", null, Role.USER, null, false))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("user_exists"));
        assertThatThrownBy(() -> id.createUser("lena", null, Role.USER, List.of("Nope"), false))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("invalid"));
    }

    @Test
    void removingARootFromTheConfigurationStripsItFromEveryUser() {
        id.bootstrap(Optional.empty());
        User a = id.createUser("lena", null, Role.USER, List.of("Music", "Jazz"), false);
        User b = id.createUser("kim", null, Role.USER, List.of("Kids"), false);

        assertThat(id.syncRoots(Set.of("Music", "Kids"))).isEqualTo(1);

        assertThat(id.user(a.id()).orElseThrow().allowedRoots()).containsExactly("Music");
        assertThat(id.user(b.id()).orElseThrow().allowedRoots()).containsExactly("Kids");
        assertThat(create(sessions).user(a.id()).orElseThrow().allowedRoots()).containsExactly("Music");   // persisted
        assertThat(id.syncRoots(Set.of("Music", "Kids"))).isZero();
    }

    @Test
    void disablingEndsSessionsAndBlocksLogin() {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        var s = id.newSession(u);
        String remember = id.issueRemember(u);

        id.updateUser(u.id(), null, null, null, null, false, admin().id());

        assertThat(sessions.find(s.token())).isEmpty();
        assertThat(id.authenticate("lena", "password-of-lena")).isEmpty();
        assertThat(id.refresh(remember)).isEmpty();
        assertThat(id.user(u.id()).orElseThrow().allowedRoots()).containsExactly("Music");   // data kept

        id.updateUser(u.id(), null, null, null, null, true, admin().id());
        assertThat(id.authenticate("lena", "password-of-lena")).isPresent();
    }

    @Test
    void aSessionOfADisabledOrDeletedUserStopsWorking() {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        var s = id.newSession(u);
        assertThat(id.userOf(s)).isPresent();
        id.deleteUser(u.id(), admin().id());
        assertThat(id.userOf(s)).isEmpty();
        assertThat(sessions.find(s.token())).isEmpty();
    }

    @Test
    void deletingAUserRemovesTheirDataFolderInvitesAndTokens() throws IOException {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        Path data = Files.createDirectories(id.dataDir(u.id()).resolve("playlists"));
        Files.writeString(data.resolve("p.json"), "{}");
        String remember = id.issueRemember(u);
        String invite = id.invite(u.id()).token();

        id.deleteUser(u.id(), admin().id());

        assertThat(id.user(u.id())).isEmpty();
        assertThat(id.dataDir(u.id())).doesNotExist();
        assertThat(id.refresh(remember)).isEmpty();
        assertThat(id.previewInvite(invite)).isEmpty();
        assertThat(id.userByName("lena")).isEmpty();
    }

    @Test
    void theLastActiveAdminAndYourselfAreProtected() {
        id.bootstrap(Optional.empty());
        String me = admin().id();
        assertThatThrownBy(() -> id.deleteUser(me, "someone-else"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("last_admin"));
        assertThatThrownBy(() -> id.updateUser(me, null, Role.USER, null, null, null, "someone-else"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("last_admin"));
        assertThatThrownBy(() -> id.updateUser(me, null, null, null, null, false, "someone-else"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("last_admin"));

        User second = id.createUser("two", null, Role.ADMIN, null, false);
        assertThatThrownBy(() -> id.deleteUser(me, me))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("self_action"));
        assertThatThrownBy(() -> id.updateUser(me, null, Role.USER, null, null, null, me))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("self_action"));
        id.deleteUser(second.id(), me);
        assertThat(id.users()).hasSize(1);
    }

    // ---- invites ----

    @Test
    void anInviteIsOneTimeAndSetsTheOwnPassword() {
        id.bootstrap(Optional.empty());
        User u = id.createUser("lena", null, Role.USER, null, false);
        assertThat(id.authenticate("lena", "anything")).as("no password yet").isEmpty();
        IdentityService.Invite invite = id.invite(u.id());
        assertThat(invite.token()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(invite.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(id.hasPendingInvite(u.id())).isTrue();
        assertThat(id.previewInvite(invite.token())).hasValueSatisfying(x -> assertThat(x.username()).isEqualTo("lena"));

        assertThatThrownBy(() -> id.acceptInvite(invite.token(), "short"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("weak_password"));
        assertThat(id.previewInvite(invite.token())).as("a weak password does not burn the invite").isPresent();

        User accepted = id.acceptInvite(invite.token(), "lena-chosen-password");
        assertThat(accepted.mustChange()).isFalse();
        assertThat(id.authenticate("lena", "lena-chosen-password")).isPresent();
        assertThat(id.hasPendingInvite(u.id())).isFalse();

        assertThatThrownBy(() -> id.acceptInvite(invite.token(), "another-chosen-password"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("invalid_invite"));
        assertThat(id.authenticate("lena", "another-chosen-password")).isEmpty();
    }

    @Test
    void anInviteExpiresAfterSevenDays() {
        id.bootstrap(Optional.empty());
        User u = id.createUser("lena", null, Role.USER, null, false);
        String token = id.invite(u.id()).token();

        clock.advance(Duration.ofDays(7).minusSeconds(1));
        assertThat(id.previewInvite(token)).isPresent();
        clock.advance(Duration.ofSeconds(2));
        assertThat(id.previewInvite(token)).isEmpty();
        assertThatThrownBy(() -> id.acceptInvite(token, "lena-chosen-password"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("invalid_invite"));
        assertThat(id.hasPendingInvite(u.id())).isFalse();
    }

    @Test
    void aNewInviteVoidsTheOldOneAndTokensAreNotStoredInClear() throws IOException {
        id.bootstrap(Optional.empty());
        User u = id.createUser("lena", null, Role.USER, null, false);
        String first = id.invite(u.id()).token();
        String second = id.invite(u.id()).token();

        assertThat(first).isNotEqualTo(second);
        assertThat(id.previewInvite(first)).isEmpty();
        assertThat(id.previewInvite(second)).isPresent();
        String stored = Files.readString(tmp.resolve("users/invites.json"));
        assertThat(stored).doesNotContain(second).contains(TokenStore.sha256(second));

        IdentityService restarted = create(new SessionStore(clock));
        assertThat(restarted.previewInvite(second)).as("invites survive a restart").isPresent();
        assertThat(restarted.previewInvite("forged")).isEmpty();
        assertThat(restarted.previewInvite(null)).isEmpty();
    }

    @Test
    void anInviteForAnExistingUserRetiresTheOldPasswordAndSessions() {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        var s = id.newSession(u);

        String token = id.invite(u.id()).token();

        assertThat(sessions.find(s.token())).isEmpty();
        assertThat(id.authenticate("lena", "password-of-lena")).isEmpty();
        id.acceptInvite(token, "lena-new-password");
        assertThat(id.authenticate("lena", "lena-new-password")).isPresent();
    }

    @Test
    void aTemporaryPasswordWorksOnceUntilChanged() {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        var s = id.newSession(u);

        String temp = id.temporaryPassword(u.id());

        assertThat(sessions.find(s.token())).isEmpty();
        assertThat(id.authenticate("lena", "password-of-lena")).isEmpty();
        User in = id.authenticate("lena", temp).orElseThrow();
        assertThat(in.mustChange()).isTrue();
        id.changePassword(u.id(), temp, "lena-own-password", null);
        assertThat(id.user(u.id()).orElseThrow().mustChange()).isFalse();
    }

    // ---- remember-me ----

    @Test
    void rememberMeTokensAreRotatedOnUse() {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        String t1 = id.issueRemember(u);

        IdentityService.Refreshed r1 = id.refresh(t1).orElseThrow();
        assertThat(r1.user().id()).isEqualTo(u.id());
        assertThat(r1.rememberToken()).isNotEqualTo(t1);
        assertThat(sessions.find(r1.session().token())).isPresent();
        assertThat(id.refresh(t1)).as("the old token is dead").isEmpty();

        IdentityService.Refreshed r2 = id.refresh(r1.rememberToken()).orElseThrow();
        assertThat(r2.rememberToken()).isNotIn(t1, r1.rememberToken());
        assertThat(id.rememberTokens(u.id())).isEqualTo(1);
    }

    @Test
    void rememberMeLastsThirtyDaysFromTheLastUse() {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        String t1 = id.issueRemember(u);

        clock.advance(Duration.ofDays(29));
        IdentityService.Refreshed r = id.refresh(t1).orElseThrow();   // renews the 30 days
        clock.advance(Duration.ofDays(29));
        IdentityService.Refreshed r2 = id.refresh(r.rememberToken()).orElseThrow();
        clock.advance(Duration.ofDays(31));
        assertThat(id.refresh(r2.rememberToken())).isEmpty();
        assertThat(id.refresh("forged")).isEmpty();
        assertThat(id.refresh(null)).isEmpty();
    }

    @Test
    void rememberMeTokensArePersistedHashedAndSurviveARestart() throws IOException {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        String token = id.issueRemember(u);

        String stored = Files.readString(tmp.resolve("users/sessions.json"));
        assertThat(stored).doesNotContain(token).contains(TokenStore.sha256(token));

        IdentityService restarted = create(new SessionStore(clock));   // restart: sessions are gone, the token not
        IdentityService.Refreshed r = restarted.refresh(token).orElseThrow();
        assertThat(r.user().username()).isEqualTo("lena");
        assertThat(restarted.refresh(token)).isEmpty();
        assertThat(Files.readString(tmp.resolve("users/sessions.json"))).doesNotContain(r.rememberToken());
    }

    @Test
    void revokedAndSignedOutTokensDoNotWork() {
        id.bootstrap(Optional.empty());
        User u = addUser("lena");
        String t1 = id.issueRemember(u);
        String t2 = id.issueRemember(u);
        id.revokeRemember(t1);
        assertThat(id.refresh(t1)).isEmpty();
        id.signOutEverywhere(u.id());
        assertThat(id.refresh(t2)).isEmpty();
    }

    // ---- throttling ----

    @Test
    void loginIsThrottledPerAddressAndPerUserName() {
        id.bootstrap(Optional.empty());
        for (int i = 0; i < LoginThrottle.DEFAULT_MAX_FAILURES; i++) {
            assertThat(id.loginBlocked("1.1.1.1", "x" + i)).isEmpty();
            id.loginFailed("1.1.1.1", "x" + i);
        }
        assertThat(id.loginBlocked("1.1.1.1", "fresh")).as("per address").isPresent();
        assertThat(id.loginBlocked("2.2.2.2", "fresh")).isEmpty();

        for (int i = 0; i < IdentityService.USER_MAX_FAILURES; i++) {
            assertThat(id.loginBlocked("3.3." + i + ".1", "Victim")).isEmpty();
            id.loginFailed("3.3." + i + ".1", "Victim");   // a botnet: a different address each time
        }
        assertThat(id.loginBlocked("4.4.4.4", "victim")).as("per user name, case-insensitive").isPresent();
        assertThat(id.loginBlocked("4.4.4.4", "someone-else")).isEmpty();

        clock.advance(Duration.ofMinutes(16));
        assertThat(id.loginBlocked("1.1.1.1", "fresh")).isEmpty();
        assertThat(id.loginBlocked("4.4.4.4", "victim")).isEmpty();
    }

    @Test
    void aSuccessfulLoginClearsBothCounters() {
        id.bootstrap(Optional.empty());
        for (int i = 0; i < 3; i++) {
            id.loginFailed("1.1.1.1", "lena");
        }
        id.loginSucceeded("1.1.1.1", "lena");
        for (int i = 0; i < 4; i++) {
            id.loginFailed("1.1.1.1", "lena");
        }
        assertThat(id.loginBlocked("1.1.1.1", "lena")).isEmpty();
    }

    @Test
    void theOtherAttemptsAreThrottledPerAddress() {
        for (int i = 0; i < LoginThrottle.DEFAULT_MAX_FAILURES; i++) {
            id.attemptFailed("5.5.5.5");
        }
        assertThat(id.attemptBlocked("5.5.5.5")).isPresent();
        assertThat(id.attemptBlocked("6.6.6.6")).isEmpty();
    }

    // ---- access hook ----

    @Test
    void accessChecksAreTheSingleDecisionPoint() {
        id.bootstrap(Optional.empty());
        User lena = id.createUser("lena", null, Role.USER, List.of("Jazz"), true);
        User kim = id.createUser("kim", null, Role.USER, List.of("Music"), false);

        Access.requireRole(admin(), Role.ADMIN);
        Access.requireRole(lena, Role.USER);
        assertThatThrownBy(() -> Access.requireRole(lena, Role.ADMIN))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("forbidden"));
        Access.requireRoot(lena, "Jazz");
        Access.requireRoot(admin(), "Kids");
        assertThatThrownBy(() -> Access.requireRoot(lena, "Music"))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.code()).isEqualTo("forbidden_root"));
        assertThatThrownBy(() -> Access.requireRoot(kim, org.kendar.dlna.shared.RootRef.parse("Jazz/x/y")))
                .isInstanceOf(IdentityException.class);
        Access.requireDownload(lena);
        assertThatThrownBy(() -> Access.requireDownload(kim)).isInstanceOf(IdentityException.class);
    }
}
