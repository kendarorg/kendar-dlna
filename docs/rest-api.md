# REST API reference

The REST API lives under **`/api/v1`** on the single HTTP port (default 8200). It is what the web app (`/app/`, admin pages also at `/admin/`) uses
and what the future web player will use (plan: [plan.player.md](../plan.player.md)). This page is derived from the code:
`adminweb/AdminApi` (routing, session, CSRF, role hook), `UserResources`, `LibraryResources`, `PlaylistResources`,
`OpsResources`, `Dto` (JSON shapes), `ApiHandler` (HTTP adapter), `identity/*` (users, invites, sessions, throttling,
allowed roots), `curation/*` (validation, error codes), and the client `src/main/resources/web/js/api.js`.

Contents: [conventions](#conventions) - [groups and roles](#groups-and-roles) -
[authentication](#authentication-and-session-model) - [errors](#error-model) - [auth endpoints](#auth) -
[me](#me) - [library (users)](#library-any-signed-in-user) - [stream](#stream-any-signed-in-user) - [network access](#network-access-and-reverse-proxy) - [user management](#user-management-admin) -
[folders](#folders) - [tracks](#tracks) - [search](#search) - [playlists](#playlists) - [admin](#admin) -
[open endpoints](#open-endpoints-no-login) - [curl walkthrough](#curl-walkthrough)

> **Moved in this version.** The unversioned `/api/...` of phase 1 is gone (`404`): `/api/session` is now
> `/api/v1/auth/session|login|logout`, `/api/session/password` is `/api/v1/me/password`, and every phase-1 admin
> resource (`folders`, `tracks`, `search`, `playlists`, `admin/*`) lives under `/api/v1/admin/`. Paths below that start
> with `/admin/` are relative to `/api/v1`.

## Conventions

* Base path `/api/v1`; JSON in and out (`application/json; charset=utf-8`, pretty-printed, `null` fields are written).
  Request bodies are UTF-8 JSON, except art upload (raw image bytes).
* Every `/api/v1` response carries `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
* Paths of library objects are **root-qualified**: `Root/relative/path` with `/` separators; a bare `Root` is the root
  directory itself. `.` and `..` segments are rejected. Example: `Music/Pink Floyd/The Wall/01 In the Flesh.flac`.
  They are passed as the query parameter `path` (URL-encode it) except in bodies that carry paths explicitly.
* Routing is by method + path only. An unknown path **or a known path with an unsupported method** answers
  `404 not_found` (`No such resource: <METHOD> <path>`); the API never answers 405.
* `GET` and `HEAD` are "reads": no CSRF token needed. Every other method is a "write".
* Maximum request body: 12 MiB (else `413 too_large`). Art uploads are further limited to 10 MiB.
* Query values: duplicates - the last one wins.

## Groups and roles

| Group | Access | Contents |
|---|---|---|
| `/auth` | public | session status, login, logout, remember-me refresh, invite preview and accept |
| `/me` | any signed-in user (profile and password also while the password must be changed) | profile, change own password; `playlists`, `favorites`, `queue`, `history` (see [Personal data](#personal-data-any-signed-in-user)) |
| `/shared` | any signed-in user, **allowed roots filter** | shared playlists of all users, system playlists (read-only) |
| `/library` | any signed-in user, **allowed roots only** | read facade of the player: `roots`, `folders`, `albums`, `artists`, `genres`, `songs`, `tracks`, `search`, `recent`, `random`, `artwork` (see [Library](#library-any-signed-in-user)) |
| `/stream` | any signed-in user, **allowed roots per request** | web playback with format negotiation, `formats`, `prefetch`, `download` (permission) (see [Stream](#stream-any-signed-in-user)) |
| `/openapi.yaml` | public | the hand-written OpenAPI description |
| `/admin` | **admin** | phase-1 admin features (`folders`, `tracks`, `search`, `playlists`, rescan, status), user management (`users`, `roots`) and shared-playlist moderation (`shared-playlists`) |

Roles: `admin` (everything, all roots) and `user` (a registered listener). Authorization is decided in one place
(`identity/Access`): the router checks the role of a group once (`403 forbidden`), and every resource touching a library
location calls `Access.requireRoot` (`403 forbidden_root`). The roots check happens **before** the existence of the path
is revealed, so a user gets `403` (not `404`) for any path outside their allowed roots, existing or not.

Later API groups (personal data, shared playlists) plug into the same hook, see
[plan.player.md](../plan.player.md) section 10.

## Authentication and session model

Users live in `<dataDir>/users/users.json` (see [configuration.md](configuration.md#users-and-identity-files)).
Passwords are stored as PBKDF2-HMAC-SHA256 (`pbkdf2-sha256$310000$salt$hash`).

| Item | Behaviour |
|---|---|
| Login | `POST /auth/login` with `{"username","password","remember"?}` creates a session. User names are case-insensitive. Needs header `X-Requested-With` (any value). Disabled users, users who have not accepted their invite and unknown users get the same `401 invalid_credentials`. |
| Cookie | `kd_session=<256-bit random token>; Path=/api/v1; HttpOnly; SameSite=Strict; Max-Age=86400`, plus `Secure` when the connection is TLS or a **trusted proxy** sent `X-Forwarded-Proto: https`. |
| Lifetime | In memory only (lost on restart). Idle timeout 8 h, absolute lifetime 24 h. |
| Remember me | `remember: true` at login (or invite accept) also sets `kd_remember=<256-bit token>; Path=/api/v1/auth; HttpOnly; SameSite=Strict; Max-Age=2592000` (30 days). Only the SHA-256 of the token is kept, in `<dataDir>/users/sessions.json`, so restarts do not sign users out. `POST /auth/refresh` consumes the token, sets a **new** one (rotation, another 30 days) and a new session; a used, unknown or expired token gives `401 invalid_remember`. Logout, password change, disable, delete, invite and reset revoke the user's tokens. |
| CSRF token | Per session, returned by login, refresh, invite accept, `GET /auth/session` and `GET /me` as `csrfToken`. Every write after login (not the public `/auth` POSTs) must send it in header `X-CSRF-Token`; compared in constant time. Reads do not need it. |
| `X-Requested-With` | Required on the public POSTs (`login`, `refresh`, `invite/accept`; `403 csrf` when missing). The web UI always sends `X-Requested-With: kendar`. |
| Origin check | For writes (public POSTs included): if an `Origin` header is present its host[:port] must equal the `Host` header, else `403 csrf`. No `Origin` (curl) passes. |
| Throttling | **Per client IP**: after **5** failed attempts within **15 min** the address is blocked until the oldest failure leaves the window; **per user name** (case-insensitive): after **10** failures within 15 min the name is blocked from every address. Both answer `429 too_many_attempts`, header `Retry-After: <s>`, `details.retryAfterSeconds`. A successful login clears both records. Failures counted: wrong login (IP + name); wrong current password on password change, invalid remember token and invalid invite token (IP only). Tracked keys capped at 10 000. |
| Forced change | While `mustChange` is true (first start, after a reset or a temporary password) every endpoint except `/auth/*` and `/me/*` answers `403 password_change_required`. |
| Unauthenticated | Any endpoint outside `/auth` and `/openapi.yaml` answers `401 unauthenticated` without a valid cookie, or when the user was disabled or deleted. |
| Password policy | At least 10 characters (`minPasswordLength`), at most 200, different from the current one. |
| Password change effect | Clears `mustChange`, ends **all other** sessions and remember-me tokens of that user, keeps the current session. |
| First password | On the first start without any admin, the admin of `config.json` is **migrated** into the user store (same hash, same must-change flag, role admin); without one a password is generated (20 characters from an unambiguous alphabet), logged once in a marked block (`KENDAR DLNA ADMIN PASSWORD`), `mustChange=true`. Later starts never migrate again. |
| Reset | Create an empty file `reset-password` in the config directory. Checked at startup and every 30 s; the file is deleted first (if deletion fails: an error is logged and **nothing** is reset), then a new password is generated for the (oldest) admin account and logged, `mustChange=true`, all that account's sessions end. |
| Not covered | The DLNA side (`/upnp`, `/stream`, `/art`) has no authentication by design and is LAN-only ([network access](#network-access-and-reverse-proxy)); `/health` is open. No HTTPS in the server: use a reverse proxy. |

Order of checks for a protected endpoint: session (401) -> must-change (403, outside `/me`) -> Origin (403, writes)
-> CSRF token (403, writes) -> role of the group (403 `forbidden`) -> route (404) -> roots / resource validation.

## Error model

Every error is JSON, with the status line as below:

```json
{ "code": "invalid", "message": "year must be a number between 0 and 9999", "details": null }
```

`details` is `null` unless stated (a list of problem strings, `{"retryAfterSeconds":n}`, `{"conflicts":[...]}`).

| Status | `code` | Cause |
|---|---|---|
| 400 | `bad_request` | Missing/invalid query parameter or body field, malformed path, `IllegalArgumentException` from a resource, unreadable body |
| 400 | `bad_json` | Body missing or not valid JSON |
| 400 | `invalid` | Validation failure of an edit (settings, tags, playlist, art, user name, unknown root or role). `details` may list the problems |
| 400 | `weak_password` | New password violates the policy |
| 401 | `unauthenticated` | No/expired session, or the user was disabled/deleted |
| 401 | `invalid_credentials` | Wrong user name or password at login |
| 401 | `invalid_remember` | Missing, used, unknown or expired remember-me token |
| 403 | `csrf` | Missing/invalid CSRF token, missing `X-Requested-With` on a public POST, cross-origin write |
| 403 | `password_change_required` | `mustChange` is set |
| 403 | `wrong_password` | Current password wrong on password change |
| 403 | `forbidden` | The caller's role is not allowed (an admin endpoint called by a user) |
| 403 | `forbidden_root` | The library root is not among the caller's allowed roots |
| 404 | `not_found` | Unknown route/method, directory, track, playlist, picture, user, or (for rescan) unknown directory |
| 409 | `user_exists` | User name taken |
| 409 | `last_admin` | The change would leave no active administrator |
| 409 | `self_action` | An admin tried to delete, demote or disable themselves |
| 409 | `not_editable` | Tags of this format cannot be written (Opus, raw `.aac`) |
| 409 | `conflict` | Edit conflict (reserved by the curation layer) |
| 409 | `scan_running` | A scan is already running |
| 409 | `sync_conflict` | "From tags": tracks disagree. `details.conflicts` lists them |
| 410 | `invalid_invite` | Invite token unknown, already used or expired (older than 7 days) |
| 413 | `too_large` | Body over 12 MiB |
| 429 | `too_many_attempts` | Login / credential throttle |
| 500 | `io_error` | File could not be written/read (read-only mount, permissions); message names the file |
| 500 | `internal_error` | Unexpected exception (logged) |

## Auth

| Method + path | Auth | Body | Response |
|---|---|---|---|
| `GET /auth/session` | none | - | Session status |
| `POST /auth/login` | none (needs `X-Requested-With`) | `{"username","password","remember"?}` | `200` status + `Set-Cookie` (`kd_session`, and `kd_remember` when requested); `401 invalid_credentials`; `403 csrf`; `429` |
| `POST /auth/logout` | cookie + CSRF token (if a session exists) | - | `204`; both cookies expired (`Max-Age=0`); the remember-me token is revoked. With no/expired session it is still `204` |
| `POST /auth/refresh` | the `kd_remember` cookie (needs `X-Requested-With`) | `{}` | `200` status + new `kd_session` and rotated `kd_remember`; `401 invalid_remember`; `429` |
| `GET /auth/invite/{token}` | none | - | `{"username","displayName","minPasswordLength"}` or `410 invalid_invite` |
| `POST /auth/invite/accept` | none (needs `X-Requested-With`) | `{"token","password","remember"?}` | `200` status, signed in. The token is consumed. `400 weak_password` (the invite stays valid), `410 invalid_invite`, `429` |

Session status:

```json
{ "authenticated": true, "id": "6f1c...", "username": "admin", "displayName": "admin", "role": "admin",
  "mustChange": false, "allowedRoots": [], "downloadAllowed": true,
  "csrfToken": "f2l...", "minPasswordLength": 10 }
```

Unauthenticated: `{ "authenticated": false }`. `allowedRoots` is empty for admins (they see all roots) and
`downloadAllowed` is always true for them. The password hash work is done even for a wrong user name.

## Me

Allowed while `mustChange` is set.

| Method + path | Body | Response |
|---|---|---|
| `GET /me` | - | Session status of the caller |
| `POST /me/password` | `{"currentPassword","newPassword"}` | `200` status; `403 wrong_password`; `400 weak_password`; `429`. Needs the CSRF token |

## Library (any signed-in user)

The read facade of the web player (plan section 6, package `libraryquery`). Read-only (`GET`; other methods `404`), for
roles `user` and `admin`. **Everything is confined to the caller's allowed roots** (admins: all roots) in one place
(`LibraryQuery`): lists, groupings, search, recently added and random are computed over the caller's root set only, and an
explicit `path`/`scope` outside it answers `403 forbidden_root` (before existence is revealed). The hand-written
OpenAPI description is served at `GET /api/v1/openapi.yaml` (public; source `src/main/resources/openapi/openapi.yaml`).

Conventions of all `/library` responses:

* `snapshot`: the catalog version (`updateId`). When it differs from the one the UI holds, the library changed: refresh.
* Pagination: `offset` (default 0) and `limit` (default 50, max 200); responses carry `total`, `offset`, `limit` and the
  page under `items` (or `children`, `tracks`, `albums` where stated). Invalid values -> `400 bad_request`.
* `formats` (optional, comma separated, default `mp3,ogg,flac,wav,opus`): formats the client plays natively; it decides
  the `playability` of track objects.
* `art` fields are ready-made URLs of the artwork endpoint (`null` when there is no picture); append `&size=`.

| Method + path | Response |
|---|---|
| `GET /library/roots` | `{ snapshot, roots: [ { path, name, displayName, art } ] }` - the caller's roots |
| `GET /library/folders?path=` (alias `/library/folders/contents`) | Folder page, see below |
| `GET /library/albums`, `/artists`, `/genres` | Library-wide groups over the caller's roots: `{ snapshot, total, offset, limit, items: [group] }` |
| `GET /library/albums/{id}` | `{ item, total, offset, limit, tracks: [track] }` - tracks in disc/track order |
| `GET /library/artists/{id}`, `/genres/{id}` | `{ item, allTracks?, total, offset, limit, albums: [group] }`; an album id below them (`vrX.vaY`) lists that album's tracks |
| `GET /library/{albums\|artists\|genres}/{id}/tracks` | All tracks of the node, paginated (`tracks`) |
| `GET /library/songs` | All tracks of the caller's roots by title: `{ items: [track], total, ... }` |
| `GET /library/tracks?path=` | `{ snapshot, track }` |
| `GET /library/search?q=&kind=&scope=` | Grouped search, see below |
| `GET /library/search/suggest?q=&limit=` | Typeahead: `{ suggestions: [ { type: artist\|album\|track\|folder, label, detail, view+id \| path, art } ] }` |
| `GET /library/recent?since=` | Recently added: tracks newest first by first-seen `addedAt` (`since` = epoch ms) |
| `GET /library/random?path=&count=&seed=` | Seeded random tracks, see below |
| `GET /library/artwork?kind=&path=&size=` | Picture, see below |

Grouping follows the phase-1 rules (album key = album artist + title with the artist appended on title clashes; artist =
album artist, else track artist, multi-value split; normalization of case/accents/whitespace; most frequent spelling is
shown; A-Z buckets are never used here), independent of the `views` in `settings.json`, with the folder's `album` overlay
applied. Results are memoized per (catalog snapshot, set of roots).

Group item: `{ id, view: albums|artists|genres, type: album|artist|genre|all_tracks, title, artist, year, trackCount,
albumCount?, durationMillis, art }`. The `id` is stable for a catalog state and only valid in the view it came from; an
id that does not exist in the caller's roots is `404`.

Folder page (`GET /library/folders`): `{ snapshot, path, name, displayName, isRoot, parent, sort, album: {title,
albumArtist, year, genre, discNumber, discTotal}, random, recent, views, art, trackCount, folderCount, total, offset,
limit, virtual, children: [ {type:"folder", path, name, displayName, virtual, art} | {type:"track", path, track} ] }`. `children` are in the
`settings.sort` order; `random` is the directory's Random N (use `/library/random?path=`), `views` its configured views.

Track object (library): `{ path, name, title, artist, album, albumArtist, genre, composer, transcriber, year, trackNo, discNo,
discTotal, format, durationMillis, bitrateKbps, sampleRate, channels, size, addedAt, playability: native|transcoded,
streamFormat, art }`. `album`, `albumArtist`, `year`, `genre`, `discNo`, `discTotal` have the folder's album overlay
applied. `playability` is `native` when `format` (lower case: `mp3 ogg flac wav aiff midi opus aac alac adts`) is in
`formats` and not MIDI; else `transcoded` and `streamFormat` is `mp3`. `addedAt` is the persisted first-seen timestamp in
epoch ms (0 = unknown; kept across rescans and tag edits).

`segment` is `null` for normal files. For a track of a cue sheet or embedded chapters (a *virtual track*, see
[features.md](features.md#cue-sheets-and-chapters-virtual-albums)) it is `{ source, index, origin, startMillis, endMillis }`:
the source file path, the 1-based section number, `cue` | `tag-cue` | `chapters`, and the range played inside the
source. The track's `path` is `<source>#<index>`, usable everywhere a track path is (favorites, playlists, `/stream`,
`/library/track`); `playability` is always `transcoded` (`streamFormat` `mp3`), the stream has the exact length of the
range. Its parent folder is the virtual album `<source>` (`virtual: true`, listed in its directory like a
subdirectory). The track is read-only: tag edits answer `409` `not_editable`; `editable` is false. The whole source file
is not a track unless the directory's `settings.json` has `"keepWholeFiles": true`; `GET /stream/download` of a virtual
track downloads the whole source file.

Search: `q` is required (`400` when blank); every word must match some field. `kind=all` (default) returns the groups
`tracks`, `albums`, `artists`, `folders`, each `{ total, offset, limit, items }` with the first `limit` (default 10, max 50)
hits; `kind=track|album|artist|folder` returns that group only, paged by `offset`/`limit`. `scope` limits to a directory
subtree (checked like `path`). Albums/artists are the library-wide groups over the caller's roots whose title (and artist)
match; track and folder hits come from the core search engine in display order.

Random: picks from all of the caller's roots, or from the subtree of `path`. `count` (1-1000; default: the directory's
Random N, else 50), `seed` (any integer; omitted -> a new one). The response carries `seed` and `count`; the same seed
gives the same tracks and order for the same user and catalog, so a client can page through them with `offset`/`limit`.
The seed is mixed with the user id, so users get different picks for the same seed.

Artwork: `kind=track|folder`, `path`, `size=small|medium|large` (160 / 300 / 800 px longest edge, default `medium`, never
upscaled, always JPEG). `ETag` + `If-None-Match` -> `304`; `Cache-Control: private, no-cache`. `404` when there is no
picture, `403 forbidden_root` outside the allowed roots. Thumbnails are cached on disk per source and size.

## Stream (any signed-in user)

Web playback (plan section 7, `adminweb/StreamResources`): the browser's audio element requests these with the session
cookie (same origin). Independent of the DLNA `/stream/...` URLs, but it shares the core: catalog, transcoding, transcode
cache, the global transcode pool. For roles `user` and `admin`; the **allowed-roots check runs on every request**
(`403 forbidden_root`, also for paths that do not exist). Tracks are addressed by their root-qualified `path` query
parameter, as in `/library`.

| Method + path | Response |
|---|---|
| `GET\|HEAD /stream?path=[&formats=]` | Audio bytes (negotiated form), `Accept-Ranges: bytes`, exact `Content-Length`, Range -> `206` (or `416` with `Content-Range: bytes */size`) |
| `GET\|HEAD /stream/download?path=` | The **original** file with `Content-Disposition: attachment; filename="..."; filename*=UTF-8''...`; needs the download permission (else `403 forbidden`); Range supported; also MIDI originals |
| `POST /stream/formats` | Body `{ "formats": ["mp3","flac",...] }` (or a bare array): the formats the browser plays (`canPlayType`), stored for the session; returns `{ formats }` as stored (unknown names dropped). `GET /stream/formats` shows them |
| `POST /stream/prefetch` | Body `{ path, formats? }` -> `202 { prefetching, reason }`; see below |

**Format negotiation.** The list used is, in order: the `formats` query parameter (comma separated), the list posted to
`/stream/formats` for this login session, else `mp3,ogg,flac,wav,opus`. Format names are the lower-case ones of track
objects (`mp3 ogg flac wav aiff opus aac alac adts`). The server sends the **original** when the track's format is in the
list (the same rule as `playability: native` in `/library`), else an **MP3 transcode** at the configured bitrate; **MIDI
is always the MP3 rendering**. With `transcode.enabled=false` the original is sent anyway (MIDI then answers `415
unsupported_format`). Response headers: `X-Stream-Format` (`mp3`, `flac`, ...) and `X-Stream-Transcoded` (`true|false`);
`Content-Type` is that of the chosen form. The DLNA headers are not sent.

**Ranges and lengths.** `Content-Length` is exact for transcodes too (frame-exact MP3), so seeking works. A transcode
that is cached is served byte-identically for any range; a live one is encoded from a fresh frame at the requested
offset. MIDI is rendered into the cache before the first byte (the response can take a few seconds the first time).

**Limits.** At most **2 concurrent transcodes per user** (live encodes and MIDI renders; cached transcodes, native
streams, HEAD and other users do not count): the third answers `429 too_many_transcodes` (`details.limit`). The global
pool is shared with DLNA. Open web streams are listed in `/admin/streams` with container `web`.

**Prefetch.** The player sends the hint for the next queue item. The answer is `202 { "prefetching": true|false,
"reason": "queued" | "cached" | "native" | "disabled" }`: the transcode cache is only warmed when the negotiated form is a
transcode that is not cached yet (`native`: nothing to do; `disabled`: `transcode.prefetch=false`). A newer hint of the
same user replaces a still-pending older one. Roots are checked before anything else (`403`).

Errors: `400` missing/invalid `path`, `401`, `403 forbidden_root | forbidden`, `404` unknown track or file gone,
`415 unsupported_format`, `429 too_many_transcodes`.

## Personal data (any signed-in user)

`/me/playlists|favorites|queue|history`, `/shared/...` and the admin moderation endpoints. Data lives in
`<data>/users/<userId>/` (`playlists/<id>.json`, `favorites.json`, `queue.json`, `history.json`), written atomically
(temp file + rename), loaded lazily and evicted after 15 idle minutes. Deleting a user removes the folder, their shared
playlists vanish. These endpoints need a settled password (`403 password_change_required`) and the CSRF token on writes.

**Viewer filtering.** Whoever looks at a playlist, favorites, queue or history sees only entries inside their allowed
roots; the rest is counted in `unavailable` and never streamable. The owner's own view of their playlists/favorites keeps
such entries as `{index, path, status:"no_access"}` (no `track`), so they come back when access is restored. New entries
must come from the editor's allowed roots (`403 forbidden_root`, checked before existence) and exist in the library
(`404 not_found`). Copying copies only entries the copier can access.

**Versions.** Playlists, favorites and the queue carry an integer `version` (also the `ETag`, e.g. `"3"`), bumped by every
change. A write may send `If-Match: "3"` (stale -> `412 precondition_failed`) or `"version": 3` in the body (stale ->
`409 conflict`); both answer `details.currentVersion`. No version = no check (queue: last writer wins). `GET` honors
`If-None-Match` (`304`). The UI reloads and re-applies on 412/409.

Entry: `{ "index": 0, "path": "main/A/01.flac", "status": "ok|missing|no_access", "track": { "path", "name", "title",
"artist", "album", "albumArtist", "genre", "year", "trackNo", "discNo", "format", "durationMillis", "addedAt", "art" } }`
(`art`: artwork URL, `null` without a picture; no `playability`: the browser derives it from its own formats).
`index` is the position in the stored list. Lists are paged with `offset` (0) and `limit` (200, max 1000) and answer
`total`, `unavailable`, `snapshot`.

### Playlists (own)

| Method + path | Body | Response |
|---|---|---|
| `GET /me/playlists` | - | `{playlists:[{id, owner, mine, name, description, shared, count, unavailable, version, created, updated}]}` |
| `POST /me/playlists` | `{name, description?, tracks?:[paths]}` | `201` detail |
| `GET /me/playlists/{id}[?offset&limit]` | - | detail = summary + `{total, offset, limit, entries, snapshot}`, `ETag` |
| `PUT /me/playlists/{id}` | `{name?, description?, tracks?, version?}` | `200` detail; `tracks` = full new ordered list (reorder/remove) |
| `PUT /me/playlists/{id}/tracks` | `{paths, version?}` | `200` detail; replaces the order |
| `POST /me/playlists/{id}/tracks` | `{paths, position?, version?}` | `200` detail; appends/inserts |
| `DELETE /me/playlists/{id}` | - (`If-Match`) | `204` |
| `POST /me/playlists/{id}/share` / `unshare` | optional `{version}` | `200` detail |
| `POST /me/playlists/{id}/copy` | optional `{name}` | `201` detail of the private copy |
| `POST /me/playlists/copy` | `{source:"shared", owner, id, name?}` or `{source:"system", key, name?}` | `201` detail |
| `GET /me/playlists/{id}/export[?format=m3u8|m3u|pls&root=false]` | - | the playlist as a file (`Content-Disposition: attachment`); entries are catalog paths (`Root/dir/file.flac`, or `dir/file.flac` with `root=false`) with `#EXTINF` / `Title` lines; tracks outside the caller's roots or gone from the catalog are left out. `400` for another format |
| `POST /me/playlists/import[?name=&filename=]` | the M3U / M3U8 / PLS file as the raw request body (UTF-8, or Latin-1 for old M3U) | `201 {playlist (detail), imported, total, unmatched:[{entry, location, title, reason}]}`; the name is `name`, else `filename` without extension, else `Imported playlist` |
| `POST /me/playlists/{id}/import` | the file as the body | `200` same shape, the matches are appended |
| `POST /me/playlists/{id}/fix-path` | `{index, path, version?}` | `200` detail; replaces the entry (new path must exist, allowed) |

Import matches each entry to a track of the caller's allowed roots: an exact catalog path first, else the longest tail of the entry's path (folders written by another program or machine, Windows paths, `file://` URLs, any case) that identifies exactly one file. `reason` of an unmatched entry is `not_found`, `ambiguous` (several files fit) or `not_a_file` (a stream URL). `400` when the file is empty or nothing matched; nothing is created then.

Limits: 500 playlists (`409 too_many`), 20 000 tracks, name 1-200 chars, description <= 1000. A foreign or unknown id is
`404`.

### Favorites

`GET /me/favorites` -> `{version, updated, total, unavailable, offset, limit, entries, snapshot}`;
`POST /me/favorites {paths}` (append, duplicates skipped); `DELETE /me/favorites` with `{paths}` or `?path=`;
`PUT /me/favorites {paths}` (full ordered list). All writes answer the new favorites; `version`/`If-Match` as above.

### Queue

`GET /me/queue` -> `{version, items:[entries], index, positionSeconds, shuffle, repeat:"off|all|one", source, deviceId,
updated, unavailable}`. Items outside the viewer's roots are dropped and `index` is moved to the next accessible item
(`positionSeconds` becomes 0 when the current item was dropped).
`PUT /me/queue {items:[paths], index, positionSeconds, shuffle, repeat, source?:{...}, deviceId?, version?}` replaces it
(<= 5000 items, `403 forbidden_root`, `400` for a bad `repeat`; `index` is clamped) and answers like `GET`. The UI shows
"playing on another device" when `deviceId`/`updated` change underneath.

### History

| Method + path | Body | Response |
|---|---|---|
| `POST /me/history` | `{path, playedSeconds, durationSeconds?, force?}` | `{counted, playCount?}`; counts when `playedSeconds` >= 30 or >= 50 % of the duration (body, else catalog), or when `force` is true and `playedSeconds` > 0 (the player sends it on pause/stop); `403` outside roots; `429 rate_limited` with `Retry-After` beyond 30 reports/minute/user |
| `GET /me/history/recent[?distinct=true&offset&limit]` | - | newest first, each track once unless `distinct=false`; entries `{path, status: "ok", playedAt, track}` |
| `GET /me/history/most-played[?offset&limit]` | - | by play count desc; entries add `playCount` |
| `DELETE /me/history/recent?path=` | - | `204`; removes the track from Recently played (the play count stays) |
| `DELETE /me/history/most-played?path=` | - | `204`; resets the track's play count, so it leaves Most played |

The history keeps the last 1000 plays plus a play count per track; writes are batched (flushed after 5 s, on idle
eviction and at shutdown).

### Shared and system playlists

| Method + path | Response |
|---|---|
| `GET /shared/playlists` | shared playlists of all users: summary entries with `owner {id, displayName}`, `mine`, `count`/`unavailable` as seen by the caller |
| `GET /shared/playlists/{ownerId}/{id}` | detail (read-only); a private playlist is `404` |
| `POST /shared/playlists/{ownerId}/{id}/copy` | `201` detail of the caller's copy (accessible entries only) |
| `GET /shared/system` | system playlists (phase-1 `/playlists`): `{key, name, system:true, count, unavailable}` |
| `GET /shared/system/{key}` | detail, entries filtered by roots |
| `POST /shared/system/{key}/copy` | `201` detail of the caller's copy |

System playlists are read-only here; admins edit them through `/admin/playlists`.

### Moderation (admin)

The admin sees **shared** playlists only; private playlists, favorites, queue and history of users are not reachable.

| Method + path | Response |
|---|---|
| `GET /admin/shared-playlists` | `{playlists:[{id, owner{id,username,displayName}, name, description, count, version, updated}]}` |
| `POST /admin/shared-playlists/{ownerId}/{id}/unshare` | `204` (back to private, version bumped) |
| `DELETE /admin/shared-playlists/{ownerId}/{id}` | `204` |

A playlist that is not shared answers `404` here.

## User management (admin)

| Method + path | Body | Response |
|---|---|---|
| `GET /admin/users` | - | `{ "users": [user], "roots": ["Music", ...], "mainRoot": "Music" }` |
| `GET /admin/roots` | - | `{ "roots": [...], "mainRoot": "..." }` |
| `POST /admin/users` | `{"username","displayName"?,"role"?,"allowedRoots"?,"downloadAllowed"?,"credential"?}` | `201 { "user", "invite" }` or, with `"credential":"temporaryPassword"`, `201 { "user", "temporaryPassword" }`. `role` `user` (default) or `admin`; `allowedRoots` default `[mainRoot]`, unknown roots `400 invalid`; `downloadAllowed` default false; `credential` `invite` (default) or `temporaryPassword`. `409 user_exists`; user names are 2-64 chars of letters, digits, `.` `_` `-` `@` |
| `GET /admin/users/{id}` | - | `{ "user" }` |
| `PUT /admin/users/{id}` | any of `displayName`, `role`, `allowedRoots`, `downloadAllowed`, `enabled` | `{ "user" }`. Disabling or changing the role ends the user's sessions at once. `409 last_admin`, `409 self_action` |
| `DELETE /admin/users/{id}` | - | `204`; removes the user, sessions, tokens, invites and `<dataDir>/users/<id>/`. `409 last_admin`, `409 self_action` |
| `POST /admin/users/{id}/invite` | `{}` | `{ "user", "invite" }`: a new invite link (the old one dies); the user's password is retired and all sessions end |
| `POST /admin/users/{id}/temporary-password` | `{}` | `{ "user", "temporaryPassword" }`: fallback when a link cannot be delivered; `mustChange` is set, sessions end, shown once |

User object:

```json
{ "id": "uuid", "username": "lena", "displayName": "Lena", "role": "user", "enabled": true, "mustChange": false,
  "hasPassword": true, "pendingInvite": false, "allowedRoots": ["Music"], "downloadAllowed": false,
  "createdAt": "2026-01-01T00:00:00Z", "lastLogin": null }
```

Invite object: `{ "token", "expiresAt", "path": "/app/#/invite/<token>" }`. The token is shown once (only its hash is
stored); invites are valid for 7 days and one-time. Give the user `http://<host>:8200/app/#/invite/<token>`.
Password hashes are never returned.

## Folders

All the sections below are under `/api/v1` (admin only): `path` = directory path (`Root` or `Root/dir/sub`). Unknown directory -> `404 not_found`; invalid path syntax ->
`400 bad_request`.

### `GET /api/v1/admin/folders/tree`

No parameters. The whole directory tree (no tracks).

```json
{ "roots": [ { "path": "Music", "name": "Music", "displayName": "Music",
               "trackCount": 0, "settingsError": false, "outOfSync": false,
               "children": [ { "path": "Music/Album", "name": "Album", "displayName": "Album (CD 1)",
                               "trackCount": 12, "settingsError": false, "outOfSync": true, "children": [] } ] } ] }
```

`displayName` is the DLNA title (`settings.displayName`, else `album.title`, plus ` (CD n)`, else directory name).
`trackCount` = direct tracks. `settingsError` is a boolean here. `outOfSync`: the `album` overlay differs from the tags
of at least one track that can hold album tags (MIDI, Opus and raw AAC never count).

### `GET /api/v1/admin/folders/contents?path=`

Directory detail with its children in display order (the order of `settings.sort`).

```json
{ "path": "Music/Album", "name": "Album", "displayName": "Album",
  "isRoot": false,
  "settings": { "sort": "track", "order": [], "album": { "title": null, "albumArtist": null, "year": null,
                "genre": null, "discNumber": null, "discTotal": null },
                "random": 0, "recent": 0, "views": [], "displayName": null },
  "settingsError": null, "outOfSync": false, "hasFront": true, "hasRear": false,
  "hasList": false, "listError": null, "missingOrderEntries": [], "trackCount": 2, "folderCount": 1,
  "children": [
    { "path": "Music/Album/Disc 2", "name": "Disc 2", "displayName": "Disc 2", "type": "folder" },
    { "type": "track", "path": "Music/Album/01 a.flac", "name": "01 a.flac", "track": { "...": "see Track object" } } ] }
```

`settings` values: `sort` lower-case (`filename|title|track|mtime|explicit`), `views` lower-case
(`album|artist|genre|title|composer`), `album.*` and `displayName` `null` when unset. `settingsError` is the message
string (or `null`) when `settings.json` was malformed (defaults are then reported). `missingOrderEntries`: entries of
`order` that match no child (explicit sort).

### Track object

Used by contents, tracks, search and playlist entries.

| Field | Type | Notes |
|---|---|---|
| `path`, `name` | string | qualified path, file name |
| `displayTitle` | string | tag title, else file name without extension |
| `title`, `artist`, `album`, `albumArtist`, `genre`, `composer` | string or null | raw tags (the album overlay is **not** applied here); first value of multi-value tags; `title`, `artist`, `album` already carry the directory's `list.json` values |
| `transcriber` | string or null | from `list.json` |
| `year`, `trackNo`, `discNo`, `discTotal` | int or null | |
| `format` | string | `mp3 ogg flac wav aiff midi opus aac alac adts` |
| `editable` | bool | tags can be written at all (false for `opus`, `adts`) |
| `editableFields` | string[] | writable tag fields: all ten, only `title, artist, year` for `midi`, `[]` for opus/adts |
| `durationMillis`, `bitrateKbps`, `sampleRate`, `channels` | number | 0 = unknown |
| `embeddedArt` | bool | |
| `size`, `mtime` | number | bytes, epoch milliseconds |
| `outOfSync` | bool | differs from the parent directory's `album` overlay |

### `GET /api/v1/admin/folders/settings?path=`

`{ "settings": {...}, "settingsError": null | "message" }`.

### `PUT /api/v1/admin/folders/settings?path=`

Validates and writes `settings.json` atomically (unknown keys of a valid existing file are preserved; defaults are not
written: empty `order`, empty `album`, `random`/`recent` 0, null `displayName`, empty `views` remove their keys).
The catalog is updated at once (no rescan).

Body: the `settings.json` shape (see [features.md](features.md#settingsjson-reference)):

```json
{ "displayName": "Greatest Hits", "sort": "explicit", "order": ["Intro.flac", "Live"],
  "album": { "title": "Greatest Hits", "albumArtist": "Some Band", "year": 1999, "genre": "Rock",
             "discNumber": 1, "discTotal": 2 },
  "random": 25, "recent": 50, "views": ["album", "artist"] }
```

Response `200 { "settings": {...}, "settingsError": null }`. Errors: `400 invalid` (not an object, bad `sort`/`views`/types,
`order` entry empty or containing `/` or `\`, `random`/`recent` > 100000, `displayName` > 255 characters,
`album.year` outside 0-9999, `album.discNumber`/`discTotal` outside 0-999; several problems are joined with `; ` and
listed in `details`), `404 not_found` (unknown directory or missing on disk), `500 io_error` (not writable).

### `GET /api/v1/admin/folders/list?path=`

The directory's `list.json` ([reference](features.md#listjson-reference)) for the editor. Admin only.

```json
{ "exists": true, "version": "9f2c4a7d1b3e5f60a1b2c3d4",
  "entries": [ { "filename": "franpani.mid", "index": null, "start": null, "author": "FRANCK, César (1822-90)",
                 "partOf": null, "title": "Panis Angelicus", "transcriber": "F.Raborn" },
               { "filename": "disc.flac", "index": 2, "start": "03:27.500", "author": "Guest",
                 "partOf": "Live Album", "title": "Middle", "transcriber": null } ],
  "files": ["franck0.mid", "franpani.mid"], "missing": ["franck0.mid"], "unknown": [],
  "warnings": [], "error": null }
```

`version` is a content hash (`"none"` when the file does not exist). `index` / `start` are `null` for whole-file entries;
with them an entry is about one track of a cue / chapter split file ([sections](features.md#listjson-reference)).
`files`: the directory's audio tracks plus the files split into virtual albums (natural order); `missing`: those without an entry; `unknown`: entries whose file is not a track of the directory;
`warnings`: skipped or repaired entries; `error`: set (and `entries` empty) when the file cannot be parsed at all.
Errors: `404 not_found`, `400 bad_request` (no `path`).

### `PUT /api/v1/admin/folders/list?path=`

Replaces the entries and writes `list.json` atomically (temp file + rename). The tracks of the directory are re-read
and merged at once (no rescan), the update ids move so DLNA clients refresh.

```json
{ "version": "9f2c4a7d1b3e5f60a1b2c3d4",
  "entries": [ { "filename": "franpani.mid", "author": "FRANCK, César (1822-90)", "partOf": "",
                 "title": "Panis Angelicus", "transcriber": "F.Raborn" } ] }
```

* `version` (optional): the `version` of the last read; if the file changed since, nothing is written
  (`409 conflict`, `details.version` = current). Omitted = overwrite.
* Each entry may carry `index` (whole number >= 1; a numeric string is accepted) and `start` (`mm:ss.fff`, stored
  normalized); they are optional, written only when set, and do not count as metadata. The identity of an entry is
  file + `index`, else file + `start`, else the file alone (case-insensitive); two entries with the same identity are a
  duplicate. Entries for the sections of a split file take effect at once.
* Entries without any metadata are dropped; no entries left = the file is deleted. `filename` is matched
  case-insensitively and stored with the on-disk spelling.
* Errors: `400 invalid` (empty or path-like `filename`, no such file in the directory - unless it is already in the
  file -, duplicate `filename`, a field over 500 characters, more than 20000 entries; problems are joined with `; ` and
  listed in `details`; also `index` < 1 or `start` not a time), `400 bad_request` (`entries` missing / not objects,
  `index` not a whole number), `404`, `409 conflict`, `500 io_error`. Response: the same shape as GET.

### `POST /api/v1/admin/folders/list/generate?path=` ("Generate from cue/chapters")

Preview only, nothing is written and the current `list.json` is not consulted. Admin only; needs the CSRF token like every
write. No body. Reads the cue sheets and embedded chapters of the directory's audio files and proposes one entry per
virtual track, plus one whole-file entry for a cue sheet with a single track.

```json
{ "entries": [ { "filename": "disc.wav", "index": 2, "start": "00:03.493", "author": "Guest",
                 "partOf": "Live Album", "title": "Middle", "transcriber": null } ],
  "sources": [ { "filename": "disc.wav", "origin": "cue", "cueFile": "disc.cue", "tracks": 3 } ],
  "warnings": [] }
```

`origin` is `cue`, `tag-cue` or `chapters` (`cueFile` is null unless `cue`); `warnings` are problems found while reading
(unreadable file, section beyond the end, ...). The entries are exactly what the sections say (title, track performer,
album, transcriber from the file tags); send the ones you want to keep to `PUT /admin/folders/list`. Errors:
`400 bad_request` (no `path`), `404 not_found`.

`GET /admin/folders/list` also returns `"sidecars"`: the names of the files of the directory that have a usable
per-file sections list (below); the directory's entries for those files are ignored.

### Per-file sections list: `GET|PUT /api/v1/admin/folders/list/file?path=` and `POST .../list/file/generate?path=`

The sections of **one audio file** (typically a big cue / chapter split flac or mp3) live in a sidecar next to it:
`<full file name>.json`, e.g. `concert.flac.json` ([format and precedence](features.md#per-file-sections-list-filejson)).
`path` is the **file** (`main/Live/concert.flac`; the cue / chapter split file itself, or any track of the catalog).
Admin only, writes need the CSRF token. Same entry shape, `version`/409 rules, validation and atomic write as
`/admin/folders/list`.

```json
{ "path": "main/Live/concert.flac", "fileName": "concert.flac", "sidecar": "concert.flac.json",
  "exists": true, "version": "9f2c4a7d1b3e5f60a1b2c3d4", "split": true,
  "entries": [ { "filename": "concert.flac", "index": null, "start": null, "author": "The Band",
                 "partOf": "Live Album", "title": null, "transcriber": null },
               { "filename": "concert.flac", "index": 2, "start": "03:27.500", "author": "Guest",
                 "partOf": "Live Album", "title": "Middle", "transcriber": null } ],
  "files": ["concert.flac"], "warnings": [], "error": null }
```

* `GET`: `exists` false and `version` `"none"` when there is no sidecar; `split` tells whether the catalog holds the file
  as a cue / chapter virtual album; `error` is set (and `entries` empty) when the sidecar cannot be parsed.
* `PUT` body `{ "version": "...", "entries": [...] }`: `filename` is optional per entry; when given it must be the file
  itself (`400 invalid` otherwise). Entries without metadata are dropped and **no entries left deletes the sidecar**. The
  file's album is rebuilt at once (update ids move). Errors: `400 invalid` (`index` < 1, bad `start`, duplicate identity,
  field over 500 characters), `400 bad_request`, `404 not_found` (unknown file, a directory, not an audio file of the
  catalog), `409 conflict` (`details.version` = current), `500 io_error`. Response: the same shape as GET.
* `POST .../list/file/generate`: preview only (nothing written, the sidecar is not consulted); same body as the directory
  generator but for this file only (`entries`, `sources`, `warnings`).

### `POST /api/v1/admin/folders/sync-to-tags?path=` ("Apply to tags")

Writes the directory's `album` overlay into the tags of the **explicitly chosen** files. There is no "write everything".

Body `{ "files": ["01 a.flac", "02 b.mp3"] }` - file names as listed in the directory (not paths).
Written fields: album, album artist, genre, year, disc number, disc total (only those set in the overlay).

```json
{ "results": [ { "path": "Music/Album/01 a.flac", "ok": true, "error": null } ],
  "skipped": [] }
```

`skipped`: chosen files whose format cannot hold album tags (MIDI, Opus, raw `.aac`). A per-file failure is reported in
`results[].error` (status stays 200). Errors: `400 bad_request` (`files` missing/not an array), `400 invalid` (names that
are not direct tracks of the directory, empty list, directory has no album fields, no chosen file can hold album tags),
`404`.

### `POST /api/v1/admin/folders/sync-from-tags?path=[&preview=true]` ("From tags")

Fills `album` from the tags of the directory's own tracks (title <- album tag, album artist, genre, year, disc number,
disc total). Blank values are ignored; values are compared normalized (case, accents, whitespace). With
`preview=true` (exactly that string) nothing is written and the would-be settings are returned; any other value (or none)
**writes** them.

* `200 { "settings": {...} }`
* `409 sync_conflict` when tracks disagree on a field; nothing is written:

```json
{ "code": "sync_conflict", "message": "The tracks disagree on 1 field(s); nothing was written",
  "details": { "conflicts": [ { "field": "genre",
      "values": [ { "value": "Rock", "files": ["01 a.flac"] }, { "value": "Pop", "files": ["02 b.flac"] } ] } ] } }
```

Conflict `field` names: `album`, `albumArtist`, `genre`, `year`, `discNumber`, `discTotal`.
* `400 invalid` when the directory has no tracks.

### `GET /api/v1/admin/folders/art?path=&kind=front|rear`

Returns the picture file (`front.jpg`/`rear.jpg`, or the best of the other accepted names such as `cover.png`) with its own image type (`Cache-Control: private, no-cache`). `404 not_found` if
absent. `kind` other than `front|rear` -> `400 bad_request`. (An extra `v=` query value, used by the UI to bust caches,
is ignored.)

### `PUT|POST /api/v1/admin/folders/art?path=&kind=front|rear`

Body: the raw image bytes (any `Content-Type`). Must decode as an image (JPEG stored as is, other formats converted to
JPEG on white); at most 10 MiB. Stored as `front.jpg` / `rear.jpg` in the directory. Response
`200 { "hasFront": true, "hasRear": false }`. Errors: `400 invalid` (empty, too large, not an image), `404`, `500 io_error`.

## Tracks

### `GET /api/v1/admin/tracks?path=`

One [track object](#track-object). `404 not_found` for an unknown track.

### `PUT /api/v1/admin/tracks?path=`

Writes tags of one file (temporary copy in the same directory, tag write, atomic rename) and returns the re-read track
object. Body = the edit:

```json
{ "title": "New title", "artist": "", "year": "1999", "trackNo": 3 }
```

Edit fields: `title artist album albumArtist genre composer year trackNo discNo discTotal`. **Absent or `null` = leave
unchanged; `""` = clear the tag.** Numbers may be sent as numbers or strings. Validation: text <= 500 characters, no
control characters; `year`, `trackNo` <= 9999, `discNo`, `discTotal` <= 999, digits only (max 4). Fields the format
cannot store are ignored (MIDI keeps only `title`, `artist`, `year`); if nothing applies the file is left untouched and
the unchanged track is returned.

Errors: `400 bad_request` (`edit` not an object / bad path), `400 invalid` ("Nothing to change", value problems;
MIDI-specific refusals such as a year outside 1900-2099, an artist without a title, RMID/broken structure),
`404 not_found` (unknown track or file missing on disk), `409 not_editable` (Opus, raw `.aac`), `500 io_error`.

### `POST /api/v1/admin/tracks/batch`

Same edit applied to many files.

```json
{ "paths": ["Music/A/01.flac", "Music/A/02.mid"], "edit": { "genre": "Rock" } }
```

Response `200`:

```json
{ "results": [ { "path": "Music/A/01.flac", "ok": true, "error": null },
               { "path": "Music/A/02.mid", "ok": true, "error": null } ], "failed": 0 }
```

Invalid input fails as a whole (`400 bad_request`: `paths` missing/empty/invalid path, `edit` not an object;
`400 invalid`: nothing to change, bad values). Per-file problems (unknown file, Opus/ADTS, unwritable) are reported in
`results[].error` while the other files are still written; all written files enter the catalog in one commit (one
SystemUpdateID bump, stale transcode cache entries dropped).

## Search

### `GET /api/v1/admin/search?q=&kind=all|track|folder&scope=`

Free-text search over the catalog (not the UPnP grammar; see [dlna.md](dlna.md#search)).

| Param | Meaning |
|---|---|
| `q` | Words; **every** word must be contained (normalized: case, accents, whitespace) in some field (title/file name, artist, album artist, album, genre, composer, file name, directory name; for directories: display title, directory name, display name, album title). Blank = everything |
| `kind` | `track`, `folder`, anything else = both |
| `scope` | Directory path to search below (the directory itself excluded); default whole library. Unknown directory -> no hits; malformed path -> `400 bad_request` |

Response (max 300 hits, display order):

```json
{ "total": 412, "truncated": true,
  "hits": [ { "type": "track", "path": "Music/A/01.flac", "track": { "...": "track object" } },
            { "type": "folder", "path": "Music/A", "name": "A", "displayName": "A" } ] }
```

`total` is the real number of matches; `truncated` is true when more than 300.

## Playlists

Playlists are JSON files `<playlistsDir>/<key>.json`; the `key` is the file name without `.json`. Changes are written
atomically and enter the catalog immediately (they are announced to DLNA clients).

Entry `status`: `ok`, `missing` (file not in the library), `unsupported` (file listed as unsupported). Broken entries
keep their place but are skipped by DLNA.

| Method + path | Body | Response |
|---|---|---|
| `GET /api/v1/admin/playlists` | - | `{ "playlists": [ { "key", "name", "count", "broken" } ] }` |
| `POST /api/v1/admin/playlists` | `{ "name" }` | `201` playlist detail |
| `GET /api/v1/admin/playlists/{key}` | - | playlist detail |
| `PUT /api/v1/admin/playlists/{key}` | `{ "name"?, "songs"? }` (at least one) | `200` detail. `songs` = complete new ordered list of qualified paths (reorder/remove); entries need only be well-formed (they may be missing -> `missing`). Applied before `name` |
| `DELETE /api/v1/admin/playlists/{key}` | - | `204` |
| `POST /api/v1/admin/playlists/{key}/songs` | `{ "paths": [...] }` | `200` detail; appends; every path must exist in the library |
| `POST /api/v1/admin/playlists/{key}/fix-path` | `{ "index": 3, "path": "Root/new/file.mp3" }` | `200` detail; replaces entry `index`; the new path must exist |

Detail:

```json
{ "key": "road-trip", "name": "Road trip", "broken": 1,
  "entries": [ { "index": 0, "path": "Music/A/01.flac", "status": "ok", "track": { "...": "track object" } },
               { "index": 1, "path": "Music/Gone.mp3", "status": "missing" } ] }
```

Rules: name required, trimmed, <= 200 characters, no control characters; the key is derived from the name (ASCII
lower-case, runs of other characters become `-`, de-duplicated with `-2`, `-3`, ...; fallback `playlist`); a key must
match `[A-Za-z0-9][A-Za-z0-9._-]{0,99}`; at most 20 000 songs; a song path must not be a root. Errors:
`400 invalid` (name, key, paths, `fix-path` index out of range), `400 bad_request` (`songs`/`paths` not an array of
strings, `index` not an integer, neither `name` nor `songs`), `404 not_found` (unknown key, unknown song path),
`500 io_error`.

New playlists created through the API are appended to the end of the DLNA list until the next scan, which sorts them by
name (natural order).

## Admin

All reads, except `rescan`.

### `POST /api/v1/admin/rescan`

Starts an asynchronous rescan. Optional body `{ "path": "Root/dir", "force": true }`: `path` limits the rescan to a
directory subtree (default whole library); `force` re-reads the tags of every file in scope (ignores the mtime+size
shortcut; first-seen `addedAt` is kept). `202 { "started": true, "force": false, "scope": null }`. `409 scan_running`
when a scan is in progress, `404 not_found` for an unknown/malformed directory (also for a malformed `path`).
An empty body is fine; a non-empty body must be valid JSON.

### `GET /api/v1/admin/scan`

```json
{ "running": false, "trigger": null, "scope": null, "startedAt": null,
  "directories": 0, "audioFiles": 0, "tracksReused": 0, "tracksRead": 0, "errors": [],
  "last": { "trigger": "MANUAL", "scope": null, "startedAt": "...", "finishedAt": "...",
            "directories": 120, "audioFiles": 3400, "tracksReused": 3390, "tracksRead": 10,
            "tracksBefore": 3395, "tracksAfter": 3400, "changedContainers": 4, "errors": [], "failure": null },
  "scannedAt": "2026-10-09T10:00:00Z", "trackCount": 3400 }
```

While running, the live counters are filled and `trigger` is `STARTUP|CATCH_UP|SCHEDULED|MANUAL`. `last` is `null`
before the first scan of this process; `failure` is the exception text of a failed scan (catalog unchanged).
`scannedAt` = last completed scan. Instants are ISO-8601.

### `GET /api/v1/admin/unsupported`

`{ "files": [ { "path", "size", "reason" } ] }` - audio files that are not served (no decoder, HE-AAC, MIDI without
SoundFont, unreadable, ...).

### `GET /api/v1/admin/invalid-settings`

`{ "folders": [ { "path", "error" } ] }` - directories whose `settings.json` is malformed.

### `GET /api/v1/admin/streams`

```json
{ "streams": [ { "id": 7, "client": "192.168.1.20", "container": "a1b2...", "item": "c3d4...",
                 "title": "Song", "path": "Music/A/01.flac", "variant": "mp3", "transcoded": true,
                 "startedAt": "..." } ],
  "transcodeCache": { "entries": 12, "sizeBytes": 123456789 },
  "prefetchJobs": 0 }
```

`variant` is `original` for a URL without variant segment, else the URL segment (`mp3`, `mp3-256`, `lpcm`, or
`native`). `transcoded` is computed as `variant != "original"`, so a URL with the explicit `native` segment is reported
as transcoded. `transcodeCache` is `null` when there is no cache.

### `GET /api/v1/admin/snapshots`

Random N snapshots: `{ "snapshots": [ { "context", "id", "directory", "clientIp", "clientAgent", "requestedSize",
"tracks", "createdAt", "lastActivity", "activeStreams" } ] }`.

### `GET /api/v1/admin/version`

`{ "version", "commit", "buildTime", "java", "startedAt" }` (`commit`/`buildTime` are `unknown` unless stamped at build).

## Network access and reverse proxy

One port serves everything, so the server filters **by path and client address** before any handler runs (plan section 9):

| Paths | Who may connect |
|---|---|
| `/api/v1/admin/**`, `/admin/**` (admin API and pages) | LAN addresses only (`403` otherwise), plus the admin login |
| `/upnp/**`, `/stream/**`, `/art/**` (DLNA) | LAN addresses only |
| `/api/v1/auth`, `/me`, `/shared`, `/library`, `/stream` (web), `/openapi.yaml`, `/health`, `/` | any address (login required where the API says so) |

"LAN" is `network.lanCidrs` (default: loopback, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `::1`, `fc00::/7`,
`fe80::/10`). The client address is the TCP peer unless the peer is in `network.trustedProxies`: only then are
`X-Forwarded-For` (walked from the right, skipping trusted proxies; the first other address is the client) and
`X-Forwarded-Proto` honored, and `Secure` cookies are set when the scheme is `https`. Headers from any other peer are
ignored, so a client cannot claim a LAN address. **Behind a reverse proxy you must list the proxy in
`network.trustedProxies`**: otherwise every request seems to come from the proxy, which is usually a private address, and
would be treated as LAN. See the README for an example. Details: [configuration.md](configuration.md#config-json-keys).

## Open endpoints (no login)

Served on the same port, outside `/api/v1`. `/admin/`, `/stream`, `/art` and `/upnp` are additionally **LAN-only** ([network access](#network-access-and-reverse-proxy)); `/health` and `/` are open. Details of the DLNA ones are in [dlna.md](dlna.md).

| Path | Methods | Description |
|---|---|---|
| `/health` | any | `200 {"status":"UP"}` (`application/json`). Used by the Docker health check |
| `/` | any | Redirect to `/admin/`. Any other unmapped path is 404 |
| `/admin/` | GET, HEAD | The web UI (static files from the jar; `/admin` redirects to `/admin/`; other methods `405 Allow: GET, HEAD`). HTML responses carry a Content-Security-Policy |
| `/stream/{containerId}/{itemId}[/{variant}]` | GET, HEAD | Audio bytes. Variants `native` (default), `mp3`, `mp3-<kbps>`, `lpcm`. Range supported; `405` for other methods. See [dlna.md](dlna.md#stream-urls) |
| `/art/t/{trackId}[/tn]`, `/art/f/{folderId}[/tn]` | GET, HEAD | Cover art, optionally as 160 px JPEG thumbnail; ETag/304. See [dlna.md](dlna.md#artwork-urls) |
| `/upnp/...` | GET, POST, SUBSCRIBE | jUPnP: device description, SCPDs, SOAP control, GENA events. See [dlna.md](dlna.md#device-and-services) |

## curl walkthrough

Assumes `jq` and a server at `http://localhost:8200`. The cookie jar keeps `kd_session`; the CSRF token is read from
the login response.

```sh
B=http://localhost:8200/api/v1
# 1. login (first start: the generated password is in the server log)
curl -s -c jar -H 'X-Requested-With: curl' -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"GENERATED"}' $B/auth/login | tee login.json
CSRF=$(jq -r .csrfToken login.json)        # "mustChange": true -> only the next step works

# 2. forced password change (min 10 characters)
curl -s -b jar -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
     -d '{"currentPassword":"GENERATED","newPassword":"a-new-long-password"}' $B/me/password

# 3. browse and edit directory settings (-G + --data-urlencode encodes the path)
curl -s -b jar -G --data-urlencode 'path=Music/Rock/Album' $B/admin/folders/contents | jq '.settings, .children[0]'
curl -s -b jar -X PUT -G --data-urlencode 'path=Music/Rock/Album' \
     -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
     -d '{"sort":"track","album":{"title":"Album","year":1999},"random":20,"recent":10,"views":["album","artist"]}' \
     $B/admin/folders/settings

# 4. edit tags (single file, then a batch)
curl -s -b jar -X PUT -G --data-urlencode 'path=Music/Rock/Album/01 Song.flac' \
     -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
     -d '{"title":"Song","trackNo":1,"genre":""}' $B/admin/tracks
curl -s -b jar -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
     -d '{"paths":["Music/Rock/Album/01 Song.flac","Music/Rock/Album/02 Other.flac"],"edit":{"year":"1999"}}' \
     $B/admin/tracks/batch

# 5. write the album overlay into chosen files, or fill the overlay from the tags (preview, then write)
curl -s -b jar -X POST -G --data-urlencode 'path=Music/Rock/Album' \
     -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
     -d '{"files":["01 Song.flac"]}' $B/admin/folders/sync-to-tags
curl -s -b jar -X POST -G --data-urlencode 'path=Music/Rock/Album' --data-urlencode 'preview=true' \
     -H "X-CSRF-Token: $CSRF" $B/admin/folders/sync-from-tags

# 6. cover picture
curl -s -b jar -X PUT -G --data-urlencode 'path=Music/Rock/Album' --data-urlencode 'kind=front' \
     -H "X-CSRF-Token: $CSRF" --data-binary @cover.png $B/admin/folders/art

# 7. rescan (whole library, forced re-read of one subtree) and follow it
curl -s -b jar -X POST -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
     -d '{"path":"Music/Rock","force":true}' $B/admin/rescan
curl -s -b jar $B/admin/scan | jq '{running, tracksRead, tracksReused, errors}'

# 8. playlists
curl -s -b jar -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' -d '{"name":"Road trip"}' $B/admin/playlists
curl -s -b jar -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
     -d '{"paths":["Music/Rock/Album/01 Song.flac"]}' $B/admin/playlists/road-trip/songs
curl -s -b jar -X PUT -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
     -d '{"songs":["Music/Rock/Album/01 Song.flac"],"name":"Road trip 2"}' $B/admin/playlists/road-trip
curl -s -b jar -X DELETE -H "X-CSRF-Token: $CSRF" $B/admin/playlists/road-trip

# 9. search, logout
curl -s -b jar -G --data-urlencode 'q=pink floyd' --data-urlencode 'kind=track' $B/admin/search | jq .total
curl -s -b jar -X POST -H "X-CSRF-Token: $CSRF" $B/auth/logout
```

Open endpoints:

```sh
curl http://localhost:8200/health
curl -I http://localhost:8200/stream/<folderId>/<trackId>              # HEAD: headers only
curl -H 'Range: bytes=1000-1999' -o part.bin http://localhost:8200/stream/<folderId>/<trackId>/mp3
curl -o thumb.jpg http://localhost:8200/art/f/<folderId>/tn
```
