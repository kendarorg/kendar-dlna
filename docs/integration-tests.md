# Integration tests (manual)

End-to-end tests that drive the real server from a **Java UPnP control point** (jUPnP, the same library the server
uses) and plain HTTP. They are separate from the unit tests: `./gradlew build`, `check` and `test` neither compile nor
run them. They run only when asked for:

```sh
export JAVA_HOME=~/.jdks/corretto-25.0.3      # or any JDK 25
./gradlew integrationTest                      # starts a container with a generated library, ~1 min
```

Sources are in `src/integrationTest/java/org/kendar/dlna/it` (JUnit 5, AssertJ, Testcontainers 2.0.5).
Reports: `build/reports/tests/integrationTest/index.html`. Side files written to `build/`:
`integration-test-tree.txt` (the whole ContentDirectory tree as the tests saw it) and
`integration-test-container.log` (the server log of the container).

## Two modes

### 1. Container mode (default)

* The image is built from the repository `Dockerfile` through Testcontainers (build context: `Dockerfile`, Gradle
  files, `src/main`, `docker/entrypoint.sh`; Docker's layer cache makes repeated builds fast). The temporary image
  `kendar-dlna-it:test` is removed when the run ends.
* A test library is **generated on the fly** (`LibraryFactory`): WAV files from Java Sound, a MIDI file, the
  fixtures of `src/test/resources/audio` (mp3, flac, ogg, opus, aiff; tagged with jaudiotagger), per-folder
  `settings.json` (sort modes, album overlay, `random`, `views`), `front.jpg` covers, a 120 file folder for paging,
  non-ASCII names, an unreadable `.m4a` (a 4-byte `ftyp` stub), and a playlist JSON with one broken entry.
* The library and playlists are **copied into the container** (`withCopyFileToContainer`); nothing is bind-mounted.
  The container runs with `PUID=0 PGID=0` so the copied (root-owned) files stay writable for the admin API.
* The admin password is read from the container log (the `KENDAR DLNA ADMIN PASSWORD` block), the forced first
  password change is made through the REST API, and the tests wait for the initial scan to finish.
* The device description URL is built from the uuid in the log (`/upnp/dev/<uuid>/desc`) and the mapped port:
  **no SSDP is used**, the control point loads the description directly.
* The stream/art URLs in the DIDL carry the container's bridge address, which is unreachable from the test machine,
  so the tests rewrite scheme/host/port to the mapped address (`Server.rebase`).

### 2. External server mode (`KENDAR_IT_BASE_URL`)

Runs the same suite against a server that is **already running** (a host-networking deployment on the LAN, a NAS,
...). No container is started.

```sh
KENDAR_IT_BASE_URL=http://192.168.1.45:8200 \
KENDAR_IT_ADMIN_PASSWORD='my-admin-password' \
./gradlew integrationTest
```

* The device description is located by a **unicast SSDP M-SEARCH to UDP 1900 of the server host** (works with host
  networking); if that does not answer set `KENDAR_IT_DESCRIPTION_URL=http://host:8200/upnp/dev/<uuid>/desc`.
* Tests that need the generated library (exact titles, counts, `random`, tag edits of known files, copying a file
  in) are **skipped** with a reason; everything that can be derived from whatever the library contains runs: device
  description, whole-tree walk with DIDL/paging checks, stream checks of the items (capped by `KENDAR_IT_MAX_ITEMS`,
  default 150, sampled evenly), art, search consistency, GENA, playlist create/rename/delete, login/CSRF flow.
* The admin tests need `KENDAR_IT_ADMIN_PASSWORD` (user `KENDAR_IT_ADMIN_USER`, default `admin`). If the account
  still has the forced first password change pending, the admin tests are skipped (change it once in the web UI; the
  tests do not change the password of a real server). **The admin tests create and delete a playlist named
  `IT Playlist`/`IT Renamed`/`Rapid n` on that server and run a rescan.**
* The server never has to be reachable by multicast in this mode, only by HTTP. For GENA the server must be able to
  reach this machine (callback).

## Optional: SSDP multicast discovery (`DiscoveryIT`)

Multicast does not cross the Docker bridge, so this class never runs against the container. It is skipped (with the
message shown by Gradle) unless enabled, and then needs a server on the same LAN, normally the host-networking
deployment:

```sh
KENDAR_IT_SSDP=true KENDAR_IT_BASE_URL=http://192.168.1.45:8200 ./gradlew integrationTest
# or:  ./gradlew integrationTest -Dkendar.it.ssdp=true -Dkendar.it.baseUrl=http://192.168.1.45:8200
```

It sends a real M-SEARCH for `MediaServer:1`, waits for a "Kendar" device (from the host of
`KENDAR_IT_BASE_URL` when given), browses it and streams one item from the **advertised, un-rewritten URL** (which
proves the advertised address is reachable). Both the firewall of the test machine (UDP 1900 inbound for responses)
and multicast on its network adapter must allow it.

## Settings

Every setting is a system property (`-Dkendar.it.x=...` on the Gradle command line is forwarded to the tests) or an
environment variable.

| Property | Environment | Meaning |
|---|---|---|
| `kendar.it.baseUrl` | `KENDAR_IT_BASE_URL` | external mode: `http://host:port` of the running server |
| `kendar.it.descriptionUrl` | `KENDAR_IT_DESCRIPTION_URL` | external mode: device description URL (otherwise unicast SSDP) |
| `kendar.it.adminUser` | `KENDAR_IT_ADMIN_USER` | external mode: admin user (default `admin`) |
| `kendar.it.adminPassword` | `KENDAR_IT_ADMIN_PASSWORD` | external mode: admin password (admin tests skipped without it) |
| `kendar.it.image` | `KENDAR_IT_IMAGE` | container mode: run this existing image instead of building the Dockerfile |
| `kendar.it.ssdp` | `KENDAR_IT_SSDP` | `true` enables `DiscoveryIT` |
| `kendar.it.maxItems` | `KENDAR_IT_MAX_ITEMS` | external mode: cap of streamed resources / art images (default 150) |
| `kendar.it.projectDir` | | repository directory (set by Gradle; from the IDE the working directory is used) |

Docker itself is configured the usual way: `DOCKER_HOST`, `~/.testcontainers.properties`. Run a subset with
`./gradlew integrationTest --tests '*SearchIT'`; from IntelliJ run the classes in `src/integrationTest` directly.

## Remote Docker engine notes

The suite was developed against `DOCKER_HOST=tcp://192.168.1.45:2375` (a remote Linux host):

* **No bind mounts** (a Windows path does not exist on the engine): the image build context is streamed to the
  engine and the library is copied into the created container.
* Published ports live on the **engine host**: URLs use `container.getHost()` / `getMappedPort(8200)`, never
  `localhost`.
* The first image build downloads Gradle and the libraries on the engine host (a few minutes); later builds reuse the
  layers. The whole `src/main` tree (including the 32 MB SoundFont) is uploaded each time.
* **GENA** needs the container to call back to the test machine. The test machine's address is the one used to
  reach the engine (`new DatagramSocket().connect(host)`), and the callback server is a Jetty on a random port. With
  a remote engine on the same LAN this worked here (the container reaches the machine through the NAT of the
  bridge) provided the host firewall allows the Java process. If the callback cannot be delivered the two GENA
  tests are **skipped** with the reason ("the server cannot deliver GENA events to this machine"), and the polling
  tests (`rescanWithoutChangesLeavesTheUpdateIdsAlone`, `newFileAppearsAfterRescanAndUpdateIdsAdvance`,
  `playlistChangeAdvancesTheUpdateIdsOfThePlaylistsContainer`) still verify the same changes through Browse and
  `GetSystemUpdateID`.
* Testcontainers' Ryuk reaper container is used (it works with a remote engine); set
  `TESTCONTAINERS_RYUK_DISABLED=true` if your engine cannot run it.
* Cleanup: the container is removed (with its volumes) and the image `kendar-dlna-it:test` is deleted at the end.
  After a hard kill remove leftovers with `docker rmi kendar-dlna-it:test` and `docker container prune`.

## What is covered

| Class | Content |
|---|---|
| `DeviceDescriptionIT` | description XML (`MediaServer:1`, ContentDirectory/ConnectionManager), jUPnP parses the device and services, SCPD served, `GetSearchCapabilities`, `GetSortCapabilities`, `GetProtocolInfo`, `GetCurrentConnectionIDs`, `GetSystemUpdateID`, `/health` |
| `BrowseTreeIT` | walk of the **entire tree** (root, Folders, library roots, subfolders, Playlists, Random N, Albums/Artists/Genres/Titles) with 37-item pages: DIDL-Lite parsed by jUPnP `DIDLParser`, `NumberReturned`/`TotalMatches` consistency across pages, `childCount`, parent ids, unique ids, `BrowseMetadata` of every object agrees with the listing, paging boundaries (count 0, start beyond the end), 701 for unknown ids, UpdateIDs stable without changes, structure of the generated library (sort modes, album overlay, view order) |
| `StreamingIT` | for every `res`: HEAD, GET, Range (first/middle/last/open/suffix/over-long -> 206 and exact `Content-Range`/`Content-Length`/bytes), 416 with `Content-Range: bytes */N`, DLNA headers (`Accept-Ranges`, `transferMode.dlna.org`, `contentFeatures.dlna.org`), declared `res@size` equals delivered bytes, protocolInfo form, transcoded MP3 structure (size a multiple of 960, sync/MPEG-1 layer III/320 kbps/48 kHz in every frame, no ID3/Xing), res order per format, MIDI only as MP3, prefetched transcode serves byte-exact ranges, unknown streams 404 |
| `ArtIT` | every `upnp:albumArtURI` returns a decodable image with right `Content-Type`/`Content-Length`, items share the art of their folder, unknown art 404 |
| `SearchIT` | `dc:title contains` (case/accent-insensitive), class filters, `=`, `and`/`or`/parentheses, `exists`, `*`, artist/album/genre/creator, container scoping, Random/view duplicates excluded, paging, 708 for malformed criteria, 710 for an unknown container |
| `RandomIT` | Random N size and subtree, same client (IP + User-Agent) keeps its snapshot, other User-Agents get other snapshots, items stream |
| `AdminApiIT` | 401 without session, login/logout flow, `X-Requested-With`, CSRF token and cross-origin refusal, wrong/weak password change, read endpoints, **tag edit round trip** (REST write -> Browse title/genre, Search, SystemUpdateID; restored afterwards), MIDI/Opus not editable, **playlist lifecycle** (create, add, reorder, rename, delete -> visible in Browse), broken playlist entry |
| `EventingIT` | **GENA** subscription to ContentDirectory: initial event, `SystemUpdateID` + `ContainerUpdateIDs` after a playlist change and after a rescan with a new file; polling equivalents; rescan without changes leaves the ids alone |
| `DiscoveryIT` | optional, SSDP multicast (see above) |

## Known skips and expected non-passes

* `BrowseTreeIT.explicitOrderInterleavesFoldersAndFiles` is `@Disabled`: **server limitation**. With
  `"sort":"explicit"` the documented interleaving of folders and files does not reach clients, because DIDL-Lite
  produced by jUPnP lists all containers of a page before its items (`Deep, Zed, Aaa` instead of
  `Zed, Deep, Aaa`). The relative order within folders and within files is kept (asserted).
* `EventingIT.everyRapidChangeIsAnnounced` is `@Disabled`: **server limitation**. jUPnP delivers an event's values
  from a separate thread that reads them later, so commits within a few milliseconds coalesce and the earlier
  `ContainerUpdateIDs` are never announced (the latest one always is, asserted by `rapidChangesAnnounceAtLeastTheLastOne`).
  jUPnP also drops (does not defer) `SystemUpdateID` events within 200 ms of the previous one.
* GENA tests are skipped when the callback cannot reach the test machine (see above).
* External mode: tests marked "needs the generated library" are skipped; admin tests are skipped without a usable
  admin account; `RandomIT` needs a folder with `"random": N`.
* `DiscoveryIT` is skipped unless `kendar.it.ssdp=true`.
* Not covered (documented, not automated): the forced-change gating of a fresh account beyond the first login,
  `reset-password` handling (a 30 s file watcher), real renderer behaviour (see `docs/client-compatibility.md`).
