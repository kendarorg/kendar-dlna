package org.kendar.dlna.adminweb;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kendar.dlna.curation.CurationException;
import org.kendar.dlna.identity.Access;
import org.kendar.dlna.identity.IdentityException;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.Role;
import org.kendar.dlna.identity.SessionStore;
import org.kendar.dlna.identity.User;
import org.kendar.dlna.libraryquery.LibraryQuery;
import org.kendar.dlna.libraryquery.QueryException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The REST API under {@code /api/v1}, free of any Jetty types (paths here are relative to that mount): session
 * handling, authentication, CSRF protection and routing to the resources.
 *
 * <pre>
 * /auth/...     public: session status, login, logout, remember-me refresh, invite preview and accept
 * /me/...       any signed-in user: profile, change password
 * /library/...  any signed-in user, limited to the allowed roots (403 otherwise): the player's read API
 * /stream/...   any signed-in user, allowed roots per request: web playback, prefetch hint, download (if permitted)
 * /openapi.yaml public: the hand-written OpenAPI description
 * /admin/...    administrators only: the phase-1 admin features and user management
 * </pre>
 *
 * Everything outside {@code /auth} needs a login session; while the password must be changed only {@code /me} and
 * {@code /auth} work. Writes need the session's CSRF token in {@value #CSRF_HEADER}. Role and roots decisions are
 * taken by {@link Access} only: {@link #authorize} checks the role of a group, resources call
 * {@link Access#requireRoot} for every library location they touch.
 */
public final class AdminApi {

    public static final String COOKIE = "kd_session";
    public static final String REMEMBER_COOKIE = "kd_remember";
    public static final String CSRF_HEADER = "x-csrf-token";
    public static final String REQUESTED_WITH = "x-requested-with";
    public static final String COOKIE_PATH = "/api/v1";
    public static final String REMEMBER_PATH = "/api/v1/auth";

    private static final Logger LOG = Logger.getLogger(AdminApi.class.getName());

    private final IdentityService identity;
    private final SessionStore sessions;
    private final LibraryResources library;
    private final LibraryQueryResources libraryQuery;
    private final PlaylistResources playlists;
    private final OpsResources ops;
    private final SettingsResources settings;
    private final UserResources userAdmin;
    private final StreamResources stream;
    private final PersonalResources personal;

    /** With a default library query facade over the catalog (no artwork service). */
    public AdminApi(AdminServices services, IdentityService identity) {
        this(services, identity, LibraryQuery.basic(services.catalog().holder()::current));
    }

    public AdminApi(AdminServices services, IdentityService identity, LibraryQuery libraryQuery) {
        this(services, identity, libraryQuery, null);
    }

    /** With the web stream group {@code /stream} (null = not offered, 404). */
    public AdminApi(AdminServices services, IdentityService identity, LibraryQuery libraryQuery,
            WebStream webStream) {
        this.stream = webStream == null ? null : webStream.resources();
        this.identity = identity;
        this.sessions = identity.sessions();
        this.library = new LibraryResources(services);
        this.libraryQuery = new LibraryQueryResources(libraryQuery);
        this.playlists = new PlaylistResources(services);
        this.ops = new OpsResources(services);
        this.settings = new SettingsResources(services);
        this.userAdmin = new UserResources(identity);
        this.personal = new PersonalResources(new org.kendar.dlna.personal.PersonalService(identity,
                services.catalog()::current, services.clock()));
    }

    /** The personal-data service (periodic maintenance, shutdown flush). */
    public org.kendar.dlna.personal.PersonalService personal() {
        return personal.service();
    }

    /** The authenticated caller of a request. */
    record Caller(User user, SessionStore.Session session) {
    }

    public ApiResponse handle(ApiRequest req) {
        try {
            ApiResponse r = route(req);
            return r != null ? r : ApiResponse.error(404, "not_found", "No such resource: " + req.method() + " "
                    + req.path(), null);
        } catch (ApiException e) {
            ApiResponse r = ApiResponse.error(e.status(), e.code(), e.getMessage(), e.details());
            return e.status() == 429 && e.details() instanceof Map<?, ?> m && m.get("retryAfterSeconds") != null
                    ? r.withHeader("Retry-After", String.valueOf(m.get("retryAfterSeconds"))) : r;
        } catch (IdentityException e) {
            return ApiResponse.error(statusOf(e), e.code(), e.getMessage(), null);
        } catch (QueryException e) {
            return ApiResponse.error(e.code().equals("not_found") ? 404 : 400, e.code(), e.getMessage(), null);
        } catch (CurationException e) {
            return ApiResponse.error(statusOf(e), e.code(), e.getMessage(), e.details());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, "bad_request", String.valueOf(e.getMessage()), null);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "API request failed: " + req.method() + " " + req.path(), e);
            return ApiResponse.error(500, "internal_error", "Internal error", null);
        }
    }

    private static int statusOf(IdentityException e) {
        return switch (e.code()) {
            case "wrong_password", "forbidden", "forbidden_root" -> 403;
            case "invalid_invite" -> 410;
            case "user_exists", "last_admin", "self_action" -> 409;
            case "not_found" -> 404;
            default -> 400;
        };
    }

    private static int statusOf(CurationException e) {
        return switch (e.code()) {
            case CurationException.NOT_FOUND -> 404;
            case CurationException.INVALID -> 400;
            case CurationException.NOT_EDITABLE, CurationException.CONFLICT -> 409;
            default -> 500;
        };
    }

    private ApiResponse route(ApiRequest req) {
        List<String> seg = segments(req.path());
        if (seg.isEmpty()) {
            return null;
        }
        if (seg.size() == 1 && seg.get(0).equals("openapi.yaml") && req.isRead()) {
            return OpenApi.response();
        }
        if (seg.get(0).equals("auth")) {
            return auth(req, seg.subList(1, seg.size()));
        }
        Caller caller = authenticate(req);
        List<String> rest = seg.subList(1, seg.size());
        if (seg.get(0).equals("me") && !rest.isEmpty() && PersonalResources.isMeResource(rest.get(0))) {
            return personalGroup(req, caller, () -> personal.me(req, rest, caller.user()));
        }
        if (seg.get(0).equals("me")) {
            return me(req, rest, caller);
        }
        if (caller.user().mustChange()) {
            throw new ApiException(403, "password_change_required", "The password must be changed first");
        }
        requireCsrf(req, caller.session());
        return switch (seg.get(0)) {
            case "library" -> libraryQuery.handle(req, rest, caller.user());
            case "shared" -> personal.shared(req, rest, caller.user());
            case "stream" -> stream == null ? null : stream.handle(req, rest, caller.user(), caller.session().token());
            case "admin" -> {
                authorize(caller.user(), Role.ADMIN);
                yield admin(req, rest, caller);
            }
            default -> null;
        };
    }

    /** The single role hook of the API: every group is guarded by one call. */
    static void authorize(User user, Role required) {
        Access.requireRole(user, required);
    }

    private ApiResponse admin(ApiRequest req, List<String> seg, Caller caller) {
        if (seg.isEmpty()) {
            return null;
        }
        List<String> rest = seg.subList(1, seg.size());
        return switch (seg.get(0)) {
            case "users" -> userAdmin.handle(req, rest, caller.user());
            case "roots" -> req.isRead() ? userAdmin.roots() : null;
            case "folders" -> library.folders(req, rest);
            case "tracks" -> library.tracks(req, rest);
            case "search" -> rest.isEmpty() ? library.search(req) : null;
            case "playlists" -> playlists.handle(req, rest);
            case "shared-playlists" -> personal.moderation(req, rest);
            case "settings" -> settings.handle(req, rest);
            default -> ops.handle(req, seg);
        };
    }

    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // ---- authentication helpers ----

    private Caller authenticate(ApiRequest req) {
        SessionStore.Session s = sessions.find(req.cookie(COOKIE))
                .orElseThrow(() -> new ApiException(401, "unauthenticated", "Login required"));
        User u = identity.userOf(s).orElseThrow(() -> new ApiException(401, "unauthenticated", "Login required"));
        return new Caller(u, s);
    }

    private void requireCsrf(ApiRequest req, SessionStore.Session session) {
        checkOrigin(req);
        if (req.isRead()) {
            return;
        }
        String token = req.header(CSRF_HEADER);
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                session.csrfToken().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(403, "csrf", "Missing or invalid CSRF token");
        }
    }

    /** A cross-origin browser request (Origin differs from Host) is refused for writes. */
    private static void checkOrigin(ApiRequest req) {
        if (req.isRead()) {
            return;
        }
        String origin = req.header("origin");
        String host = req.header("host");
        if (origin == null) {
            return;
        }
        try {
            URI u = URI.create(origin);
            String authority = u.getPort() < 0 ? u.getHost() : u.getHost() + ":" + u.getPort();
            if (host == null || !authority.equalsIgnoreCase(host)) {
                throw new ApiException(403, "csrf", "Cross-origin request refused");
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(403, "csrf", "Cross-origin request refused");
        }
    }

    /** Pre-session POSTs (login, refresh, invite accept) carry no CSRF token: custom header + same origin. */
    private static void requirePublicPost(ApiRequest req) {
        checkOrigin(req);
        if (req.header(REQUESTED_WITH) == null) {
            throw new ApiException(403, "csrf", "Missing " + REQUESTED_WITH + " header");
        }
    }

    private static ApiException tooMany(Duration blocked) {
        long secs = Math.max(1, blocked.toSeconds());
        return new ApiException(429, "too_many_attempts", "Too many failed attempts, try again in " + secs + " s",
                Dto.obj("retryAfterSeconds", secs));
    }

    // ---- /auth ----

    private ApiResponse auth(ApiRequest req, List<String> seg) {
        if (seg.size() == 1) {
            String name = seg.get(0);
            if (name.equals("session") && req.isRead()) {
                return ApiResponse.ok(currentStatus(req));
            }
            if (req.method().equals("POST")) {
                return switch (name) {
                    case "login" -> login(req);
                    case "logout" -> logout(req);
                    case "refresh" -> refresh(req);
                    default -> null;
                };
            }
        }
        if (seg.size() == 2 && seg.get(0).equals("invite")) {
            if (seg.get(1).equals("accept") && req.method().equals("POST")) {
                return acceptInvite(req);
            }
            if (req.isRead()) {
                User u = identity.previewInvite(seg.get(1)).orElseThrow(
                        () -> new ApiException(410, "invalid_invite", "This invite link is invalid, used or expired"));
                return ApiResponse.ok(Dto.obj("username", u.username(), "displayName", u.displayName(),
                        "minPasswordLength", IdentityService.MIN_PASSWORD_LENGTH));
            }
        }
        return null;
    }

    private Map<String, Object> currentStatus(ApiRequest req) {
        Optional<SessionStore.Session> s = sessions.find(req.cookie(COOKIE));
        Optional<User> u = s.flatMap(identity::userOf);
        return u.isEmpty() ? Dto.obj("authenticated", false) : status(u.get(), s.get());
    }

    private static Map<String, Object> status(User u, SessionStore.Session s) {
        return Dto.obj("authenticated", true, "id", u.id(), "username", u.username(), "displayName", u.displayName(),
                "role", u.role().wire(), "mustChange", u.mustChange(), "allowedRoots", u.allowedRoots(),
                "downloadAllowed", u.mayDownload(), "csrfToken", s.csrfToken(), "minPasswordLength",
                IdentityService.MIN_PASSWORD_LENGTH);
    }

    private ApiResponse loggedIn(ApiRequest req, User user, boolean remember) {
        SessionStore.Session s = identity.newSession(user);
        ApiResponse r = ApiResponse.ok(status(user, s))
                .withCookie(cookie(req, COOKIE, s.token(), COOKIE_PATH, sessions.maxAge().toSeconds()));
        if (remember) {
            r = r.withCookie(cookie(req, REMEMBER_COOKIE, identity.issueRemember(user), REMEMBER_PATH,
                    IdentityService.REMEMBER_LIFETIME.toSeconds()));
        }
        return r;
    }

    private ApiResponse login(ApiRequest req) {
        requirePublicPost(req);
        JsonNode body = req.json();
        String user = body.path("username").asText("");
        String password = body.path("password").asText("");
        Optional<Duration> blocked = identity.loginBlocked(req.remoteAddr(), user);
        if (blocked.isPresent()) {
            throw tooMany(blocked.get());
        }
        Optional<User> u = identity.authenticate(user, password);
        if (u.isEmpty()) {
            identity.loginFailed(req.remoteAddr(), user);
            throw new ApiException(401, "invalid_credentials", "Wrong user name or password");
        }
        identity.loginSucceeded(req.remoteAddr(), user);
        return loggedIn(req, u.get(), body.path("remember").asBoolean(false));
    }

    private ApiResponse logout(ApiRequest req) {
        Optional<SessionStore.Session> s = sessions.find(req.cookie(COOKIE));
        s.ifPresent(x -> {
            requireCsrf(req, x);
            sessions.invalidate(x.token());
        });
        String remember = req.cookie(REMEMBER_COOKIE);
        if (remember != null && (s.isPresent() || req.header(REQUESTED_WITH) != null)) {
            identity.revokeRemember(remember);
        }
        return ApiResponse.noContent().withCookie(cookie(req, COOKIE, "", COOKIE_PATH, 0))
                .withCookie(cookie(req, REMEMBER_COOKIE, "", REMEMBER_PATH, 0));
    }

    private ApiResponse refresh(ApiRequest req) {
        requirePublicPost(req);
        String token = req.cookie(REMEMBER_COOKIE);
        if (token == null || token.isEmpty()) {
            throw new ApiException(401, "invalid_remember", "No remember-me token");
        }
        Optional<Duration> blocked = identity.attemptBlocked(req.remoteAddr());
        if (blocked.isPresent()) {
            throw tooMany(blocked.get());
        }
        Optional<IdentityService.Refreshed> r = identity.refresh(token);
        if (r.isEmpty()) {
            identity.attemptFailed(req.remoteAddr());
            throw new ApiException(401, "invalid_remember", "The remember-me token is invalid or expired")
                    ;
        }
        IdentityService.Refreshed ok = r.get();
        return ApiResponse.ok(status(ok.user(), ok.session()))
                .withCookie(cookie(req, COOKIE, ok.session().token(), COOKIE_PATH, sessions.maxAge().toSeconds()))
                .withCookie(cookie(req, REMEMBER_COOKIE, ok.rememberToken(), REMEMBER_PATH,
                        IdentityService.REMEMBER_LIFETIME.toSeconds()));
    }

    private ApiResponse acceptInvite(ApiRequest req) {
        requirePublicPost(req);
        Optional<Duration> blocked = identity.attemptBlocked(req.remoteAddr());
        if (blocked.isPresent()) {
            throw tooMany(blocked.get());
        }
        JsonNode body = req.json();
        try {
            User u = identity.acceptInvite(body.path("token").asText(""), body.path("password").asText(""));
            return loggedIn(req, u, body.path("remember").asBoolean(false));
        } catch (IdentityException e) {
            if (e.code().equals("invalid_invite")) {
                identity.attemptFailed(req.remoteAddr());
            }
            throw e;
        }
    }

    /** Personal data of the caller: needs a settled password and the CSRF token for writes. */
    private ApiResponse personalGroup(ApiRequest req, Caller caller, java.util.function.Supplier<ApiResponse> call) {
        if (caller.user().mustChange()) {
            throw new ApiException(403, "password_change_required", "The password must be changed first");
        }
        requireCsrf(req, caller.session());
        return call.get();
    }

    // ---- /me ----

    private ApiResponse me(ApiRequest req, List<String> seg, Caller caller) {
        if (seg.isEmpty() && req.isRead()) {
            return ApiResponse.ok(status(caller.user(), caller.session()));
        }
        if (seg.size() == 1 && seg.get(0).equals("password") && req.method().equals("POST")) {
            requireCsrf(req, caller.session());
            return changePassword(req, caller);
        }
        return null;
    }

    private ApiResponse changePassword(ApiRequest req, Caller caller) {
        Optional<Duration> blocked = identity.attemptBlocked(req.remoteAddr());
        if (blocked.isPresent()) {
            throw tooMany(blocked.get());
        }
        JsonNode body = req.json();
        try {
            identity.changePassword(caller.user().id(), body.path("currentPassword").asText(""),
                    body.path("newPassword").asText(""), caller.session().token());
        } catch (IdentityException e) {
            if (e.code().equals("wrong_password")) {
                identity.attemptFailed(req.remoteAddr());
            }
            throw e;
        }
        User fresh = identity.user(caller.user().id()).orElse(caller.user());
        return ApiResponse.ok(status(fresh, caller.session()));
    }

    static String cookie(ApiRequest req, String name, String token, String path, long maxAgeSeconds) {
        return name + "=" + token + "; Path=" + path + "; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds
                + (req.secure() ? "; Secure" : "");
    }
}
