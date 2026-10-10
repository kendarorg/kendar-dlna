# Kendar DLNA

A UPnP/DLNA **music server** in pure Java 25: browse a music directory tree from any DLNA renderer or control point,
with per-directory sorting, "Random N", "Recently added" and Albums/Artists/Genres/Titles/Composers virtual containers, server-side playlists,
search, MP3 transcoding of formats the renderer cannot play, MIDI rendering, and an admin web UI. One HTTP port, no
database, no native libraries. The design is in `plan.md`.

* Formats: MP3, OGG Vorbis, FLAC, WAV, AIFF, Opus, AAC-LC (`.m4a` `.mp4` and raw ADTS `.aac`), Apple Lossless (ALAC,
  `.m4a`), MIDI (`.mid` `.midi` `.kar`). Others (HE-AAC, WMA, APE, WavPack, DSD `.dsf` `.dff`, `.m4b`, ...) are listed in
  the admin page as *unsupported*, with the reason, and are not exposed through DLNA.
* Out of scope: authentication on the DLNA side, gapless playback, client queues, filesystem watching.

## Documentation

Reference documentation (features, `config.json`, REST API, DLNA/UPnP behaviour, transcoding, client compatibility) is
indexed in [`docs/README.md`](docs/README.md).

## Contents

1. [Quick start with Docker Compose](#quick-start-with-docker-compose)
2. [Portainer (stack from a Git repository)](#portainer-stack-from-a-git-repository)
3. [Host networking (Linux only)](#host-networking-linux-only)
4. [Volumes, PUID/PGID, environment](#volumes-puidpgid-environment)
5. [Admin account and password reset](#admin-account-and-password-reset)
6. [Formats and transcoding](#formats-and-transcoding)
7. [SoundFont (MIDI)](#soundfont-midi)
8. [`settings.json` reference](#settingsjson-reference)
9. [`config.json` reference](#configjson-reference)
10. [Build and run locally](#build-and-run-locally)
11. [Run locally for debugging](#run-locally-for-debugging)
12. [Troubleshooting](#troubleshooting)

## Quick start with Docker Compose

```sh
cp stack.env .env            # or: docker compose --env-file stack.env ...
$EDITOR .env                 # MUSIC_DIR, PLAYLISTS_DIR, CONFIG_DIR, DATA_DIR, PUID, PGID, TZ
mkdir -p /srv/kendar-dlna/{playlists,config,data}
docker compose --env-file .env up -d --build
docker compose logs kendar-dlna | grep -A4 "ADMIN PASSWORD"
```

Then open `http://<host>:8200/` (the web player; the admin pages are at `/admin/`, LAN only). The server shows up as "Kendar DLNA" in your renderers a few seconds later
(requires [host networking](#host-networking-linux-only)).

The first start writes `/config/config.json` with the music directory (`/music`) registered as the root `Music`,
playlists in `/playlists`, data in `/data`, port 8200 and every other setting at its default. Edit that file for
anything else (see [the reference](#configjson-reference); a complete example is `config/config.example.json`) and
restart the container.

## Portainer (stack from a Git repository)

1. *Stacks* -> *Add stack* -> *Repository*. Repository URL = this repository, reference `refs/heads/main`, compose
   path `docker-compose.yml`.
2. Under *Environment variables* use *Load variables from .env file* and pick `stack.env`, then adjust:
   `MUSIC_DIR`, `PLAYLISTS_DIR`, `CONFIG_DIR`, `DATA_DIR` (absolute **host** paths; Portainer clones the repository
   into its own volume, so relative paths would not point where you expect), `PUID`, `PGID`, `TZ`, optionally
   `JAVA_OPTS`, `IMAGE_NAME`, `IMAGE_TAG`.
3. *Deploy the stack*. The image is built on the Docker host (the first build downloads Gradle and the libraries
   and takes a few minutes; later builds reuse the dependency layer).
4. **Updates**: *Pull and redeploy* in the stack page (enable *Re-pull image and redeploy*/rebuild), or enable
   *GitOps updates* (polling or webhook) so that a push to the repository rebuilds and restarts the stack.
   `/config` and `/data` persist, so the device UUID stays stable (renderers keep the server) and startup is fast
   (the persisted index is reused; a background rescan only runs when the last scan is older than 12 h).
5. **Lost admin password**: in the container's *Console* run `touch /config/reset-password` (see below).
6. The build stamps the image with `GIT_COMMIT` and `BUILD_TIME` (shown in the admin page). Portainer does not
   provide them; set them in the stack variables if you want them, otherwise they read `unknown`.

> **SoundFont in Git builds.** The bundled GeneralUser GS SoundFont is a 32 MB file,
> `src/main/resources/soundfont/GeneralUser-GS.sf2`, which this project's author keeps out of Git. The Docker build
> copies `src/`, so a Git-based Portainer build only has MIDI support if that file is **in the repository**
> (commit it, or use Git LFS). Without it the image builds fine but MIDI files are listed as unsupported
> ("MIDI rendering unavailable: no SoundFont"). Alternative that works with any build: download
> `GeneralUser-GS.sf2` yourself, drop it into the host `DATA_DIR` (mounted as `/data`) or `CONFIG_DIR`, and set
> `"transcode": { "soundFontPath": "/data/GeneralUser-GS.sf2" }` in `config.json`.

## Host networking (Linux only)

DLNA discovery is SSDP: UDP multicast to `239.255.255.250:1900`, plus the HTTP description and stream URLs that must be
reachable from the renderers at the address the server advertises. The compose file therefore uses
`network_mode: host`, so the server sits on the LAN interface directly. The HTTP port is then the host's port 8200
(`network.port` in `config.json`; if you change it, also set `KENDAR_PORT` for the health check).

* **Linux hosts** (including NAS boxes, Raspberry Pi, a VM with a bridged adapter): works as is.
* **Docker Desktop on Windows/macOS**: host networking does not give the container the real LAN (it lives in a VM), so
  multicast discovery does **not** work and renderers will not see the server. The container itself runs fine: you
  can use the admin UI and test with a published port (`ports: ["8200:8200"]` instead of `network_mode: host`), and
  point a control point at `http://localhost:8200/upnp/dev/<uuid>/desc` where it supports manual URLs. For real
  use run the container on a Linux host.
* **Bridge networking on Linux** (`ports:`) also lacks discovery, and the advertised stream URLs would carry the
  container's bridge address. If you must, set `network.advertiseAddress` to the host's LAN IP; renderers still
  need a manual server entry. Host networking is the supported setup.
* Auto-detection of the advertised address skips `docker0`, `veth*` and other virtual interfaces; set
  `network.advertiseAddress` when a host has several LAN interfaces.
* Firewall: allow UDP 1900 (multicast) and TCP 8200 from the LAN.
* `stop_grace_period: 20s`: on stop the server sends `ssdp:byebye`, so renderers drop it immediately.

## Volumes, PUID/PGID, environment

| Container path | Host variable | Contents |
|---|---|---|
| `/music` | `MUSIC_DIR` | audio files, per-directory `settings.json`, `front.jpg`/`rear.jpg`. Read-write: the admin UI writes tags, settings and art. Mount `:ro` for a view-only server (editing then fails with an error). |
| `/playlists` | `PLAYLISTS_DIR` | one JSON file per playlist |
| `/config` | `CONFIG_DIR` | `config.json`, optional `clients.json`, `reset-password` trigger |
| `/data` | `DATA_DIR` | device UUID, `catalog.json` (persisted index), thumbnails (`thumbs/`), transcode cache (`transcode/`), extracted SoundFont, **`users/` (accounts, invites, remember-me tokens and, later, personal playlists: user content, back it up)** |

The container starts as root only to `chown -R` `/config` and `/data` (and `/playlists` itself) to `PUID:PGID`, then
drops privileges (`su-exec`) and runs Java as that user. **Set `PUID`/`PGID` to the owner of the music directory**
(`id -u`, `id -g`) so tag edits and `settings.json` writes work. `/music` is never chown-ed. Starting the container
with `--user` skips the chown/drop and runs as that user directly.

Environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `PUID`, `PGID` | 1000 | run-as user and group |
| `TZ` | `UTC` | time zone (scan cron expressions fire in local time) |
| `JAVA_OPTS` | empty | extra JVM options, appended after the defaults (`-XX:MaxRAMPercentage=70`, headless, UTF-8, `-XX:+ExitOnOutOfMemoryError`), so `-Xmx1g` wins |
| `KENDAR_CONFIG_DIR` | `/config` | where `config.json` lives |
| `KENDAR_DATA_DIR` | (config) | overrides `library.dataDir` |
| `KENDAR_PORT` | 8200 | overrides `network.port` (also used by the health check) |
| `KENDAR_FRIENDLY_NAME` | (config) | overrides `server.friendlyName` |
| `KENDAR_DEFAULT_ROOT` | `/music` (image) | only for the first-run template: library root `Music` |

`LANG=C.UTF-8` is set so non-ASCII file names work. Health: `GET /health` returns `{"status":"UP"}` (the image and
compose health checks use busybox `wget`).

## Admin account and password reset

* First start: no admin password exists, so a random 20-character one is generated and **logged once** in a marked
  block (`docker logs kendar-dlna`, or Portainer *Logs*):

  ```
  ================================================================
    KENDAR DLNA ADMIN PASSWORD (first startup: no admin password was set)
    user:     admin
    password: ...
  ================================================================
  ```
* The first login forces a password change. Only a PBKDF2-HMAC-SHA256 hash is stored, in the user store
  `/data/users/users.json` (an `admin` section of a phase-1 `config.json` is migrated there once, hash included, at the
  first start of this version, and then ignored).
* **Users and roles**: the admin account is the first user. An admin creates more accounts through the REST API
  (`/api/v1/admin/users`, see [docs/rest-api.md](docs/rest-api.md#user-management-admin)): role `admin` or `user`,
  allowed library roots (default: the main root, `library.mainRoot`, else the first root), download permission, and an
  **invite link** (`http://<host>:8200/app/#/invite/<token>`, one-time, valid 7 days) where the user chooses a
  password; a temporary password with forced change is the fallback. Regular users cannot use the admin API (`403`) and
  only see their allowed roots. "Keep me signed in" at login stores a 30-day remember-me token (hashed, rotated at every
  use) that survives server restarts. Details: [docs/configuration.md](docs/configuration.md#users-and-identity-files).
* **Backup**: `/data/users` holds user content (accounts, invites, remember-me tokens and, per user, playlists, favorites,
  the saved queue and the play history), not just cache. Back it up together with `/config`.
* **Admin UI**: the *Users* page (`#/admin/users`, LAN only) creates users (allowed-roots check boxes with the main root
  preselected, download permission), shows the invite link to copy (built from the address the admin page was opened on:
  replace the host with the public address when the user comes from outside), issues new invites and temporary
  passwords, edits, disables and deletes users. *Shared playlists* (`#/admin/shared-playlists`) makes a shared playlist
  private again or deletes it; private playlists, favorites, queues and history are never visible to admins.
* **Lost admin password**: create the empty file `reset-password` in the config directory
  (`touch /config/reset-password` in the container console, or `touch $CONFIG_DIR/reset-password` on the host). The
  server notices it at startup and every ~30 s, deletes it, generates a new password (logged once, change forced) and
  ends all sessions. If the file cannot be deleted (wrong permissions), nothing is reset and an error is logged.
* Network exposure: the admin API and pages (`/api/v1/admin`, `/admin`) and the DLNA paths (`/upnp`, `/stream`, `/art`) answer
  **LAN addresses only** (private ranges by default, `network.lanCidrs`); the web player and user API (`/api/v1/...`)
  answer anywhere but need a login. There is no HTTPS in the server: put a reverse proxy in front (example below) and list
  it in `network.trustedProxies` so its `X-Forwarded-*` headers are honored.

### Reverse proxy example (HTTPS, path routing)

Publish only the web app (`/app/`) and the user API (`/api/v1/`); the admin pages and DLNA paths are not routed and
would be refused for internet clients anyway. nginx on the same Docker network (`172.18.0.0/16`):

```nginx
server {
    listen 443 ssl;
    server_name music.example.org;
    # ssl_certificate / ssl_certificate_key ...
    add_header Strict-Transport-Security "max-age=31536000" always;
    client_max_body_size 12m;          # the API accepts bodies up to 12 MiB (large playlists, queue sync)

    location = / { return 302 /app/; }
    location /app/ {                   # the web player (static files)
        proxy_pass http://kendar-dlna:8200;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    location /api/v1/ {                # the user API: library, stream, /me, /shared, /auth
        proxy_pass http://kendar-dlna:8200;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;           # audio streams and Range requests
        proxy_read_timeout 1h;
    }
    location /api/v1/admin { return 404; }   # belt and braces: admin stays on the LAN
}
```

```json
"network": { "trustedProxies": ["172.18.0.0/16"] }
```

Without `trustedProxies` the proxy would be the client address (a private one, therefore "LAN"); with it, the real client
address decides, so an internet client is refused at /api/v1/admin even if the proxy forwarded it. Cookies become `Secure`
because of `X-Forwarded-Proto: https`. Keep DLNA (host networking, SSDP) on the LAN; it does not go through the proxy.

Hardening checklist for an internet-facing player:

* HTTPS only (redirect port 80, HSTS as above). The session cookies are `HttpOnly`, `SameSite=Strict` and `Secure` behind
  `X-Forwarded-Proto: https`; a proxy that does not send that header leaves them without `Secure`.
* `network.trustedProxies` must list **only** the proxy: an over-broad range lets anyone forge `X-Forwarded-For` and look
  like a LAN client. From the internet `GET /api/v1/admin/users` must answer `403` or `404`.
* Do not route `/upnp`, `/stream`, `/art`, `/admin` and `/api/v1/admin`; DLNA has no authentication by design.
* The server throttles logins per client IP and per user name; additionally rate-limit `/api/v1/auth/` in the proxy (for
  example `limit_req`). Play reports are limited to 30 per minute and user, web transcodes to 2 per user.
* Give every person their own account (invite link); disabling or deleting a user ends their sessions at once.
* Keep `/data` (password hashes, personal data) and `/config` readable only by the container user.
* Browser support and known platform limits: [docs/client-compatibility.md](docs/client-compatibility.md#web-player-browser-compatibility).
* For testing purposes the passowrd adminadmin has this hash: pbkdf2-sha256$310000$iabkq/BCxD3pbq+RSU5zeg==$lPMUPKT9WTDWXiEsipU0RKzATk5fh/0/AAhAGArlIGU=

## Formats and transcoding

| Source | What the renderer is offered (default profile) |
|---|---|
| MP3 | the original |
| OGG Vorbis, FLAC, WAV, AIFF, Opus, AAC, ALAC | an MP3 transcode first (exact size and duration), the original second |
| MIDI | an MP3 rendered with the SoundFont (size known after the first render) |

* Transcoding is pure Java: decoder -> PCM -> jump3r (LAME port): **CBR 320 kbps** (`transcode.bitrateKbps`), 48 kHz
  stereo, bit reservoir off, no Xing/ID3 tags. Every frame has the same size, so `Content-Length` is exact and
  Range/seek requests work. Optional LPCM output (`audio/L16`) per client profile.
* **Prefetch** (on): when a client starts track N of a folder/playlist/Random/view, track N+1 is encoded into the
  cache (`/data/transcode`, LRU, `cacheMaxMb` 2048, `cacheTtlHours` 336) so it starts instantly.
* **Client profiles** (`clients` in `config.json`, optional `clients.json`) decide per renderer (User-Agent, headers,
  IP) which formats are native, the order of the `res` elements, MP3 or LPCM, and quirks. Built-in profiles exist for
  VLC, BubbleUPnP, Kodi, foobar2000, WMP, Samsung and LG TVs (provisional until tested; see
  `docs/client-compatibility.md`). Details: `docs/transcoding.md`.
* **AAC and ALAC.** `.m4a` / `.mp4` are told apart by the codec inside (AAC-LC or ALAC), raw `.aac` is ADTS. Native
  `res` is `audio/mp4` (`audio/aac` for ADTS) without a `DLNA.ORG_PN`. Gapless-exact length from the MP4 edit list or
  `iTunSMPB`; seeking is sample accurate. Tags (title, artist, album, album artist, composer, genre, year, track, disc)
  and cover art are read and written for `.m4a` / `.mp4`; raw `.aac` has no tags and is read-only. Not supported
  (listed as unsupported with the reason): HE-AAC (SBR/PS), AAC profiles other than LC, MP4 with a video track,
  fragmented MP4, DRM, ALAC with more than 2 channels or other than 16/24 bit. AAC with more than 2 channels is folded
  to stereo. Details and test results: `docs/aac-alac-findings.md`.
* Playable: every file of the formats above. Unsupported files, bad `settings.json` and broken playlist entries are
  listed in the admin page. Rescans: manual (admin page), scheduled (`scan.cron`, default every 12 h).

## SoundFont (MIDI)

MIDI is rendered with the JDK's software synthesizer and a SoundFont:

1. `transcode.soundFontPath` if it points to a file (any `.sf2`; mount it into `/data` or `/config`), else
2. the bundled **GeneralUser GS** (S. Christian Collins, v2.0.3, permissive licence, see `NOTICE`), extracted once to
   `/data/soundfont/`, else
3. MIDI is unavailable and the files are listed as unsupported.

The bundled file (`src/main/resources/soundfont/GeneralUser-GS.sf2`, 32 MB) is not generated or downloaded by the
build and the author keeps it out of Git: see the note under [Portainer](#portainer-stack-from-a-git-repository).
The JVM flag `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` that Gervill needs is set in the jar
manifest (`Add-Exports`), so plain `java -jar kendar-dlna.jar` works.

## `settings.json` reference

An optional `settings.json` per directory (edit it in the admin UI, or by hand and rescan). **No inheritance**: a file
only affects its own directory. A malformed file is logged and shown in the UI; defaults are used and browsing never
fails. Unknown keys are ignored.

| Field | Values | Default | Meaning |
|---|---|---|---|
| `sort` | `filename` `title` `track` `mtime` `explicit` | `filename` | ordering of the **direct children** (subdirectories first, then files) |
| `order` | array of child names | none | used by `explicit`: files and subdirectories interleaved in this order; unlisted children are appended; unknown names ignored (flagged in the UI) |
| `displayName` | string (max 255) | none | shown instead of the directory name in DLNA Browse (also for a library root under Folders), the web UI and search; used verbatim (no " (CD n)" suffix), blank = unset. The directory on disk is **not** renamed and object ids stay path-based. Wins over `album.title`. Search finds the directory by its display name, its real name and `album.title` |
| `album` | object, see below | none | album-level overlay |
| `random` | integer N | absent | N > 0 adds a container titled "[Random N]" (N random tracks from the whole subtree, one snapshot per client, 30 min TTL) |
| `recent` | integer N | absent | N > 0 adds a container titled "[Recently added]": the N most recently **added** tracks of the whole subtree, newest first, after [Random N] and before the views. "Added" is the time a scan first saw the file (see below), not its mtime |
| `views` | array of `album` `artist` `genre` `title` `composer` | `[]` | adds [Albums] / [Artists] / [Genres] / [Titles] / [Composers] containers over the subtree, in this order, after Random N and Recently added. [Composers] is composer, album, tracks (plus "All tracks"); tracks without a composer go to "Unknown Composer" |

Sort modes: `filename` (natural, case/accent-insensitive: "Track 2" before "Track 10"), `title` (tag title, fallback
file name; directories by display name), `track` (disc, track number, file name), `mtime` (modification time),
`explicit` (see `order`).

`album` fields (all optional; they win over the tags in all output and the UI flags the directory as out of sync):
`title`, `albumArtist`, `year`, `genre`, `discNumber`, `discTotal`. The directory's display name is `displayName`
when set, else `album.title` (plus " (CD n)" when `discNumber` is set), else the directory name. In the editor: *Apply to tags* writes them to every track, *From tags* fills them from the tracks (error if
the tracks disagree).

```json
{
  "displayName": "Greatest Hits (remastered)",
  "sort": "explicit",
  "order": ["Intro.flac", "Live", "02 - Song.mp3"],
  "album": { "title": "Greatest Hits", "albumArtist": "Some Band", "year": 1999, "genre": "Rock",
             "discNumber": 1, "discTotal": 2 },
  "random": 25,
  "recent": 50,
  "views": ["album", "artist", "genre", "title", "composer"]
}
```

Artwork: `front.jpg` (item and folder art, preferred over embedded art) and `rear.jpg` (admin UI only) in the directory.
Whole-library random: put `"random": N` in the root's `settings.json`.

**Recently added.** Every track has a persisted first-seen time (`addedAt` in `catalog.json`). On the very first full
scan it is the file creation time (the modification time where the file system has no creation time), so the initial
order is sensible; afterwards a file that was not in the previous index gets the time of the scan that found it. It is
kept across rescans, forced re-reads and tag edits (a renamed or moved file counts as new). Catalog format 2 added
`addedAt` and the composer tags: an older `catalog.json` is discarded at startup and a full scan runs once.

**Composer.** The `composer` tag is read from ID3 (TCOM), Vorbis comments/Opus (`COMPOSER`), MP4 (`©wrt`) and FLAC, supports several
values, is shown and editable in the metadata editor (MP3, OGG, FLAC, WAV, AIFF, M4A; not MIDI/Opus/raw AAC), is exposed in DIDL as
`upnp:author role="Composer"`, and is searchable (`upnp:author contains "..."` over UPnP, and in the web UI search).

**Editing MIDI tags.** MIDI files (`.mid` `.midi` `.kar`) can be edited for **title, artist and year only** (a sequence holds
no album, genre, composer, track or disc number). The server patches the file bytes directly, never re-serialising the
sequence: title = the sequence-name meta event of the first track (and the first karaoke `@T` line, if the file has
them, is kept consistent); artist = the second `@T` line (a title is needed first, so setting an artist on a file without
a title is refused); year = the year in the first copyright notice (an existing "(c) 1996 Owner" becomes "(c) 2003 Owner",
otherwise "(c) YYYY" is added; clearing removes the year digits and the event itself only when nothing else is in it). Years
must be 1900-2099. The edit follows the usual safe-write procedure (temporary copy in the same directory, patch the
copy, verify it, atomic rename; no backup is kept). The copy must parse, keep division, track count and tick/microsecond
length, keep every other event byte-identical at the same tick, and read back exactly the requested tags; otherwise
it is deleted, the original stays untouched and the reason is reported for that file. The persisted rendered length of the
file is reset (measured again on the next render) and its transcode cache entry is dropped. Files with a broken structure,
`RIFF`-wrapped (RMID) files and files `javax.sound.midi` cannot read are refused. In the metadata editor the album, genre,
composer, track and disc inputs are disabled when only MIDI files are selected; in a mixed batch those fields are simply
skipped for the MIDI files. *Apply to tags* (album fields) skips MIDI files, as before. Opus and raw `.aac` stay read-only.

**Re-reading tags.** A rescan normally reuses the tags of files whose mtime and size are unchanged. Tick *Re-read all
tags* next to the rescan buttons in the Admin page (whole library or one directory) or in the Library browser (the
selected directory) to ignore that shortcut and re-read every file in scope - e.g. after upgrading, to pick up the new
composer tag. REST: `POST /api/v1/admin/rescan` with `{"path": "root/dir", "force": true}` (both optional).

## `config.json` reference

`/config/config.json`; validated at startup (invalid values stop the server with a log line naming the problem).
Every key is optional. JSON has no comments, so this table is the commentary for `config/config.example.json`.

**Relative paths** in the file (`library.roots`, `library.playlistsDir`, `library.dataDir`, `transcode.soundFontPath`,
`clients.profilesFile`) are resolved against the directory that contains `config.json`, e.g. with
`dev/config/config.json`: `"roots": {"Music": "../music"}`, `"dataDir": "../data"`. Absolute paths (and rooted ones such
as `/music`) are used as written. Command-line overrides (`-Dkendar.dataDir=...`) are relative to the working directory.

| Key | Default | Meaning |
|---|---|---|
| `library.roots` | `{}` (`{"Music":"/music"}` from the Docker template) | map root name -> directory; names must not contain `/` or `\` |
| `library.playlistsDir` | `/playlists` | playlist JSON files |
| `library.dataDir` | `/data` | index, thumbnails, transcode cache, device UUID |
| `network.advertiseAddress` | `null` (auto-detect) | IP written into the descriptions/stream URLs |
| `network.port` | 8200 | the single HTTP port (UPnP, streams, art, REST, web UI) |
| `server.friendlyName` | `Kendar DLNA` | name shown by renderers |
| `admin.*` | generated | `username`, `passwordHash`, `mustChange`; managed by the server, do not hand-edit |
| `random.ttlMinutes` | 30 | Random N snapshot lifetime after the last activity |
| `views.bucketThreshold` | 500 | view lists longer than this are split into A-Z / `#` |
| `scan.cron` | `0 */12 * * *` | 5-field cron of the periodic rescan (local time, `TZ`) |
| `scan.catchUpThresholdHours` | 12 | after a fast start, scan in the background if the last scan is older |
| `transcode.enabled` | true | false: originals only (MIDI then unavailable) |
| `transcode.bitrateKbps` | 320 | MP3 CBR rate: 32 40 48 56 64 80 96 112 128 160 192 224 256 320 |
| `transcode.prefetch` / `prefetchJobs` | true / 1 | encode the next track ahead (pool of 1 or 2) |
| `transcode.cacheMaxMb` / `cacheTtlHours` | 2048 / 336 | transcode cache size cap and idle lifetime |
| `transcode.soundFontPath` | `null` (bundled) | SF2 file for MIDI |
| `transcode.midiSilenceThreshold` | 16 | 16-bit amplitude regarded as silence when trimming the MIDI tail (0-2000) |
| `transcode.midiMaxTailSeconds` | 10 | longest tail rendered after the last MIDI event (0-120) |
| `clients.builtins` | true | enable the built-in renderer profiles |
| `clients.profilesFile` | `null` | JSON file `{"profiles":[...]}` relative to `/config` |
| `clients.profiles` | `[]` | inline profiles, checked first; first match wins |
| `clients.defaultProfile` | MP3 native | profile for unmatched renderers (its `match` is ignored) |

Profile fields: `id`, `description`, `match` (`userAgentRegex`, `headers` as `{name, valueRegex, negate}`, `ipCidrs`;
all given parts must match), `nativeFormats` (`MP3 OGG FLAC WAV AIFF OPUS AAC ALAC ADTS`; MIDI is never native), `resOrder`
(`TRANSCODED_FIRST` or `NATIVE_FIRST`), `transcodeTarget` (`MP3` or `LPCM`), `bitrateKbps` (0 = global), `quirks`
(`AVOID_NATIVE_MP3`, `HIDE_UNPLAYABLE_ORIGINAL`, `SINGLE_RES`).

## Build and run locally

Requirements: JDK 25 (Corretto/Temurin). The Gradle wrapper does the rest.

```sh
./gradlew build                 # compile, unit tests (incl. ArchUnit), JS unit tests (if Node.js is installed), fat jar -> build/libs/kendar-dlna.jar
./gradlew jsTest                # only the JavaScript unit tests of the web app (node --test src/test/js)
java -jar build/libs/kendar-dlna.jar
```

Without Docker there is no `/config`: point it elsewhere, for example
`KENDAR_CONFIG_DIR=./dev/config KENDAR_DATA_DIR=./dev/data java -jar build/libs/kendar-dlna.jar`, then add your
directory under `library.roots` in `dev/config/config.json`. Under Gradle: `./gradlew run`. Tests that need the
SoundFont run with `-PsoundFont=path/to/GeneralUser-GS.sf2`.

See [Run locally for debugging](#run-locally-for-debugging) for a ready-made dev setup.

Manual end-to-end tests (a Java UPnP control point against a Testcontainers container, or against a running server)
live in a separate source set and are **not** part of `build`/`check`: `./gradlew integrationTest`, see
`docs/integration-tests.md`.

Docker image only:

```sh
docker build -t kendar-dlna .
docker build --build-arg SKIP_TESTS=false --build-arg GIT_COMMIT=$(git rev-parse --short HEAD) \
             --build-arg BUILD_TIME=$(date -u +%FT%TZ) -t kendar-dlna .       # also runs the unit tests
```

`eclipse-temurin:25-jre-alpine` is used at runtime. For an architecture without an Alpine Temurin image build with
`--build-arg RUNTIME_IMAGE=eclipse-temurin:25-jre` (Ubuntu based); install `gosu` there instead of `su-exec` (the
commented line in the `Dockerfile`; the entrypoint accepts either). The application is pure Java: no native
libraries, no musl issues.

## Run locally for debugging

No Docker needed. Everything lives in a scratch directory (`dev/` is already git-ignored).

**1. Prepare a dev directory and a small library**

```sh
mkdir -p dev/config dev/data dev/playlists
# put some audio files (mp3, flac, ogg, wav, opus, mid ...) under dev/music, or point at your real collection below
```

**2. Start the server** (Git Bash / Linux / macOS; JDK 25 on the path or `JAVA_HOME`):

```sh
./gradlew shadowJar
java -Dkendar.configDir=dev/config -Dkendar.dataDir=dev/data -Dkendar.port=8200 \
     -Dkendar.defaultRoot=$(pwd)/dev/music \
     -jar build/libs/kendar-dlna.jar
```

* `kendar.defaultRoot` only matters on the very first run: it is written into the generated
  `dev/config/config.json` as the library root `Music`. Afterwards edit `library.roots` there (a root is
  `"Name": "path"`; relative paths are resolved against the directory of `config.json`, so
  `"roots": {"Music": "../music"}, "playlistsDir": "../playlists", "dataDir": "../data"` works for `dev/config`).
  The file is not regenerated if it already exists, so a stale `config.json` keeps its old (possibly empty) roots. Delete `dev/data/catalog.json` to force a full rescan.
* Every `-Dkendar.*` property has an environment variable twin (`KENDAR_CONFIG_DIR`, `KENDAR_DATA_DIR`, `KENDAR_PORT`,
  `KENDAR_FRIENDLY_NAME`, `KENDAR_DEFAULT_ROOT`); the system property wins.
* Set `network.advertiseAddress` in `config.json` to your LAN address if the log shows a wrong one (for example a
  VirtualBox/VPN adapter): that address ends up in the `res` and art URLs.
* On Windows PowerShell use `$PWD\dev\music` instead of `$(pwd)/dev/music`.
* Without the `--add-exports` flag (it is in the jar manifest, so `java -jar` is fine) MIDI is reported as unavailable.

**3. Look around**

* Health: <http://localhost:8200/health>
* Web player: <http://localhost:8200/app/>; admin pages: <http://localhost:8200/admin/> (user `admin`; the generated password is printed once in the log block
  `KENDAR DLNA ADMIN PASSWORD`, you must change it at first login; lost it: `touch dev/config/reset-password`)
* Device description: `http://localhost:8200/upnp/dev/<uuid>/desc` (the UUID is in `dev/data/device-uuid`)
* A DLNA client on the same LAN (VLC: *View → Local Network → Universal Plug'n'Play*) should now list the server.
* Raw SOAP Browse without a client:

```sh
curl -s -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"' \
  --data '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>' \
  http://localhost:8200/upnp/dev/<uuid>/svc/upnp-org/ContentDirectory/action
```

**4. Run and debug from IntelliJ**

* Open the folder as a Gradle project (JDK 25, *Settings → Build Tools → Gradle → Gradle JVM*).
* **Ready-made:** the repository contains a shared run configuration, `.run/Kendar DLNA (local).run.xml`. IntelliJ picks it
  up automatically: choose **Kendar DLNA (local)** in the run configurations drop-down and press *Run* or *Debug*
  (after the first Gradle import). It uses `dev/config`, `dev/data` and port 8200 under the project directory, and
  `dev/music` as the first-run library root: drop some audio files in `dev/music` (the folders are git-ignored) or
  change `library.roots` in `dev/config/config.json` after the first start. Edit the VM options in the configuration
  (e.g. `-Dkendar.port=`, `-Dkendar.defaultRoot=`) for your setup; the admin password appears in the *Run* console.
* Manual setup, if the configuration doesn't show up: create an *Application* run configuration: main class `org.kendar.dlna.platform.Main`, JRE 25,
  VM options
  `-Dkendar.configDir=dev/config -Dkendar.dataDir=dev/data -Dkendar.port=8200 -Dkendar.defaultRoot=C:/path/to/music -Djava.awt.headless=true --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED`,
  working directory = project root. Then use *Debug*; breakpoints work in the DLNA, delivery and transcoding code.
* Alternatively `./gradlew run` (the flags above are already set in `build.gradle`; pass the `-Dkendar.*` values with
  `JAVA_TOOL_OPTIONS` or `KENDAR_*` environment variables). To attach a remote debugger to the jar:
  `java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -Dkendar.configDir=... -jar build/libs/kendar-dlna.jar`
  and create a *Remote JVM Debug* configuration on port 5005.
* The web app is plain static files (native ES modules, no build step) in `src/main/resources/web`; the jar serves them from the classpath, so after editing
  a file rebuild (or let IntelliJ rebuild and restart) and reload the browser.

**5. Verbose logging**

Logging goes through slf4j to `java.util.logging`. Supply a config file to raise levels, for example a
`dev/logging.properties`:

```properties
handlers=java.util.logging.ConsoleHandler
.level=INFO
java.util.logging.ConsoleHandler.level=ALL
org.kendar.dlna.level=FINE
org.jupnp.level=INFO
```

and start with `-Djava.util.logging.config.file=dev/logging.properties`. Useful loggers: `org.kendar.dlna.delivery`
(streams, Range, transcode cache, prefetch), `org.kendar.dlna.dlna` (Browse/Search), `org.kendar.dlna.ingestion`
(scans), `org.jupnp` (SSDP and eventing).

**6. Other debugging aids**

* Rescan from the Admin page, or `touch`/edit files and trigger *Rescan*; the index is `dev/data/catalog.json`.
* Transcodes and thumbnails are cached in `dev/data/transcode` and `dev/data/thumbs`: delete them to test cold paths.
* SSDP multicast also works for a locally started server on the LAN; behind VPNs or several network adapters set
  `network.advertiseAddress`.
* Tests: `./gradlew test` (unit, fast), `./gradlew integrationTest` (manual, needs Docker or `KENDAR_IT_BASE_URL`,
  see `docs/integration-tests.md`); against your local debug server:
  `KENDAR_IT_BASE_URL=http://localhost:8200 ./gradlew integrationTest`.

## Troubleshooting

* **Server not visible in the renderer**: check host networking and a Linux host (see above), firewall (UDP 1900),
  that the renderer is on the same subnet/VLAN (multicast does not cross routers), and the log line
  `Kendar DLNA '...' listening on port 8200`. Try a different renderer (VLC on a PC) to separate renderer quirks from
  network issues.
* **Visible but nothing plays / wrong address in URLs**: wrong interface advertised; set `network.advertiseAddress`.
* **Empty library**: the log warns `No library roots configured`; edit `library.roots` in `/config/config.json` and
  restart. Then check the admin page for unsupported files and `settings.json` errors.
* **Files not writable (tag/settings edits fail)**: `PUID`/`PGID` do not match the owner of `/music`.
* **Container exits at start with "Invalid configuration"**: the log names the offending key; fix `config.json`.
* **MIDI files listed as unsupported**: no SoundFont (see above).
* **Health check unhealthy after changing the port**: set `KENDAR_PORT` to the same value.
* **Changes made on disk do not show up**: there is no filesystem watcher; run *Rescan* in the admin page (or wait
  for the schedule). Tag changes of files whose size and mtime did not change need *Re-read all tags*.
* **High memory**: heap is 70% of the container memory limit; cap it with `JAVA_OPTS=-Xmx512m`.
* **Logs for tuning client profiles**: see `docs/client-compatibility.md`.

## Licences

See `NOTICE` (jump3r/LAME, JLayer, JOrbis, JFLAC and jaudiotagger are LGPL and used unmodified; GeneralUser GS
licence is kept with the SoundFont).
