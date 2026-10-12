package org.kendar.dlna.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The Identity context: users and roles, credentials, invites, login sessions, remember-me tokens, login
 * throttling and the allowed-roots list (plan sections 3 and 4). All state lives under {@code <dataDir>/users}:
 * {@code users.json}, {@code invites.json}, {@code sessions.json} (remember-me) and one folder per user id for the
 * personal data of later milestones.
 */
public final class IdentityService {

    public static final String DEFAULT_ADMIN = "admin";
    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final Duration INVITE_LIFETIME = Duration.ofDays(7);
    public static final Duration REMEMBER_LIFETIME = Duration.ofDays(30);
    public static final int USER_MAX_FAILURES = 10;
    static final int GENERATED_LENGTH = 20;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{1,63}");

    private static final Logger LOG = Logger.getLogger(IdentityService.class.getName());

    /** The admin account of config.json as it was before the user store (migrated at first start). */
    public record LegacyAdmin(String username, String passwordHash, boolean mustChange) {
    }

    /** A freshly created or re-issued invite: the raw token is shown once, only its hash is stored. */
    public record Invite(String token, Instant expiresAt) {
    }

    /** What a refresh with the remember-me token yields. */
    public record Refreshed(User user, SessionStore.Session session, String rememberToken) {
    }

    private final Path usersDir;
    private final List<String> rootNames;
    private final String mainRoot;
    private final PasswordHasher hasher;
    private final SessionStore sessions;
    private final Clock clock;
    private final SecureRandom random;
    private final Consumer<String> announcer;
    private final UserStore users;
    private final TokenStore invites;
    private final TokenStore remember;
    private final LoginThrottle ipThrottle;
    private final LoginThrottle userThrottle;
    private final String dummyHash;

    /**
     * @param rootNames configured library root names (in order); {@code mainRoot} the default root of new users
     *                  (null = the first one)
     */
    public IdentityService(Path usersDir, List<String> rootNames, String mainRoot, PasswordHasher hasher,
            SessionStore sessions, Clock clock, SecureRandom random, Consumer<String> announcer) {
        this.usersDir = usersDir;
        this.rootNames = List.copyOf(rootNames);
        this.mainRoot = mainRoot != null ? mainRoot : (rootNames.isEmpty() ? null : rootNames.get(0));
        this.hasher = hasher;
        this.sessions = sessions;
        this.clock = clock;
        this.random = random;
        this.announcer = announcer;
        this.users = new UserStore(usersDir.resolve(UserStore.FILE_NAME));
        this.invites = new TokenStore(usersDir.resolve("invites.json"), "invites", clock, random);
        this.remember = new TokenStore(usersDir.resolve("sessions.json"), "tokens", clock, random);
        this.ipThrottle = new LoginThrottle(clock);
        this.userThrottle = new LoginThrottle(clock, USER_MAX_FAILURES, LoginThrottle.DEFAULT_WINDOW);
        this.dummyHash = hasher.hash("not-a-real-password");
    }

    /** Announcer writing the marked block to the application log. */
    public static Consumer<String> logAnnouncer() {
        return block -> LOG.warning("\n" + block);
    }

    public SessionStore sessions() {
        return sessions;
    }

    public List<String> rootNames() {
        return rootNames;
    }

    public String mainRoot() {
        return mainRoot;
    }

    // ---- bootstrap, migration, configuration changes ----

