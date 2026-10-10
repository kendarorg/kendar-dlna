package org.kendar.dlna.adminweb;

import java.util.List;
import java.util.Map;

import org.kendar.dlna.identity.User;
import org.kendar.dlna.libraryquery.LibraryQuery;

/**
 * HTTP adapter of the library query facade: {@code /library/...} for role user and admin. Parses the request,
 * calls {@link LibraryQuery} (which confines everything to the caller's allowed roots) and renders the answer.
 */
final class LibraryQueryResources {

    private final LibraryQuery query;

    LibraryQueryResources(LibraryQuery query) {
        this.query = query;
    }

    ApiResponse handle(ApiRequest req, List<String> seg, User user) {
        if (!req.isRead() || seg.isEmpty()) {
            return null;
        }
        Map<String, String> q = req.query();
        String head = seg.get(0);
        int n = seg.size();
        return switch (head) {
            case "roots" -> n == 1 ? ApiResponse.ok(query.roots(user)) : null;
            case "folders" -> n == 1 || (n == 2 && seg.get(1).equals("contents"))
                    ? ApiResponse.ok(query.folder(user, q)) : null;
            case "albums", "artists", "genres" -> switch (n) {
                case 1 -> ApiResponse.ok(query.groups(user, head, q));
                case 2 -> ApiResponse.ok(query.group(user, head, seg.get(1), false, q));
                case 3 -> seg.get(2).equals("tracks") ? ApiResponse.ok(query.group(user, head, seg.get(1), true, q))
                        : null;
                default -> null;
            };
            case "songs" -> n == 1 ? ApiResponse.ok(query.songs(user, q)) : null;
            case "tracks" -> n == 1 ? ApiResponse.ok(query.track(user, q)) : null;
            case "search" -> n == 1 ? ApiResponse.ok(query.search(user, q))
                    : n == 2 && seg.get(1).equals("suggest") ? ApiResponse.ok(query.suggest(user, q)) : null;
            case "recent" -> n == 1 ? ApiResponse.ok(query.recent(user, q)) : null;
            case "random" -> n == 1 ? ApiResponse.ok(query.random(user, q)) : null;
            case "artwork" -> n == 1 ? artwork(req, user) : null;
            default -> null;
        };
    }

    private ApiResponse artwork(ApiRequest req, User user) {
        LibraryQuery.Image img = query.artwork(user, req.query());
        String inm = req.header("if-none-match");
        ApiResponse r;
        if (inm != null && (inm.equals(img.etag()) || inm.equals("W/" + img.etag()) || inm.equals("*"))) {
            r = new ApiResponse(304, null, null, null, null);
        } else {
            r = ApiResponse.bytes(img.contentType(), img.data());
        }
        return r.withHeader("ETag", img.etag()).withHeader("Cache-Control", "private, no-cache");
    }
}
