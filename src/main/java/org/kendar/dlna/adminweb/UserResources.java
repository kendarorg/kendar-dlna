package org.kendar.dlna.adminweb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.kendar.dlna.identity.IdentityException;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.Role;
import org.kendar.dlna.identity.User;

import com.fasterxml.jackson.databind.JsonNode;

/** REST resources under {@code /admin/users} and {@code /admin/roots} (administrators only). */
final class UserResources {

    private final IdentityService identity;

    UserResources(IdentityService identity) {
        this.identity = identity;
    }

    ApiResponse roots() {
        return ApiResponse.ok(Dto.obj("roots", identity.rootNames(), "mainRoot", identity.mainRoot()));
    }

    ApiResponse handle(ApiRequest req, List<String> seg, User caller) {
        if (seg.isEmpty()) {
            return switch (req.method()) {
                case "GET" -> list();
                case "POST" -> create(req);
                default -> null;
            };
        }
        String id = seg.get(0);
        if (seg.size() == 1) {
            return switch (req.method()) {
                case "GET" -> ApiResponse.ok(Dto.obj("user", dto(find(id))));
                case "PUT" -> update(id, req, caller);
                case "DELETE" -> {
                    identity.deleteUser(id, caller.id());
                    yield ApiResponse.noContent();
                }
                default -> null;
            };
        }
        if (seg.size() == 2 && req.method().equals("POST")) {
            return switch (seg.get(1)) {
                case "invite" -> {
                    find(id);
                    yield ApiResponse.ok(Dto.obj("user", dto(find(id)), "invite", invite(identity.invite(id))));
                }
                case "temporary-password" -> {
                    find(id);
                    String pw = identity.temporaryPassword(id);
                    yield ApiResponse.ok(Dto.obj("user", dto(find(id)), "temporaryPassword", pw));
                }
                default -> null;
            };
        }
        return null;
    }

    private User find(String id) {
        return identity.user(id).orElseThrow(() -> new IdentityException("not_found", "No such user"));
    }

    private ApiResponse list() {
        List<Object> out = new ArrayList<>();
        identity.users().forEach(u -> out.add(dto(u)));
        return ApiResponse.ok(Dto.obj("users", out, "roots", identity.rootNames(), "mainRoot", identity.mainRoot()));
    }

    /** Body: username, displayName, role (user), allowedRoots (default: main root), downloadAllowed (false),
     * credential ("invite" default, or "temporaryPassword"). */
    private ApiResponse create(ApiRequest req) {
        JsonNode b = req.json();
        String credential = b.path("credential").asText("invite");
        if (!credential.equals("invite") && !credential.equals("temporaryPassword")) {
            throw new ApiException(400, "bad_request", "credential must be invite or temporaryPassword");
        }
        Role role = b.hasNonNull("role") ? Role.parse(b.get("role").asText()) : Role.USER;
        User u = identity.createUser(b.path("username").asText(""), text(b, "displayName"), role,
                b.has("allowedRoots") ? strings(b.get("allowedRoots")) : null, b.path("downloadAllowed").asBoolean(false));
        Map<String, Object> out = Dto.obj();
        if (credential.equals("invite")) {
            Map<String, Object> inv = invite(identity.invite(u.id()));
            out.put("user", dto(find(u.id())));
            out.put("invite", inv);
        } else {
            String pw = identity.temporaryPassword(u.id());
            out.put("user", dto(find(u.id())));
            out.put("temporaryPassword", pw);
        }
        return ApiResponse.json(201, out);
    }

    private ApiResponse update(String id, ApiRequest req, User caller) {
        JsonNode b = req.json();
        Role role = b.hasNonNull("role") ? Role.parse(b.get("role").asText()) : null;
        User u = identity.updateUser(id, text(b, "displayName"), role,
                b.has("allowedRoots") ? strings(b.get("allowedRoots")) : null,
                b.hasNonNull("downloadAllowed") ? b.get("downloadAllowed").asBoolean() : null,
                b.hasNonNull("enabled") ? b.get("enabled").asBoolean() : null, caller.id());
        return ApiResponse.ok(Dto.obj("user", dto(u)));
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static List<String> strings(JsonNode n) {
        if (n == null || !n.isArray()) {
            throw new ApiException(400, "bad_request", "allowedRoots must be an array of root names");
        }
        List<String> out = new ArrayList<>();
        n.forEach(e -> out.add(e.asText()));
        return out;
    }

    private static Map<String, Object> invite(IdentityService.Invite i) {
        return Dto.obj("token", i.token(), "expiresAt", i.expiresAt().toString(), "path",
                "/app/#/invite/" + i.token());
    }

    private Map<String, Object> dto(User u) {
        return Dto.obj("id", u.id(), "username", u.username(), "displayName", u.displayName(), "role",
                u.role().wire(), "enabled", u.enabled(), "mustChange", u.mustChange(), "hasPassword", u.hasPassword(),
                "pendingInvite", identity.hasPendingInvite(u.id()), "allowedRoots", u.allowedRoots(),
                "downloadAllowed", u.downloadAllowed(), "createdAt", u.createdAt().toString(), "lastLogin",
                u.lastLogin() == null ? null : u.lastLogin().toString());
    }
}