    /**
     * Administrator password given by the deployment ({@code KENDAR_ADMIN_PASSWORD}, plain text of at least
     * {@value #MIN_PASSWORD_LENGTH} characters, or a stored {@code pbkdf2-sha256$...} hash), applied at every start,
     * before {@link #bootstrap}: a missing administrator is created with it (no password is generated nor logged);
     * the first administrator (or the one named {@code username}) gets it when it differs from the current password,
     * without a forced change, and is signed out everywhere. Nothing is logged but the fact.
     *
     * @param username the administrator to create or update, null = the first administrator, else "admin"
     * @param secret   the password or hash; null / blank = do nothing
     * @return true when an account was created or its password replaced
     */
    public synchronized boolean applyAdminOverride(String username, String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        boolean isHash = secret.startsWith("pbkdf2-sha256$");
        if (!isHash && policyViolation(secret, null) != null) {
            LOG.severe("KENDAR_ADMIN_PASSWORD is ignored: " + policyViolation(secret, null));
            return false;
        }
        User admin = users.all().stream().filter(u -> u.role() == Role.ADMIN)
                .filter(u -> username == null || u.username().equalsIgnoreCase(username))
                .min(Comparator.comparing(User::createdAt)).orElse(null);
        if (admin == null) {
            String name = username != null ? username : DEFAULT_ADMIN;
            if (users.byUsername(name).isPresent()) {
                LOG.severe("KENDAR_ADMIN_USER '" + name + "' exists and is not an administrator: override ignored");
                return false;
            }
            users.put(newUser(name, name, Role.ADMIN, isHash ? secret : hasher.hash(secret), false, List.of(), true));
            LOG.warning("The admin account '" + name + "' was created with the password from the environment");
            return true;
        }
        boolean same = isHash ? secret.equals(admin.passwordHash())
                : admin.hasPassword() && hasher.verify(secret, admin.passwordHash());
        if (same && !admin.mustChange()) {
            return false;
        }
        users.put(admin.withPassword(isHash ? secret : hasher.hash(secret), false));
        signOutEverywhere(admin.id());
        invites.removeUser(admin.id());
        LOG.warning("The password of '" + admin.username() + "' was set from the environment");
        return true;
    }

    /**
     * First start of the user-store version: when there is no administrator yet, the admin of config.json is
     * migrated into the store (same hash and must-change flag); without one a random password is generated and
     * announced once. Later starts do nothing.
     */
    public synchronized void bootstrap(Optional<LegacyAdmin> legacy) {
        if (users.all().stream().anyMatch(u -> u.role() == Role.ADMIN)) {
            return;
        }
        String name = legacy.map(LegacyAdmin::username).filter(u -> u != null && !u.isBlank()).orElse(DEFAULT_ADMIN);
        if (legacy.isPresent() && legacy.get().passwordHash() != null && !legacy.get().passwordHash().isBlank()) {
            User admin = newUser(name, name, Role.ADMIN, legacy.get().passwordHash(), legacy.get().mustChange(),
                    List.of(), true);
            users.put(admin);
            LOG.info("The admin account '" + name + "' of config.json was migrated into the user store");
            return;
        }
        User admin = newUser(name, name, Role.ADMIN, null, true, List.of(), true);
        users.put(admin);
        generateFor(admin, "first startup: no admin password was set");
    }

    /**
     * Applies the configured roots: roots no longer configured are removed from every user (logged). Returns the
     * number of users changed.
     */
    public synchronized int syncRoots(Set<String> configured) {
        List<User> changed = new ArrayList<>();
        for (User u : users.all()) {
            List<String> kept = u.allowedRoots().stream().filter(configured::contains).toList();
            if (kept.size() != u.allowedRoots().size()) {
                List<String> removed = new ArrayList<>(u.allowedRoots());
                removed.removeAll(kept);
                LOG.info("Library roots " + removed + " are no longer configured: removed from user '" + u.username()
                        + "'");
                changed.add(u.withAllowedRoots(kept));
            }
        }
        if (!changed.isEmpty()) {
            users.putAll(changed);
        }
        return changed.size();
    }

    // ---- lookups ----

    public List<User> users() {
        return users.all();
    }

    public Optional<User> user(String id) {
        return users.byId(id);
    }

    public Optional<User> userByName(String username) {
        return users.byUsername(username);
    }

    /** The user behind a live login session; a disabled or deleted user ends the session. */
    public Optional<User> userOf(SessionStore.Session session) {
        Optional<User> u = users.byId(session.userId());
        if (u.isEmpty() || !u.get().enabled()) {
            sessions.invalidate(session.token());
            return Optional.empty();
        }
        return u;
    }

