# DLNA / UPnP reference

What a renderer or control point sees. Derived from `dlna/*` (device, ContentDirectory, ids, DIDL, search),
`delivery/*` (stream and art URLs, `res` offers), `search/*`, `clients/*`, `views/*`, `shuffle/*`.

Contents: [device and services](#device-and-services) - [object ids](#object-ids) - [hierarchy](#content-hierarchy) -
[ordering](#container-ordering-inside-a-directory) - [DIDL mapping](#didl-mapping) - [res elements](#res-elements) -
[browse behaviour](#browse-behaviour) - [search](#search) - [update ids and events](#update-ids-and-eventing) -
[client profiles](#client-profiles) - [stream URLs](#stream-urls) - [artwork URLs](#artwork-urls)

## Device and services

jUPnP 3.0.5 runs on the shared Jetty server (one port, default 8200); SSDP multicast on UDP 1900.

| Item | Value |
|---|---|
| Device type | `urn:schemas-upnp-org:device:MediaServer:1` |
| UDN | `uuid:<random UUID>` persisted in `<dataDir>/device-uuid` (stable across restarts; regenerated if the file is corrupt) |
| friendlyName | `server.friendlyName` (default `Kendar DLNA`) |
| Manufacturer / model | `Kendar` / `KendarDLNA` ("Java DLNA music server", version `1`) |
| Base path | `/upnp` (jUPnP namespace) |
| Description | `/upnp/dev/<uuid>/desc` |
| Service SCPD / control / events | `/upnp/dev/<uuid>/svc/upnp-org/<Service>/desc`, `.../action`, `.../event` (jUPnP defaults; callbacks under `/cb`) |
| Advertised base URL | `http://<network.advertiseAddress or first site-local IPv4>:<port>` - used in every `res` and `albumArtURI` |
| Shutdown | `ssdp:byebye` is sent on stop |

Only the server side is started (no control-point protocols). Spec-violation reporting of jUPnP is switched off.

### ContentDirectory:1

| Action | Behaviour |
|---|---|
| `Browse` | `BrowseDirectChildren` and `BrowseMetadata`; see [browse behaviour](#browse-behaviour). `SortCriteria` is **ignored** (order comes from `settings.json`). Receives the caller's IP/User-Agent for per-client Random N and client profiles |
| `Search` | See [search](#search). `SortCriteria` ignored (hits come in catalog display order) |
| `GetSearchCapabilities` | `dc:title,upnp:artist,upnp:album,upnp:genre,dc:creator,upnp:author,upnp:class` |
| `GetSortCapabilities` | empty string |
| `GetSystemUpdateID` | The catalog snapshot version (unsigned 32 bit) |

State variables: `SystemUpdateID` and `ContainerUpdateIDs` are evented (see [events](#update-ids-and-eventing)).

### ConnectionManager:1

Stock jUPnP implementation, not customised beyond the protocol list: `GetProtocolInfo`, `GetCurrentConnectionIDs`,
`GetCurrentConnectionInfo` (plus the optional `PrepareForConnection`/`ConnectionComplete` as shipped by jUPnP). The
**Source** list is: the native protocolInfo of every format except MIDI, the transcoded-MP3 protocolInfo, and LPCM
protocolInfo for 32000, 44100 and 48000 Hz. **Sink** is empty.

## Object ids

`<id>` parts are 16-character URL-safe stable hashes (`StableIds`: SHA-256 of kind + NFC path, 12 bytes, base64url),
so ids survive rescans and restarts and change only when a path changes. Kinds: `f` folder, `t` track, `p` playlist,
`va`/`vr`/`vc`/`vg` view groups (album, artist, composer, genre).

| Object id | Meaning |
|---|---|
| `0` | Root |
| `folders` | "Folders" container (library roots) |
| `playlists` | "Playlists" container |
| `f:<folderId>` | A real directory (including library roots) |
| `t:<trackId>` | A track as listed in its real directory (and as a search hit) |
| `c:<context>` | A virtual container; `<context>` is one of the context ids below |
| `i:<context>:<trackId>` | A track as listed inside a virtual container |

Context ids (also the `containerId` of stream URLs; they contain a `.`, folder ids do not):

| Context | Container |
|---|---|
| `p.<playlistId>` | a playlist |
| `r.<dirId>.<snapshotId>` | "[Random N]" snapshot of directory `<dirId>` (snapshot id = 64-bit seed in hex) |
| `n.<dirId>` | "[Recently added]" of directory `<dirId>` |
| `v.<dirId>.<kind>` | view root; kind `al` albums, `ar` artists, `ge` genres, `ti` titles, `co` composers |
| `v.<dirId>.<kind>.<segment>[.<segment>...]` | nested view container. Segments: `b<A-Z>` / `b0` bucket (letters, `#`), `<va-hash>` album, `<vr-hash>` artist, `<vc-hash>` composer, `<vg-hash>` genre, `all` the "All tracks" of an artist |

Stream URLs of tracks listed under a real directory use the **folder id** (no prefix) as container id; tracks listed in
virtual containers use the context. For a track found by Search or `t:`, the container is its parent directory's id.

## Content hierarchy

```
0 Root
|- folders                      "Folders"
|   `- f:<root>                 one per library root (title = displayName | album.title | root name)
|       |- c:r.<dir>.<snap>     "[Random N]"            if settings.random > 0
|       |- c:n.<dir>            "[Recently added]"      if settings.recent > 0
|       |- c:v.<dir>.al ...     "[Albums]" "[Artists]" "[Genres]" "[Titles]" "[Composers]"  per settings.views, in that order
|       |- f:<subdir> ...       subdirectories and tracks (t:<id>) per settings.sort
|       `- t:<id> ...
`- playlists                    "Playlists"
    `- c:p.<playlistId>         playlist (items i:p.<id>:<trackId>; broken entries skipped)
```

Virtual container trees (all over the directory's whole **subtree**, with the album overlay of each track's own
directory applied):

| Container | Structure |
|---|---|
| `[Random N]` | N tracks picked at random (seeded shuffle of the subtree sorted by path), flat; one snapshot per client (IP + User-Agent) and directory |
| `[Recently added]` | N tracks, newest first by first-seen time (`addedAt`), then mtime (newest), then path; flat |
| `[Albums]` | albums sorted by title then artist; tracks by disc, track no., file name. Albums are grouped by (album artist, album); titles that collide get ` (Artist)` appended; tracks without an album tag go to "Unknown Album" grouped by track artist. Year = lowest year of the album |
| `[Artists]` | artist (album artist(s) else track artist(s); multi-value tags yield one entry per name; none = "Unknown Artist") -> that artist's albums (by year then title) plus an "All tracks" container |
| `[Composers]` | same shape as Artists, over composers; none = "Unknown Composer" |
| `[Genres]` | genre (multi-value aware; none = "Unknown Genre") -> albums -> tracks |
| `[Titles]` | all tracks sorted by title |

Names are grouped by normalized key (case, accents, whitespace); the displayed spelling is the most frequent variant.
When the artist list or the title list has more than `views.bucketThreshold` (default 500) entries it is split into
`A`-`Z` and `#` bucket containers (a letter container holds the entries starting with it; `#` everything else).
(A directory can override the limit with `bucketThreshold` in its `settings.json`, editable in Directory settings.)
Albums and Genres are never bucketed.

Random N snapshot lifecycle: created when the directory is browsed; reused while it is not expired; expires after
`random.ttlMinutes` (default 30) without browse/stream activity and **never while a stream from it is active**;
a snapshot that expired but is still the client's current one is kept up to 24 h. A new snapshot is made when N changes
or it expired. Tracks removed by a rescan are skipped, not an error. In memory only.

## Container ordering inside a directory

`BrowseDirectChildren` of `f:<dir>` returns, in this order:

1. `[Random N]` (if `random > 0`)
2. `[Recently added]` (if `recent > 0`)
3. the enabled views, in `settings.views` order
4. the real children, ordered by `settings.sort`:

| `sort` | Subdirectories | Files |
|---|---|---|
| `filename` (default) | by name | by file name |
| `title` | by display title | by tag title (file name without extension if none), then file name |
| `track` | by display title | by disc no., track no. (missing last), then file name |
| `mtime` | by modification time, then name | by modification time, then name |
| `explicit` | names from `order` first, interleaved, in that order; the rest: subdirectories then files by name. Unknown names are ignored (reported as `missingOrderEntries` in the REST API) | |

**Known limitation (explicit order):** the interleaving of directories and files that `explicit` defines is built
correctly in the catalog (and in the REST API), but the DIDL-Lite generated by jUPnP writes all containers of a page
before its items, so a DLNA client sees the directories first and the files second (relative order within each group
is kept). See `docs/integration-tests.md`. Paging (`StartingIndex`/`RequestedCount`)
applies to the catalog order, not to this re-grouped order.

Non-explicit modes always list subdirectories before files. Comparisons are natural (digit runs compared numerically,
"Track 2" before "Track 10"), case-, accent- and whitespace-insensitive.

## DIDL mapping

### Containers

| Container | `upnp:class` | `dc:title` |
|---|---|---|
| Root, Folders, Playlists (`0`, `folders`, `playlists`) | `object.container.storageFolder` | `Root`, `Folders`, `Playlists` |
| Real directory | `object.container.storageFolder` | `displayName` verbatim if set; else `album.title` (else directory name), plus ` (CD n)` when `album.discNumber` is set and the name does not already mention that disc (`CD 2`, `Disc 2`, `Disk02`) |
| Virtual album (a file split by a cue sheet or chapters) | `object.container.album.musicAlbum` | album title (cue / chapters, list.json `partOf`), `upnp:artist` = album artist; id `f:<id of the file path>`, parent = the file's directory; items are its tracks, `childCount` = number of tracks; see [features.md](features.md#cue-sheets-and-chapters-virtual-albums) |
| Playlist | `object.container.playlistContainer` | playlist name |
| `[Random N]`, `[Recently added]`, view roots, buckets, "All tracks" | `object.container.storageFolder` | `[Random 25]`, `[Recently added]`, `[Albums]`, `[Artists]`, `[Genres]`, `[Titles]`, `[Composers]`, `A`..`Z`/`#`, `All tracks` |
| Album node | `object.container.album.musicAlbum` | album title (+ `upnp:artist` = album artist, `dc:date` = `YYYY-01-01`) |
| Artist / composer node | `object.container.person.musicArtist` | name |
| Genre node | `object.container.genre.musicGenre` | genre |

All containers are `restricted="1"`. `childCount` is emitted only when the Filter asks for it (`@childCount`,
`container@childCount` or `*`). Real directory: `childCount` = (Random?1) + (Recent?1) + number of enabled views +
number of real children. `parentID`: Root `-1`; `folders` and `playlists` -> `0`; a root directory -> `folders`; other
directories -> `f:<parent>`; Random and view roots -> `f:<dir>`; nested view nodes -> `c:<parent node>`; playlists ->
`playlists`; items in virtual containers -> `c:<context>`.

`upnp:albumArtURI` (with `dlna:profileID="JPEG_TN"`) on: directories that have art (`front.jpg`, else the embedded art
of the first track having some: direct tracks first, then subdirectories depth-first), and album/artist/genre nodes
(art of their first track).

### Items

`upnp:class` = `object.item.audioItem.musicTrack`, `restricted="1"`.

| DIDL | Source |
|---|---|
| `id` | `t:<trackId>` in a directory / search; `i:<context>:<trackId>` in a virtual container |
| `dc:title` | tag title, else file name without extension |
| `upnp:artist`, `dc:creator` | track artist, else (effective) album artist; omitted if neither |
| `upnp:author role="Composer"` | all composers joined with `, ` (only when the file has a composer) |
| `upnp:album` | effective album (directory overlay `album.title` wins over the tag) |
| `upnp:genre` | effective genre (overlay wins) |
| `upnp:originalTrackNumber` | track number tag |
| `dc:date` | `YYYY-01-01` from the effective year (overlay wins), when > 0 |
| `upnp:albumArtURI` | `/art/f/<dirId>/tn` when the directory has `front.jpg`, else `/art/t/<trackId>/tn` when the track has embedded art; absent otherwise. With `dlna:profileID="JPEG_TN"` |
| `res` | see below |

The disc number is not emitted. Optional properties are filtered by the request `Filter` (blank or `*` = everything;
a comma list otherwise; asking for an attribute `res@duration` implies `res`; asking for `res` implies its attributes);
required properties (`id`, `parentID`, `restricted`, `dc:title`, `upnp:class`) are always present.

## `res` elements

For every track the server decides, per client profile, which `res` elements to publish. Default profile: MP3 -> the
original only; every other format -> **transcoded MP3 first, original second**; MIDI -> transcoded MP3 only (MIDI has
no native `res`). Profiles can change the order, hide the original, switch to LPCM, or keep only the first `res`
(see [client profiles](#client-profiles)). With `transcode.enabled=false` only native `res` are offered (MIDI then
has none and is unsupported). A playable item never ends up without a `res`. Tracks of a cue sheet / chapters album
(`<file>#n`) have **no native `res`**: only the transcode of their exact time range is offered (MP3 by default, LPCM when
the profile asks for it), with exact `size` and `duration`; a request for the `native` URL gets the MP3 form.

### Attributes

| Attribute | Native | Transcoded MP3 | LPCM |
|---|---|---|---|
| `size` | file size | exact byte length (when the source sample count is known; MIDI: only after its first render) | exact (`samples * 4`) |
| `duration` | tag/decoded duration `H:MM:SS.mmm` | from exact sample count (MIDI: rendered length once known) | same |
| `bitrate` (bytes/s) | `bitrateKbps * 125` | `kbps * 125` | `rate * 4` |
| `sampleFrequency` | source rate | 48000 | source rate (MIDI 48000) |
| `nrAudioChannels` | source channels | 2 | 2 |

Attributes with value 0/unknown are omitted. `res` text = the stream URL (see below).

### protocolInfo

`http-get:*:<mime>:<fourth field>`

| Form | mime | Fourth field |
|---|---|---|
| MP3 original | `audio/mpeg` | `DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000` |
| OGG Vorbis, Opus original | `audio/ogg` | `DLNA.ORG_OP=01;DLNA.ORG_FLAGS=0170...` |
| FLAC | `audio/flac` | same |
| WAV | `audio/wav` | same |
| AIFF | `audio/aiff` | same |
| AAC, ALAC (`.m4a`, `.mp4`) | `audio/mp4` | same (no `DLNA.ORG_PN`) |
| raw AAC (ADTS) | `audio/aac` | same |
| Transcoded MP3 | `audio/mpeg` | `DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=0170...` |
| LPCM | `audio/L16;rate=<source rate>;channels=2` | `DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=0170...` |
| Thumbnail (art) | `image/jpeg` is implied by the URL; headers: `DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=00;DLNA.ORG_FLAGS=00D00000000000000000000000000000` | |

`DLNA.ORG_OP=01` = byte-range seek supported. `DLNA.ORG_CI=1` = converted content. The flags word `01700000...` is
the usual audio "streaming + background + connection stalling + DLNA 1.5" set (bit meaning per the DLNA guidelines,
not defined in this code base). `MIDI` has no protocolInfo of its own (only the rendered MP3/LPCM forms).

## Browse behaviour

* `ObjectID` accepts `0`, `folders`, `playlists`, `f:`, `t:`, `c:`, `i:` ids. Unknown ids (including an expired
  `[Random N]` snapshot, an `i:` id whose context is gone) -> UPnP error **701 No such object**.
* `BrowseDirectChildren` on an item (`t:`/`i:`) -> **720 Cannot process the request**. An unrecognised `BrowseFlag`
  value is treated as `BrowseDirectChildren`.
* `BrowseMetadata` returns the one object with its `parentID`.
* Paging: `StartingIndex` >= total gives an empty page; `RequestedCount=0` = all remaining; `TotalMatches` is always
  exact; `NumberReturned` is the page size.
* `UpdateID` in the result: the container's own ContainerUpdateID for `folders`, `playlists`, real directories,
  playlists, Random N and view containers (0 if it never changed); the SystemUpdateID for Root and items.
* Opening a directory that has `random > 0` creates (or reuses) the caller's snapshot.
* Unexpected exceptions become **720** with the exception text.

## Search

`Search` with `ContainerID`, `SearchCriteria`, `Filter`, `StartingIndex`, `RequestedCount`.

* Scope: `0` or `folders` = the whole library; `f:<id>` = the subtree below that directory (directory itself
  excluded); any other id (playlists, virtual containers, items, unknown) -> **710 No such container**.
* Hits: tracks (`object.item.audioItem.musicTrack`) and directories (`object.container.storageFolder`), depth first
  with a directory before its content (display order), mapped exactly like Browse (item ids are `t:`/`f:` ids with their
  real parent). `[Random N]`, view containers and their items are never hits (the real tracks are).
* `TotalMatches` exact; `UpdateID` = SystemUpdateID.
* Malformed criteria -> **708 Invalid search criteria**.

### Grammar

```
criteria := '*' | or
or       := and ( 'or' and )*          -- 'and' binds tighter than 'or'
and      := primary ( 'and' primary )*
primary  := '(' or ')' | property op value
op       := contains | doesNotContain | = | != | derivedFrom | exists
value    := '"' text '"'   (backslash escapes a quote or a backslash)   |   true | false after 'exists'
```

Keywords and property names are case-insensitive; nesting depth <= 50; empty criteria, unterminated strings, `<`, `<=`,
`>`, `>=`, unquoted values are errors (708).

### Properties

| Property | Field searched |
|---|---|
| `dc:title` | track: tag title **or** file name without extension; directory: display title, directory name, `displayName`, `album.title` |
| `upnp:artist`, `dc:creator` | track artist **or** (effective) album artist (either may match) |
| `upnp:album` | effective album (overlay applied) |
| `upnp:genre` | effective genre |
| `upnp:author`, `upnp:composer` | composer(s) |
| `upnp:albumArtist` | effective album artist |
| `upnp:class` | `object.item.audioItem.musicTrack` or `object.container.storageFolder` (selects tracks / directories) |
| any other well-formed property (e.g. `@refID`) | accepted as "absent": comparisons never match, `exists false` matches everything, `exists true` nothing |

`upnp:albumArtist` and `upnp:composer` are accepted but not listed in `GetSearchCapabilities`.

### Operators

All string comparisons are normalized (Unicode decomposed, accents stripped, lower-cased, whitespace collapsed).

| Operator | Meaning |
|---|---|
| `contains` | substring |
| `doesNotContain` | no value contains it; **false when the field is absent** (so `upnp:artist doesNotContain "x"` never matches a directory) |
| `=`, `!=` | equality of normalized strings; `!=` is false when the field is absent |
| `derivedFrom` | prefix match (fields); for `upnp:class`: equal to the class or a subclass (`object.item` matches tracks, `object.container` matches directories) |
| `exists true/false` | field present / absent |

For `upnp:class`, `=`/`!=` compare the class string exactly, `contains`/`doesNotContain` are substring tests.

Examples:

```
upnp:class derivedFrom "object.item.audioItem" and dc:title contains "love"
(upnp:artist contains "beatles" or upnp:author contains "lennon") and upnp:genre = "Rock"
upnp:class = "object.container.storageFolder" and dc:title contains "live"
```

## Update ids and eventing

* `SystemUpdateID` = version of the catalog snapshot; bumped **once per committed change** (a rescan that changed
  something, a tag/settings/playlist/art edit, a vanished file). A scan that changes nothing keeps it (only the last-scan
  time moves). Persisted in `<dataDir>/catalog.json` together with the per-container ids, so clients' caches stay
  consistent across restarts.
* Per-container ids: `folders`, `playlists`, each real directory, each playlist, and one per directory for its virtual
  containers (Random N, Recently added, views). A directory changes when its settings, art, direct tracks or direct
  subdirectories change (its mtime counts only for `sort: mtime`); the virtual containers of a directory and of **every
  ancestor** change when anything in the subtree changes.
* GENA events (`SystemUpdateID`, `ContainerUpdateIDs`) are sent to subscribed control points after each commit.
  `ContainerUpdateIDs` is `id,updateId,id,updateId,...` with the object ids `folders`, `playlists`, `f:<id>`,
  `c:p.<id>`, and for directories with virtual containers `c:n.<dir>` (when `recent > 0`) and each view root
  `c:v.<dir>.<kind>`. `[Random N]` containers are per client and are **not** announced.
  `ContainerUpdateIDs` is not rate-moderated, `SystemUpdateID` fires first.

## Client profiles

Profiles decide per renderer which formats are native, the order of the `res` elements, MP3 vs LPCM and quirks.
Resolution: for each Browse/Search the profile whose `match` holds first wins - inline `clients.profiles`, then the
profiles of `clients.profilesFile`, then (if `clients.builtins`) the built-in ones - else the default profile.
A user profile with a built-in's id replaces that built-in. Full field and validation reference:
[configuration.md](configuration.md#clients-section). Built-ins (provisional guesses, see
[client-compatibility.md](client-compatibility.md)): `vlc`, `bubbleupnp`, `kodi`, `foobar2000`, `wmp`, `samsung-tv`,
`lg-tv` (`src/main/resources/clients/builtin-profiles.json`).

`res` plan per format (`ClientProfile.resPlan`):

1. MIDI: transcoded only.
2. Format in `nativeFormats` (and not `MP3` with quirk `AVOID_NATIVE_MP3`): the original only.
3. Else with quirk `HIDE_UNPLAYABLE_ORIGINAL`: transcoded only.
4. Else `resOrder` `TRANSCODED_FIRST` (default): transcoded, original; `NATIVE_FIRST`: original, transcoded.
5. Quirk `SINGLE_RES`: keep only the first element of the plan.

The transcoded form is MP3 (CBR at the profile's `bitrateKbps`, 0 = `transcode.bitrateKbps`) or LPCM
(`transcodeTarget`). The client identity used for Random N snapshots is **IP + User-Agent**.

## Stream URLs

```
http://<host>:<port>/stream/<containerId>/<itemId>[/<variant>]
```

| Variant | Output |
|---|---|
| none or `native` | the original file (404 for MIDI) |
| `mp3` | MP3 CBR at `transcode.bitrateKbps` |
| `mp3-<kbps>` | MP3 CBR at 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256 or 320 kbps |
| `lpcm` | `audio/L16;rate=<source rate>;channels=2`, big-endian s16 |

The URL alone decides the output (reproducible and cacheable). The `res` URL for the default bitrate uses plain `mp3`;
`mp3-<kbps>` appears only for a profile bitrate that differs from the global one.

Status codes: `200`, `206` (Range), `404` (malformed path, unknown variant or bitrate, unknown item, a virtual
context - one containing `.` - that is unknown or expired, native MIDI, transcoded variant when transcoding is off or
the format cannot be transcoded, source file missing on disk - the track is then removed from the catalog), `405`
(`Allow: GET, HEAD`), `416`, `500` (transcode failed). The `containerId` of a real-directory context is **not**
validated beyond the track lookup; only the item id selects the file.

Headers: `Content-Type`, `Content-Length` (exact), `Accept-Ranges: bytes`, `transferMode.dlna.org: Streaming`,
`contentFeatures.dlna.org` (the protocolInfo fourth field of the form), `Content-Range` for 206/416.

### Range and HEAD

* `HEAD` returns the same headers as `GET` without a body (for MIDI this renders the file first, because its length is
  only known afterwards).
* Single byte range `bytes=a-b`, `bytes=a-`, suffix `bytes=-n`: `206`. The end is clipped to the size. `first >= size`
  or an empty suffix -> `416` with `Content-Range: bytes */<size>` and `Content-Length: 0`. Malformed headers and
  multi-range requests are ignored (full `200`).
* Transcoded streams: a cached transcode serves exact byte ranges from its file; an uncached one starts a fresh encoder
  at the frame containing the offset. The live stream from byte 0 is identical to the cached file. MP3 length is
  `ceil(samples at 48 kHz / 1152) * (144 * kbps * 1000 / 48000)` bytes; LPCM `samples * 4`.
* **Prefetch**: when a stream starts, the next item of the same context (folder, playlist, Random N, view; no
  wrap-around) is encoded into the cache if it needs transcoding and is not cached (per client and context, one job,
  cancelled when the client plays another item). See [features.md](features.md#transcoding-prefetch-and-cache).
* A stream registers in the admin "active streams" list for its duration and keeps a Random N snapshot alive.

## Artwork URLs

| URL | Image |
|---|---|
| `/art/t/<trackId>` | full size: `front.jpg` of the track's directory, else the track's embedded art |
| `/art/f/<folderId>` | full size: `front.jpg`, else embedded art of the directory's first track having some (direct tracks, then subdirectories depth-first) |
| `.../tn` | thumbnail: JPEG, at most 160 px on the longer side, quality 0.85, never upscaled, transparency flattened on white; cached in `<dataDir>/thumbs` |

DIDL always points at the `/tn` form. `rear.jpg` is not reachable through DLNA (admin UI only).

`GET`/`HEAD` only (`405 Allow: GET, HEAD`); `404` for unknown ids, unknown suffix or no art. Headers: `ETag` (strong,
changes with the source file version or thumbnail variant), `Last-Modified`, `Cache-Control: public, max-age=3600`,
`transferMode.dlna.org: Interactive`, `contentFeatures.dlna.org` (thumbnail:
`DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=00;DLNA.ORG_FLAGS=00D00000000000000000000000000000`; full size
`DLNA.ORG_OP=00;DLNA.ORG_FLAGS=00D0...`), `Content-Type` (thumbnail `image/jpeg`; full size sniffed: png, gif, bmp, else
jpeg), `Content-Length`. `If-None-Match` matching the ETag (or `*`) answers `304`. Ranges are not supported for art.
