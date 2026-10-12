# Configuration reference

Everything the server reads at start-up. Derived from `platform/AppConfig`, `platform/ConfigLoader`, `platform/Main`,
`clients/*`. A complete example is `config/config.example.json`. Changes need a restart (profiles included).

Contents: [where the config lives](#where-the-config-lives) - [overrides](#overrides-system-properties-and-environment) -
[paths](#relative-paths) - [keys](#configjson-keys) - [clients section](#clients-section) -
[validation and failure](#validation-and-failure) - [logging](#logging) - [directory layout](#directory-layout)

## Where the config lives

* Config directory: `/config` by default; set with `-Dkendar.configDir=...` or `KENDAR_CONFIG_DIR`.
* `config.json` inside it. **If it is missing it is created** with every default (and one library root `Music` when a
  default root is given, see `defaultRoot` below). It is never regenerated afterwards, so a stale file keeps its old
  (possibly empty) `library.roots`.
* The same directory holds the optional profiles file (`clients.profilesFile`) and the `reset-password` trigger file.
* Accounts are **not** kept in `config.json` any more: they live in the user store `<dataDir>/users/users.json`
  ([below](#users-and-identity-files)). A phase-1 `admin` section (`username`, `passwordHash`, `mustChange`) is read
  **once**, at the first start without any administrator in the store, and migrated (the server never writes
  `config.json`). Afterwards the section is ignored and can be deleted. To reset the admin password use
  `reset-password` ([features.md](features.md#security-and-password-lifecycle)).
* Unknown keys are ignored. JSON only (no comments).
* No library roots: a warning is logged and the library is empty.

## Overrides: system properties and environment

Resolution per setting: system property `kendar.<name>`, else environment variable `KENDAR_<NAME>` (the camel-case name
is split on capitals: `friendlyName` -> `KENDAR_FRIENDLY_NAME`), else the file. Blank values count as unset.

| Name | System property | Environment | Overrides | Default |
|---|---|---|---|---|
| `configDir` | `-Dkendar.configDir` | `KENDAR_CONFIG_DIR` | location of `config.json` | `/config` |
| `dataDir` | `-Dkendar.dataDir` | `KENDAR_DATA_DIR` | `library.dataDir` | (file) |
| `port` | `-Dkendar.port` | `KENDAR_PORT` | `network.port` (non-numeric: startup error `Invalid port override`) | (file) |
| `friendlyName` | `-Dkendar.friendlyName` | `KENDAR_FRIENDLY_NAME` | `server.friendlyName` | (file) |
| `defaultRoot` | `-Dkendar.defaultRoot` | `KENDAR_DEFAULT_ROOT` | only when the first-run template is written: becomes the single root `Music` | none (the Docker image sets `/music`) |

Overrides are applied after the file is read and are **not** resolved against the config directory (relative values are
relative to the working directory).

Other process-level settings: `JAVA_OPTS` (Docker entrypoint, appended to `-XX:MaxRAMPercentage=70
-XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Duser.home=/tmp`, so `-Xmx` in `JAVA_OPTS`
wins), `TZ` (time zone for `scan.cron`), `PUID`/`PGID` (Docker run-as user), `LANG=C.UTF-8` (non-ASCII file names).
`GIT_COMMIT` / `BUILD_TIME` are build arguments, not runtime settings.

## Relative paths

Relative paths in the **file** - `library.roots.*`, `library.playlistsDir`, `library.dataDir`,
`transcode.soundFontPath`, `clients.profilesFile` - are resolved against the directory containing `config.json` (made
absolute and normalised). Absolute paths and rooted ones (`/music`, also on Windows) are used as written. Example for
`dev/config/config.json`: `"roots": {"Music": "../music"}`, `"dataDir": "../data"`. The defaults `/playlists` and `/data`
are rooted, so for a local run set them explicitly (or pass `-Dkendar.dataDir`).

## `config.json` keys

| Key | Type | Default | Validation | Meaning |
|---|---|---|---|---|
| `library.roots` | object name -> path | `{}` | name not blank, no `/` or `\`; path not blank | The music roots; order is display order. The name is the first segment of all paths |
| `library.playlistsDir` | string | `/playlists` | - | Playlist JSON files (created on demand) |
| `library.dataDir` | string | `/data` | not blank | Index, thumbnails, transcode cache, device UUID, extracted SoundFont |
| `library.mainRoot` | string | the first root | must be one of `library.roots` | The "main root": the library root new users get as their only allowed root |
| `network.advertiseAddress` | string or null | null (auto) | - | IP written into `res`/art URLs. Auto: the first up, non-loopback, non-virtual interface (skipping names starting `docker`, `veth`, `br-`) with a site-local IPv4 address, else `127.0.0.1` |
| `network.port` | int | 8200 | 0-65535 (0 = pick a free port) | The single HTTP port: UPnP, streams, art, REST, web UI, health |
| `network.lanCidrs` | list of strings | `[]` (= loopback, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `::1`, `fc00::/7`, `fe80::/10`) | each an IP or CIDR | Addresses counted as LAN. Only LAN clients reach the admin API/pages (`/api/v1/admin`, `/admin`) and the DLNA paths (`/upnp`, `/stream`, `/art`); the web player and user API are open to any address. A non-empty list **replaces** the defaults |
| `network.trustedProxies` | list of strings | `[]` | each an IP or CIDR | Reverse proxies whose `X-Forwarded-For` / `X-Forwarded-Proto` are honored (client address for the LAN check, throttling and stream bookkeeping; `Secure` cookies when `https`). Headers from other peers are ignored. **Set this when running behind a proxy** (e.g. `["172.18.0.0/16"]` for a Docker network), otherwise the proxy address is the client |
| `server.friendlyName` | string | `Kendar DLNA` | not blank | Name shown by renderers |
| `random.ttlMinutes` | int | 30 | >= 1 | Random N snapshot lifetime after the last browse/stream activity |
| `views.bucketThreshold` | int | 500 | >= 1 | View lists longer than this (Artists, Composers, Titles) split into `A`-`Z`/`#`; a directory can override it with `bucketThreshold` in its `settings.json` (admin: Directory settings) |
| `scan.cron` | string | `0 */12 * * *` | valid 5-field cron | Periodic rescan, local time. Syntax in [features.md](features.md#library-and-scanning) |
| `scan.catchUpThresholdHours` | int | 12 | >= 1 | After a start from the persisted index, rescan in the background if the last completed scan is older |
| `transcode.enabled` | bool | true | - | false: originals only, MIDI unavailable, cue sheets and chapters ignored (files stay whole) |
| `transcode.bitrateKbps` | int | 320 | one of 32 40 48 56 64 80 96 112 128 160 192 224 256 320 | MP3 CBR bitrate (default for profiles with `bitrateKbps: 0`) |
| `transcode.prefetch` | bool | true | - | Encode the next track ahead |
| `transcode.prefetchJobs` | int | 1 | 1 or 2 | Size of the low-priority prefetch pool |
| `transcode.cacheMaxMb` | int | 2048 | >= 16 | Size cap of `<dataDir>/transcode` |
| `transcode.cacheTtlHours` | int | 336 | >= 1 | Entries unused for this long are dropped |
| `transcode.soundFontPath` | string or null | null (bundled) | blank = null; if not a file: warning and bundled one is used | SF2 file for MIDI |
| `transcode.midiSilenceThreshold` | int | 16 | 0-2000 | 16-bit amplitude regarded as silence when trimming the MIDI tail |
| `transcode.midiMaxTailSeconds` | int | 10 | 0-120 | Longest tail rendered after the last MIDI event |
| `clients.*` | object | see below | see below | Renderer profiles |
| `admin.username` / `passwordHash` / `mustChange` | string / string / bool | - | - | Legacy (phase 1): migrated into the user store at the first start, then ignored |

Fixed (not configurable): concurrent transcodes per user for web streams 2, UPnP base path `/upnp`, session idle 8 h / absolute 24 h, login throttle 5 failures / 15 min per IP and 10 failures / 15 min per user name, invite validity 7 days, remember-me 30 days,
thumbnail size 160 px, request body limit 12 MiB, art upload 10 MiB, REST search limit 300, tag reading parallelism 8.

Minimal working file:

```json
{ "library": { "roots": { "Music": "/music" } } }
```

## `clients` section

```json
"clients": {
  "builtins": true,
  "profilesFile": "clients.json",
  "profiles": [
    { "id": "living-room-tv", "description": "TV that plays MP3 and WAV only",
      "match": { "userAgentRegex": "MyTV", "headers": [ { "name": "X-Foo", "valueRegex": "bar", "negate": false } ],
                 "ipCidrs": ["192.168.1.40", "192.168.2.0/24"] },
      "nativeFormats": ["MP3", "WAV"], "resOrder": "TRANSCODED_FIRST", "transcodeTarget": "MP3",
      "bitrateKbps": 256, "quirks": ["SINGLE_RES"] }
  ],
  "defaultProfile": { "nativeFormats": ["MP3"] }
}
```

| Key | Default | Meaning |
|---|---|---|
| `builtins` | true | Enable the built-in profiles (`vlc`, `bubbleupnp`, `kodi`, `foobar2000`, `wmp`, `samsung-tv`, `lg-tv`; provisional) |
| `profilesFile` | null | JSON file `{ "profiles": [ ... ] }`, relative to the config directory |
| `profiles` | `[]` | Inline profiles |
| `defaultProfile` | MP3 native, transcoded first, MP3 target | Used when no profile matches; its `match` and `id` are ignored (id is forced to `default`) |

**Precedence**: inline `profiles`, then `profilesFile`, then built-ins; the first profile whose `match` holds wins.
A user profile (inline or file) with a built-in's id replaces that built-in.

Profile fields:

| Field | Values | Default | Notes |
|---|---|---|---|
| `id` | `[a-z0-9][a-z0-9._-]*`, unique | required | `default` is reserved |
| `description` | text | empty | |
| `match` | object | required (not for the default profile) | At least one of the parts must be given; **all given parts must hold** |
| `match.userAgentRegex` | Java regex | none | **Found** (substring) in the `User-Agent`, case-insensitive |
| `match.headers[]` | `{ "name", "valueRegex", "negate" }` | none | Header name case-insensitive. `valueRegex` null = header present; else regex found in one of the values (case-insensitive). `negate` inverts |
| `match.ipCidrs[]` | `"192.168.1.40"` or `"10.0.0.0/8"`, IPv4/IPv6 literals | none | Any-of. Never triggers DNS. IPv4-mapped IPv6 caller addresses are unwrapped |
| `nativeFormats` | any of `MP3 OGG FLAC WAV AIFF OPUS AAC ALAC ADTS` (upper case, case-sensitive) | none | Formats the renderer plays as they are. `MIDI` is silently removed (always rendered) |
| `resOrder` | `TRANSCODED_FIRST`, `NATIVE_FIRST` | `TRANSCODED_FIRST` | Order when original and transcode are both offered |
| `transcodeTarget` | `MP3`, `LPCM` | `MP3` | Form of the transcoded `res` |
| `bitrateKbps` | 0 or an MP3 bitrate | 0 | 0 = `transcode.bitrateKbps` (MP3 only) |
| `quirks` | `AVOID_NATIVE_MP3`, `HIDE_UNPLAYABLE_ORIGINAL`, `SINGLE_RES` | none | See [dlna.md](dlna.md#client-profiles) |

All profile errors (bad regex, bad CIDR, duplicate ids, bad bitrate, `id` format, empty `match`, unreadable file) are
collected and reported together at startup.

## Validation and failure

* Invalid JSON, an unknown enum value or a wrong type: `Invalid <path>/config.json: <message>`.
* A violated rule above: the message names the key, e.g. `transcode.bitrateKbps must be one of [32, 40, ...], got 300`.
* Profile errors: `clients: <error>; <error>`.
* On any of these the process logs `Invalid configuration in <dir>: ...` at SEVERE and **exits with status 2**.
* Not validated at start: that roots exist (a missing root is a scan warning/error), that directories are writable.

## Logging

* slf4j is bridged to `java.util.logging` (`slf4j-jdk14`); the default is the JDK console handler at INFO.
* Supply your own configuration with `-Djava.util.logging.config.file=...` (Docker: in `JAVA_OPTS`, with the file under
  `/config`). Useful loggers: `org.kendar.dlna.delivery` (streams, Range, transcode cache, prefetch),
  `org.kendar.dlna.dlna` (Browse/Search), `org.kendar.dlna.ingestion` (scans), `org.jupnp` (SSDP, eventing).

```properties
handlers=java.util.logging.ConsoleHandler
.level=INFO
java.util.logging.ConsoleHandler.level=ALL
org.kendar.dlna.level=FINE
org.jupnp.level=INFO
```

* `org.jupnp.transport` (one INFO line per HTTP request) is set to WARNING at start-up **unless**
  `java.util.logging.config.file` is set - then your file decides. A strong reference to that logger is kept so the
  level is not lost (JUL holds loggers weakly).
* jUPnP's specification-violation reporting is disabled (it warns about action arguments that are shaped on purpose for
  Windows Media Player compatibility).
* jaudiotagger's loggers (`org.jaudiotagger`) are forced to OFF by the tag reader/writer classes; tag problems surface as
  scan errors / API errors instead.
* The generated admin password is logged at WARNING in a marked block (`KENDAR DLNA ADMIN PASSWORD`), once.
* Start-up lines worth knowing: transcoding on/off with bitrate and prefetch, MIDI available/unavailable (with reason),
  `Catalog ready in N ms (persisted index | full scan)`, `Kendar DLNA '<name>' uuid=... listening on port N`, next
  scheduled rescan.
* Requests are not access-logged.

## Directory layout

Config directory (`KENDAR_CONFIG_DIR`, `/config`):

```
config.json        settings (legacy admin section only read once, for migration)
clients.json       optional, any name set by clients.profilesFile
reset-password     optional trigger file (empty); deleted when handled
```

Data directory (`library.dataDir`, `/data`):

```
device-uuid                 stable UPnP device UUID (created on first start; regenerated if corrupt)
catalog.json                persisted index (written atomically via catalog.json.tmp)
thumbs/<hash>-<hash>.jpg    160 px JPEG thumbnails (key: source identity + mtime/size); stale versions removed
transcode/<key>.bin|.meta   transcode cache entries (LRU, TTL, size cap)
soundfont/GeneralUser-GS.sf2  the bundled SoundFont, extracted once when no soundFontPath is set
users/users.json              the user store (see below)
users/invites.json            pending invites (hashed tokens)
users/sessions.json           remember-me tokens (hashed)
users/<userId>/               personal data of a user (later milestones; removed with the user)
```

Delete `catalog.json` to force a full scan; delete `thumbs/` or `transcode/` to test cold paths. All are rebuildable;
only `device-uuid` matters for renderers that remember the server (keep the volume).

Playlists directory (`library.playlistsDir`): `<key>.json` per playlist.

Music roots (`library.roots`): audio files plus optional `settings.json` (incl. `keepWholeFiles`, editable on the Directory settings page), `list.json` (per-file or per-cue-track title/author/collection/transcriber, see
[features.md](features.md#listjson-reference)), per-file sections lists `<file name>.json` such as `concert.flac.json` (see
[features.md](features.md#per-file-sections-list-filejson)), `.cue` cue sheets (see
[features.md](features.md#cue-sheets-and-chapters-virtual-albums)), `front.jpg`, `rear.jpg` per directory. The
server writes here (tags, `settings.json`, `list.json`, `<file>.json`, pictures, safe temporary files that are renamed or removed); mount read-only
for a view-only server.

## Users and identity files

Accounts, roles and permissions are kept by the server in `<dataDir>/users/` (JSON, written atomically via a temp file
and rename). **`/data/users` holds user content, not just cache: include it in backups.**

* `users.json`: per user `id`, `username` (unique, case-insensitive), `displayName`, `role` (`admin` or `user`),
  `passwordHash` (PBKDF2-HMAC-SHA256; null until the invite is accepted), `mustChange`, `allowedRoots`,
  `downloadAllowed` (default false), `enabled`, `createdAt`, `lastLogin`. Managed through the admin API
  ([rest-api.md](rest-api.md#user-management-admin)); do not hand-edit while the server runs.
* `invites.json`: one-time invite links (SHA-256 of the token, user, expiry 7 days).
* `sessions.json`: remember-me tokens (SHA-256 of the token, user, expiry 30 days, rotated at every use). Login
  sessions themselves are in memory.
* **Admin migration**: at the first start without any administrator in `users.json`, the `admin` section of
  `config.json` is migrated (role admin, same hash and must-change flag); without one, a password is generated and logged
  once. The `reset-password` trigger file keeps working: it resets the (oldest) admin account.
* **Allowed roots**: new users get `[main root]` (`library.mainRoot`, default the first configured root); the admin
  edits the list per user. Admins always see all roots. **Removing a root from `library.roots` removes it from every
  user's list at the next start** (logged, `Library roots [...] are no longer configured: removed from user '...'`);
  adding a root back does not restore it.
* **Invites and temporary passwords**: the admin creates the account; the user opens
  `http://<host>:8200/app/#/invite/<token>` and chooses a password. If the link cannot be delivered, the admin sets a
  temporary password (shown once, change forced at first login).