    /** The data folder of a user (not created here). */
    public Path dataDir(String userId) {
        return usersDir.resolve(userId);
    }

    // ---- login, sessions, remember-me ----

    /**
     * Checks the credentials; the hash work is done even for an unknown user. Disabled users and users who have
     * not accepted their invite never authenticate. Records the last login.
     */
    public Optional<User> authenticate(String username, String password) {
        Optional<User> found = users.byUsername(username);
        String stored = found.filter(User::enabled).filter(User::hasPassword).map(User::passwordHash).orElse(dummyHash);
        boolean ok = password != null && hasher.verify(password, stored);
        if (!ok || found.isEmpty() || !found.get().enabled() || !found.get().hasPassword()) {
            return Optional.empty();
        }
        User u = found.get().withLastLogin(clock.instant());
        users.put(u);
        return Optional.of(u);
    }

    public SessionStore.Session newSession(User user) {
        return sessions.create(user.id());
    }

    /** A new remember-me token for the user (30 days); only its hash is persisted. */
    public String issueRemember(User user) {
        return remember.issue(user.id(), REMEMBER_LIFETIME);
    }

    /**
     * Logs in with a remember-me token: the token is consumed and replaced by a new one (rotation), a login session
     * is created. Empty for an unknown, expired, already rotated token or a disabled user.
     */
    public synchronized Optional<Refreshed> refresh(String rememberToken) {
        Optional<TokenStore.Entry> e = remember.take(rememberToken);
        if (e.isEmpty()) {
            return Optional.empty();
        }
        Optional<User> u = users.byId(e.get().userId()).filter(User::enabled).filter(User::hasPassword);
        if (u.isEmpty()) {
            return Optional.empty();
        }
        String next = remember.issue(u.get().id(), REMEMBER_LIFETIME);
        users.put(u.get().withLastLogin(clock.instant()));
        return Optional.of(new Refreshed(users.byId(u.get().id()).orElseThrow(), sessions.create(u.get().id()), next));
    }

    /** Revokes one remember-me token (logout). */
    public void revokeRemember(String rememberToken) {
        remember.take(rememberToken);
    }

    /** Ends every session and remember-me token of the user. */
    public void signOutEverywhere(String userId) {
        sessions.invalidateUser(userId);
        remember.removeUser(userId);
    }

    public int rememberTokens(String userId) {
        return remember.forUser(userId).size();
    }

    // ---- throttling ----

    /** How long this login attempt must wait (per IP and per user name), empty when it may proceed. */
    public Optional<Duration> loginBlocked(String ip, String username) {
        Optional<Duration> a = ipThrottle.blockedFor(ip);
        Optional<Duration> b = userThrottle.blockedFor(userKey(username));
        if (a.isPresent() && b.isPresent()) {
            return a.get().compareTo(b.get()) >= 0 ? a : b;
        }
        return a.isPresent() ? a : b;
    }

    public void loginFailed(String ip, String username) {
        ipThrottle.failure(ip);
        userThrottle.failure(userKey(username));
    }

    public void loginSucceeded(String ip, String username) {
        ipThrottle.success(ip);
        userThrottle.success(userKey(username));
    }

    /** Throttling of the other credential checks (remember refresh, invite accept, password change): per IP. */
    public Optional<Duration> attemptBlocked(String ip) {
        return ipThrottle.blockedFor(ip);
    }

    public void attemptFailed(String ip) {
        ipThrottle.failure(ip);
    }

    private static String userKey(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    // ---- passwords ----

    /** The reason a new password is unacceptable, or null. */
    public static String policyViolation(String password, String current) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return "The new password must have at least " + MIN_PASSWORD_LENGTH + " characters";
        }
        if (password.length() > 200) {
            return "The new password is too long";
        }
        if (password.equals(current)) {
            return "The new password must differ from the current one";
        }
        return null;
    }

    /**
     * Changes the user's own password after checking the current one; clears must-change and ends all the user's
     * other sessions and remember-me tokens.
     *
     * @throws IdentityException {@code wrong_password} or {@code weak_password}
     */
    public synchronized void changePassword(String userId, String current, String next, String keepSessionToken) {
        User u = users.byId(userId).orElseThrow(() -> notFound());
        if (current == null || !u.hasPassword() || !hasher.verify(current, u.passwordHash())) {
            throw new IdentityException("wrong_password", "The current password is not correct");
        }
        String problem = policyViolation(next, current);
        if (problem != null) {
            throw new IdentityException("weak_password", problem);
        }
        users.put(u.withPassword(hasher.hash(next), false));
        sessions.invalidateUserExcept(userId, keepSessionToken);
        remember.removeUser(userId);
    }

    /** Lost admin password (reset-password file): new random password announced in the log, must-change set. */
    public synchronized void resetAdminPassword() {
        User admin = users.all().stream().filter(u -> u.role() == Role.ADMIN)
                .min(Comparator.comparing(User::createdAt)).orElseThrow(() -> notFound());
        generateFor(admin, "password reset requested");
    }

    private void generateFor(User user, String reason) {
        String pw = randomPassword();
        users.put(users.byId(user.id()).orElse(user).withPassword(hasher.hash(pw), true));
        signOutEverywhere(user.id());
        invites.removeUser(user.id());
        announcer.accept(block(reason, user.username(), pw));
    }

    static String block(String reason, String user, String password) {
        String bar = "=".repeat(64);
        return bar + "\n  KENDAR DLNA ADMIN PASSWORD (" + reason + ")\n  user:     " + user + "\n  password: "
                + password + "\n  Shown only once. It must be changed at the first login.\n" + bar;
    }

    private String randomPassword() {
        StringBuilder pw = new StringBuilder();
        for (int i = 0; i < GENERATED_LENGTH; i++) {
            pw.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return pw.toString();
    }

    // ---- user management (admin) ----

    /**
     * Creates an account without a password; the caller then issues an invite or a temporary password.
     *
     * @param allowedRoots null = the main root only; unknown roots are refused
     */
    public synchronized User createUser(String username, String displayName, Role role, List<String> allowedRoots,
            boolean downloadAllowed) {
        String name = username == null ? "" : username.strip();
        if (!USERNAME.matcher(name).matches()) {
            throw new IdentityException("invalid", "The user name must have 2 to 64 letters, digits, '.', '_', '-' or '@'");
        }
        if (users.byUsername(name).isPresent()) {
            throw new IdentityException("user_exists", "This user name is taken");
        }
        List<String> roots = allowedRoots == null ? defaultRoots() : checkRoots(allowedRoots);
        User u = newUser(name, displayName, role == null ? Role.USER : role, null, true, roots, downloadAllowed);
        users.put(u);
        return u;
    }

    /** Changes profile, role, roots, download permission and enabled flag; disabling ends all sessions. */
    public synchronized User updateUser(String id, String displayName, Role role, List<String> allowedRoots,
            Boolean downloadAllowed, Boolean enabled, String actingUserId) {
        User u = users.byId(id).orElseThrow(() -> notFound());
        Role newRole = role == null ? u.role() : role;
        boolean newEnabled = enabled == null ? u.enabled() : enabled;
        if (u.role() == Role.ADMIN && (newRole != Role.ADMIN || !newEnabled) && otherActiveAdmins(id) == 0) {
            throw new IdentityException("last_admin", "This is the last active administrator");
        }
        if (id.equals(actingUserId) && (newRole != u.role() || !newEnabled)) {
            throw new IdentityException("self_action", "You cannot change your own role or disable yourself");
        }
        User updated = u.withProfile(displayName == null ? u.displayName() : displayName.strip(), newRole,
                allowedRoots == null ? u.allowedRoots() : checkRoots(allowedRoots),
                downloadAllowed == null ? u.downloadAllowed() : downloadAllowed, newEnabled);
        users.put(updated);
        if (!newEnabled || newRole != u.role()) {
            signOutEverywhere(id);
        }
        return updated;
    }

    /** Deletes the user, ends their sessions, drops their invites and tokens and removes their data folder. */
    public synchronized void deleteUser(String id, String actingUserId) {
        User u = users.byId(id).orElseThrow(() -> notFound());
        if (id.equals(actingUserId)) {
            throw new IdentityException("self_action", "You cannot delete yourself");
        }
        if (u.role() == Role.ADMIN && otherActiveAdmins(id) == 0) {
            throw new IdentityException("last_admin", "This is the last active administrator");
        }
        users.remove(id);
        signOutEverywhere(id);
        invites.removeUser(id);
        deleteTree(dataDir(id));
    }

    /** New invite link token for the user (any earlier invite is void); ends the user's sessions. */
    public synchronized Invite invite(String id) {
        User u = users.byId(id).orElseThrow(() -> notFound());
        invites.removeUser(id);
        signOutEverywhere(id);
        users.put(u.withPassword(null, true));   // the old password no longer works
        String token = invites.issue(id, INVITE_LIFETIME);
        return new Invite(token, clock.instant().plus(INVITE_LIFETIME));
    }

    /** Fallback without a link: a random temporary password (returned once) with must-change; ends sessions. */
    public synchronized String temporaryPassword(String id) {
        User u = users.byId(id).orElseThrow(() -> notFound());
        String pw = randomPassword();
        users.put(u.withPassword(hasher.hash(pw), true));
        signOutEverywhere(id);
        invites.removeUser(id);
        return pw;
    }

    public boolean hasPendingInvite(String id) {
        return !invites.forUser(id).isEmpty();
    }

    /** The user an invite token is for (to greet them on the accept page), empty when void or expired. */
    public Optional<User> previewInvite(String token) {
        return invites.peek(token).flatMap(e -> users.byId(e.userId()));
    }

    /**
     * Accepts an invite: the token is consumed (one-time), the chosen password stored, must-change cleared.
     *
     * @throws IdentityException {@code invalid_invite} (unknown, used, expired) or {@code weak_password}
     */
    public synchronized User acceptInvite(String token, String password) {
        Optional<TokenStore.Entry> e = invites.peek(token);
        Optional<User> u = e.flatMap(x -> users.byId(x.userId()));
        if (u.isEmpty()) {
            throw new IdentityException("invalid_invite", "This invite link is invalid, used or expired");
        }
        String problem = policyViolation(password, null);
        if (problem != null) {
            throw new IdentityException("weak_password", problem);   // the token stays valid
        }
        invites.take(token);
        User updated = u.get().withPassword(hasher.hash(password), false);
        users.put(updated);
        return updated;
    }

    // ---- helpers ----

    private User newUser(String username, String displayName, Role role, String hash, boolean mustChange,
            List<String> roots, boolean download) {
        return new User(UUID.randomUUID().toString(), username, displayName, role, hash, mustChange, roots, download,
                true, clock.instant(), null);
    }

    private List<String> defaultRoots() {
        return mainRoot == null ? List.of() : List.of(mainRoot);
    }

    private List<String> checkRoots(List<String> roots) {
        Set<String> out = new LinkedHashSet<>();
        for (String r : roots) {
            if (!rootNames.contains(r)) {
                throw new IdentityException("invalid", "Unknown library root: " + r);
            }
            out.add(r);
        }
        return List.copyOf(out);
    }

    private long otherActiveAdmins(String exceptId) {
        return users.all().stream().filter(x -> x.role() == Role.ADMIN && x.enabled() && !x.id().equals(exceptId))
                .count();
    }

    private static IdentityException notFound() {
        return new IdentityException("not_found", "No such user");
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + dir, e);
        }
    }
}
