# Features

Functional overview, by area: how it behaves and where it is configured. Derived from the code. Related references:
[configuration.md](configuration.md) (`config.json`), [rest-api.md](rest-api.md), [dlna.md](dlna.md).

Contents: [library and scanning](#library-and-scanning) - [settings.json](#settingsjson-reference) -
[virtual containers](#virtual-containers) - [playlists](#playlists) - [search](#search) - [artwork](#artwork) -
[formats](#formats-matrix) - [transcoding, prefetch, cache](#transcoding-prefetch-and-cache) -
[MIDI and SoundFont](#midi-rendering-and-soundfont) - [web UI](#web-ui-walkthrough) -
[security](#security-and-password-lifecycle) - [deployment](#deployment-pointers) -
[known limitations](#known-limitations)

## Library and scanning

| Aspect | Behaviour | Configured by |
|---|---|---|
| Roots | Named directories: `"Music": "/music"`. The root name is the first segment of every object path. Order = configured order (display order under "Folders"). Names must not contain `/` or `\`. A root that is not a directory is skipped with a scan error | `library.roots` |
| What is scanned | Directories recursively (hidden entries - names starting with `.` - are skipped; symbolic links are not followed). Files are recognised **by extension** (see [formats](#formats-matrix)); `settings.json`, `front.jpg`, `rear.jpg` (case-insensitive) in a directory are read as directory settings and art; `list.json` as per-file metadata ([reference](#listjson-reference)) | - |
| Unsupported files | Audio-looking files that are not served are listed (path, size, reason) on the Admin page and `GET /api/v1/admin/unsupported`: extensions with no decoder (`.wma .ape .wv .mpc .m4b .alac .dsf .dff`), formats refused by a decoder probe (HE-AAC, ...), MIDI when rendering is unavailable, and unreadable files (`unreadable: ...`, also counted as scan error) | - |
| Tag reading | Parallel (8 at a time, virtual threads). A file that cannot be read never fails the scan | - |
| Incremental rescan | A track whose **mtime and size** equal the indexed ones (and whose kind - extension/codec - is the same) is taken over without reading its tags. Directories, `settings.json`, `list.json`, art and playlists are always re-read (cheap); when a directory's `list.json` content changed since the last scan, the tags of that directory's tracks are re-read too | - |
| Force re-read | `force` ignores the mtime+size shortcut and re-reads every file in scope. Use after an upgrade that reads more tags (e.g. composer) or when tags changed without size/mtime change. First-seen time (`addedAt`) is kept | Admin page / Library page tick box "Re-read all tags"; REST `POST /api/v1/admin/rescan {"force":true}` |
| Scope | Whole library, or one directory subtree (`path`); the rest is taken over from the current catalog | same |
| Triggers | `STARTUP` (full scan when there is no usable index), `CATCH_UP`, `SCHEDULED`, `MANUAL` | - |
| Schedule | 5-field cron in the **local** time zone (`TZ` in Docker), default `0 */12 * * *`. Syntax: `*`, numbers, ranges `a-b`, steps `*/n` and `a-b/n`, comma lists, month/weekday names (`jan`, `mon`), weekday 0 and 7 = Sunday; if both day-of-month and day-of-week are restricted either matches (classic cron) | `scan.cron` |
| Catch-up | After a fast start from the persisted index, a background (incremental) rescan of the whole library starts when the last completed scan is older than the threshold (default 12 h) | `scan.catchUpThresholdHours` |
| No overlap | Only one scan at a time: a manual start answers `409 scan_running`; a scheduled/catch-up start is skipped with a log line | - |
| Staged commit | A scan builds a candidate catalog and commits it at the end: one SystemUpdateID bump, per-container ids only for changed containers, DLNA events, stale transcode cache entries dropped, index persisted. A scan that changes nothing keeps the ids (only the last-scan time moves). A failed scan leaves the catalog unchanged (reported in the last-scan report) | - |
| Persisted index | `<dataDir>/catalog.json`: the whole catalog (folders with settings, tracks with tags/technical data/`addedAt`/exact sample counts/rendered MIDI lengths, unsupported files, update ids). Written atomically (temp file + rename). Loaded at start so the server answers at once; ignored (-> full scan first) when missing, corrupt, written by an older format (current format version 2), made for **other library roots** (fingerprint of names+paths), or made under a different capability set (`v9`, `+midi` when MIDI is available - so toggling MIDI support causes one full scan) | `library.dataDir` |
| `addedAt` | Epoch millis when a scan first saw the file. On the very first full scan: file creation time (mtime if the file system has none), so the initial "Recently added" order is sensible; later, a file not in the previous index gets the time of the scan that found it. Survives rescans, forced re-reads and tag edits. A renamed or moved file counts as new | - |
| Vanished files | A file that disappears between scan and request answers 404 on the stream URL and is removed from the catalog at that moment | - |
| No watcher | There is no filesystem watcher: changes made on disk appear after a rescan | - |
| IDs | Object ids are stable hashes of the path (they do not depend on scan order) | - |

## Per-file sections list (`<file>.json`)

A big file split by a cue sheet or chapters (one flac / mp3 holding a whole concert) can have its own list instead of
cluttering the directory's `list.json`: a sidecar named after the **full file name plus `.json`**, next to the file
(`concert.flac` -> `concert.flac.json`; the name matches case-insensitively).

```json
[
    { "partOf": "Live at the Hall", "author": "The Band" },
    { "index": 1, "title": "Opening" },
    { "index": 2, "start": "03:27.500", "title": "Middle", "author": "Guest" }
]
```

* **Same schema and tolerant parsing as `list.json`** (comments, trailing commas, BOM, ...; a bare object is one
  entry). `filename` is optional and, when given, must be the file itself; entries naming another file are skipped with
  a warning. An entry without `index` / `start` is about the whole file (album title `partOf`, album artist `author`);
  with them it is about one section ([sections](#listjson-reference)). It also works for an unsplit file (whole-file
  entry: `title`, `author`, ...).
* **Precedence** (highest first): the file's sidecar, the directory `list.json`, the cue sheet / chapters, the tags.
  A file with a usable sidecar ignores the directory `list.json` completely; other files keep using it. A sidecar that
  cannot be parsed or has no entries is ignored (a warning is logged) and the directory `list.json` applies again.
* **Rescans:** creating, editing or deleting a sidecar is noticed by the next scan (its content hash is part of the
  directory's list hash, like `list.json`), which rebuilds the directory's files; unchanged lists reuse everything.
  A stray `x.flac.json` without `x.flac` is ignored. Existing section entries inside `list.json` keep working.
* **Editing:** Library page, "Sections" link on the file's row (or on the virtual album) opens the same table editor as
  the titles list, for that file only, with the "Generate from cue/chapters" button; saving with no entries deletes the
  sidecar. The directory's titles list page links to the files that have a sidecar. REST:
  [`/admin/folders/list/file`](rest-api.md#per-file-sections-list-getput-apiv1adminfolderslistfilepath-and-post-listfilegeneratepath).

## `list.json` reference

Optional file in a directory (one per directory, no inheritance): per-file metadata for files that carry few or no
tags, typically MIDI. Created and edited from the web UI (Library page, "Titles list" button, or the link on the
Directory settings page) or by hand (then rescan; the next scan notices the changed content by itself).

```json
[
    { "filename": "franpani.mid", "author": "FRANCK, César (1822-90)", "partOf": "",
      "title": "Panis Angelicus", "transcriber": "F.Raborn" }
]
```

| Field | Becomes | Notes |
|---|---|---|
| `filename` | - | file name in this directory, matched **case-insensitively** (Unicode-normalized); a path is reduced to its last segment |
| `title` | track title | shown instead of the file name by DLNA, the REST API, the web player and search |
| `author` | artist | replaces the artist (and multi-value artists) |
| `partOf` | album | a collection name; the folder's `settings.json` `album.title` overlay still wins over it |
| `transcriber` | extra tag `transcriber` | exposed in the REST track objects (`transcriber`) and the web track info; not used by DLNA |
| `index` | - | optional, whole number from 1: the entry is about **track n of a cue / chapter split file** (the virtual track `filename#n`) instead of the whole file |
| `start` | - | optional, `mm:ss.fff` (also `h:mm:ss.fff` or plain seconds): the entry is about the section of the file that **starts there** (within 0.5 s). Written normalized as `mm:ss.fff`; informational when `index` is also given |

* **Precedence:** a non-blank list.json value replaces the embedded tag of the same field; blank or absent fields leave
  the embedded tags alone. The list therefore wins over tags (MIDI files have few anyway); `settings.json` `album`
  still overlays the album-level fields on top. Editing the `title`/`artist`/`album` tag of a listed file in the tag
  editor is masked by the list value until the entry is removed.
* **Sections** (cue sheets, chapters): `filename` is the *source* file (`disc.flac`); `index` and/or `start` pick one of its virtual
  tracks. Precedence for track n starting at time t: (1) the entry with `index` n - an old-style entry named
  `disc.flac#n` is the same thing -, (2) else the entry with a `start` closest to t (within 0.5 s), (3) none. The index
  wins when an entry has both. An entry without `index` and `start` is about the whole file (for a split file: the album,
  `partOf` = album title, `author` = album artist) and never matches a section. A repeated identity (same file and
  index / start) keeps the **first** entry. A malformed `index` (not a whole number >= 1) or `start` skips the entry with a
  warning. Entries without these fields keep working unchanged. Section entries merge field by field over the cue /
  chapter data like any entry over tags.
* **Generate from cue/chapters:** on the list.json page the button "Generate from cue/chapters" asks the server
  (`POST /admin/folders/list/generate`, preview only) for one entry per virtual track of the directory - `filename`,
  `index`, `start`, `title`, `author` (the track performer), `partOf` (the album title) and `transcriber` - and merges them
  into the table: new sections are added, existing entries only get their *empty* fields filled, unless "Overwrite the
  entries I already edited" is ticked; touched rows are highlighted and nothing is written until you press Save. Editing the
  entries of split files takes effect at once (like other entries).
* **Tolerant reading:** UTF-8 (BOM or not), UTF-16 with BOM or Windows-1252 text; comments, trailing commas and single
  quotes are accepted; numbers are read as text; field names are case-insensitive. Entries that are not objects or have
  no `filename` are skipped, a repeated file name keeps its **first** entry, entries for missing files are ignored.
  A file that is not usable at all (not JSON, not an array) is logged, shown as an error on the Library page and tree
  and treated as absent; browsing never fails.
* **Admin editor:** table with a file select per row (only unused files are offered), author, part of, title and
  transcriber; "Add the missing files" adds a row for each audio file without an entry in one click; "Set a field for
  all entries" fills a column; rows without any metadata are dropped on save and saving no entries deletes the file.
  Saving writes the file atomically (4-space indentation, all five fields) and updates the catalog at once; if the file
  changed on disk after it was opened the save is refused with a conflict (409) and the page offers to reload.

## `settings.json` reference

Optional file in a directory; **no inheritance** - it only affects its own directory. Created and edited by the
Directory settings page or by hand (then rescan). A malformed file is logged, shown in the UI (Library page banner,
Admin page "Invalid settings.json"), defaults are used and browsing never fails. An empty file means defaults.
Unknown keys are ignored when reading and preserved when the UI saves a valid file.

| Field | Type / values | Default | Validation | Effect |
|---|---|---|---|---|
| `sort` | `filename` `title` `track` `mtime` `explicit` (case-insensitive) | `filename` | other value: file invalid | Ordering of the **direct children**: subdirectories first, then files, except `explicit` (see [dlna.md](dlna.md#container-ordering-inside-a-directory)) |
| `order` | array of strings | `[]` | each entry a plain name: not blank, no `/` or `\` (checked on save); non-array/non-string: file invalid | Used by `sort: explicit`: child names in the wanted order, files and subdirectories interleaved. Unlisted children follow (subdirectories, then files, by name). Duplicate names: first wins. Names matching nothing are ignored (shown as "not found" in the editor, `missingOrderEntries` in the API). The editor keeps a stored `order` when another sort mode is selected |
| `displayName` | string | none | JSON string, <= 255 characters (on save); trimmed, blank = unset | Replaces the directory name in DLNA Browse (also a root under "Folders"), search, and the web UI. **Verbatim**: no ` (CD n)` suffix. Wins over `album.title`. The directory is not renamed and ids stay path-based |
| `album.title` | string | none | blank = unset | Overrides the album tag of every track of this directory in all output (DIDL, views, search); becomes the directory title when `displayName` is unset |
| `album.albumArtist` | string | none | | Overrides the album artist; used by the Albums and Artists views and as `upnp:artist` fallback |
| `album.year` | integer or numeric string | none | 0-9999 on save; non-numeric: file invalid | Overrides the year (`dc:date`) |
| `album.genre` | string | none | | Overrides the genre |
| `album.discNumber` | integer | none | 0-999 on save | Overrides the disc number; the directory title gets ` (CD n)` |
| `album.discTotal` | integer | none | 0-999 on save | Overrides the disc total (not shown in DIDL; used for sync) |
| `random` | integer N | 0 (off) | non-negative integer (JSON int, not a string); <= 100000 on save | N > 0 adds the container `[Random N]` |
| `recent` | integer N | 0 (off) | same | N > 0 adds `[Recently added]` |
| `views` | array of `album` `artist` `genre` `title` `composer` | `[]` | other value: file invalid; duplicates removed | Adds `[Albums]`, `[Artists]`, `[Genres]`, `[Titles]`, `[Composers]` in this order |
| `keepWholeFiles` | boolean | `false` | not a boolean: file invalid | Keeps the whole audio file listed next to the virtual album that a cue sheet / chapters split it into (see [Cue sheets and chapters](#cue-sheets-and-chapters-virtual-albums)). Editable in the web UI: Directory settings, fieldset "Cue sheets and chapters", checkbox "Keep the whole files listed" (saving without the field never drops a stored `true`) |

Album overlay rules: non-null `album.*` fields win over the tags **in all output** (DIDL, views, search); per-track
fields (title, track number, artist, composer) always come from the tags. A directory is **out of sync** when an
overlay field is set and at least one track that can hold album tags has a different tag (missing counts as different);
MIDI, Opus and raw `.aac` tracks are ignored for that test. "Apply to tags" writes the overlay into files, "From tags"
fills the overlay from the files.

**Directory title** (DLNA, UI): `displayName` verbatim; else `album.title` or the directory name, followed by
` (CD n)` when `album.discNumber` = n, **unless** the name already says that disc (case-insensitive `CD 2`, `Disc 2`,
`Disk02`, `cd-2`, ...).

Saved files are written as JSON with keys `sort`, `order`, `album`, `random`, `recent`, `displayName`, `views`; keys that
equal the default are omitted.

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

Minimal examples (three separate files):

```text
an album folder:        { "sort": "track" }
a library root:         { "random": 100, "recent": 30, "views": ["artist"] }   (whole-library random + newest + artists)
a second disc:          { "displayName": "Live 1985", "album": { "discNumber": 2 } }   (title "Live 1985": displayName is verbatim, no CD suffix)
```

## Cue sheets and chapters (virtual albums)

A single audio file described by a **cue sheet** or by **embedded chapters** ("sections") is shown as an **album with
one track per section**, as a container inside the file's directory. Everything is pure Java; it needs transcoding
(`transcode.enabled`, the default) because a section is played by decoding exactly its time range. With transcoding
off, such files stay ordinary whole files and cue sheets are ignored.

**Sources, in order of precedence** (the first that yields at least two sections wins):

1. a `.cue` file in the same directory whose `FILE` names the audio file. Matching is tolerant: exact name, then the
   same base name with another audio extension (cue says `disc.wav`, file is `disc.flac`), then, for a cue with a
   single `FILE`, the cue's own base name. Several cues may live in one directory; a file is described by the first
   cue (by name) that names it. A `FILE` that matches nothing is logged and ignored.
2. a `CUESHEET` Vorbis comment of a FLAC / OGG file (full cue text; origin `tag-cue`). The native FLAC CUESHEET
   metadata block is not read.
3. embedded **chapters** (origin `chapters`): MP3 ID3v2.3/2.4 `CHAP` frames (title from the `TIT2` subframe; when a
   top-level `CTOC` exists only the chapters it lists), MP4/M4A Nero `chpl` atoms, Vorbis `CHAPTERnnn=hh:mm:ss.mmm`
   with `CHAPTERnnnNAME`. Chapters are ordered by start time; QuickTime text-track chapters are not read.

**Interpretation of "sections"**: embedded chapters and cue tracks are the same thing here: a start position and a
title inside one file; each becomes a virtual track.

**Cue parsing** is tolerant: UTF-8 (BOM skipped), UTF-16 with BOM or Windows-1252; commands in any case; quoted or
unquoted values; `FILE` names with spaces or quotes; several `FILE`s; `INDEX 00` / `INDEX 01` (the start of a track is
`INDEX 01`, `INDEX 00` is read but a track simply ends where the next one starts); `REM GENRE`, `REM DATE`,
`REM DISCNUMBER`, `REM COMPOSER`; `PERFORMER`, `TITLE`, `SONGWRITER` per album and per track. Unknown commands,
non-audio tracks, tracks without `INDEX 01`, invalid times and tracks that do not start after the previous one are
skipped with a warning in the log. A cue with several `FILE`s makes one virtual album per file (each carrying the
cue's album tags). A file with a single cue track starting at 0 is not split: the cue only supplies its tags.

**Virtual tracks**
* Path / id: `<file path>#<n>` (n = 1.. in order), e.g. `main/Cues/disc.flac#2`; the id is the stable hash of that
  path, so favorites, playlists (`songs` entries are such paths) and queues work unchanged. The container's path is
  the file's path (`main/Cues/disc.flac`, `virtual: true`), its id the usual folder id.
* Tags: title (cue title, chapter title, else `Track NN`), artist (track performer, else album performer, else the
  file's artist), album (cue title, else the file's album/title, else the file name), album artist, genre, year,
  composer, track number (the cue's number), disc number; other fields come from the file's tags.
* Duration, `startMillis` / `endMillis` and the sample range are exact (cue frames are 1/75 s). The last section ends
  at the end of the file. For MP3 the file length comes from the header estimate, so the last section of an MP3 can be
  off by a few milliseconds (padded with silence or cut; the advertised length stays exact).
* **Streaming**: always the decoded range, as MP3 (or LPCM when the client profile asks for it), with the exact
  `Content-Length`, `Range` / `HEAD` support and the same transcode cache rules as other transcodes (cache key includes
  the range). The native file is never served by byte range, because byte offsets inside compressed audio are not
  frame exact; a request for `/native` is answered with the MP3 form. DLNA offers one `res` (the transcode).
* The **whole file is hidden** by default (it would duplicate the album). Set `"keepWholeFiles": true` in the
  directory's `settings.json` to list the whole file next to the album.
* The album sorts by track number inside; it inherits the directory's `album` overlay and `front.jpg`; the picture is
  the file's embedded art or the directory's `front.jpg`. Tags of virtual tracks cannot be edited (write the cue).
* **list.json**: an entry for the file name (`disc.flac`) overrides the album: `partOf` = album title,
  `author` = album artist. An entry for `disc.flac#2` - or `{"filename": "disc.flac", "index": 2}`, or the section starting at
  `"start": "03:27.500"` ([sections](#listjson-reference)) - overrides that track (`title`, `author`, `partOf`,
  `transcriber`) like it does for normal files. list.json wins over the cue / chapters, which win over the file's tags.
  A per-file sidecar `disc.flac.json` ([format](#per-file-sections-list-filejson)) takes precedence over list.json.
* **Rescans**: editing or adding/removing the `.cue`, the audio file, `list.json` or `settings.json` makes the next
  rescan rebuild the album; an unchanged album is taken over without reading the file. Rescanning the album's path
  rescans its directory.
* Web player and admin UI show these albums as folders with a small `cue` badge; track info shows the source file and
  the range in it.

## Virtual containers

Per directory, over its whole subtree, enabled by `random`, `recent`, `views`. Full structure, ids and ordering in
[dlna.md](dlna.md#content-hierarchy). Summary:

| Title | Setting | Content |
|---|---|---|
| `[Random N]` | `random` | N random tracks; per client (IP + User-Agent) snapshot, stable while browsing and playing, expires after `random.ttlMinutes` (default 30) idle without active stream |
| `[Recently added]` | `recent` | N tracks with the newest first-seen time |
| `[Albums]` `[Artists]` `[Genres]` `[Titles]` `[Composers]` | `views` | Browse by album / artist -> albums / genre -> albums / title / composer -> albums. Artists and Titles are split into `A`-`Z`/`#` buckets above `views.bucketThreshold` (500). Missing values go to "Unknown Album/Artist/Genre/Composer" |

Whole-library variants: put the setting on the root directory. The admin page lists live Random N snapshots.

## Playlists

* One JSON file per playlist in `library.playlistsDir`: `<key>.json`, key = file name without `.json`.

```json
{ "name": "Road trip", "songs": ["Music/Rock/Album/01 Song.flac", "Music/Pop/x.mp3"] }
```

* Songs are root-qualified paths (`Root/relative/path`, `/` separators). `name` falls back to the key. Files are read
  from the flat directory (no recursion, hidden files and non-`.json` ignored), sorted by name (natural order); a file
  that is not a JSON object, or whose `songs` is not an array of non-empty strings, is skipped with a warning.
* An entry is `ok`, `missing` (not in the catalog) or `unsupported` (listed as unsupported). Broken entries are skipped
  by DLNA but keep their place so the UI can **fix** them (point to another file). The Playlists page shows the broken
  count.
* DLNA: under the `Playlists` container as `playlistContainer`, items listed in play order; changes (UI/REST or
  rescan) bump the playlist's ContainerUpdateID and are evented.
* Edit through the web UI (create, rename, delete, add via search, reorder, remove, fix) or the REST API
  ([rest-api.md](rest-api.md#playlists)); hand edits need a rescan. Writes are atomic.
* Limits: name <= 200 characters; 20 000 songs.

## Search

* **DLNA Search**: UPnP criteria grammar, scoped to `0`, `folders` or a directory; see
  [dlna.md](dlna.md#search). Searches real tracks and directories only (virtual containers excluded).
* **Web UI search** (`GET /api/v1/admin/search`): free text, all words must match some field; optionally restricted to tracks
  or directories and to a subtree; max 300 hits. Used by the playlist editor ("Add songs", "Fix").
* Matching is case-, accent- and whitespace-insensitive and applies the album overlay.

## Artwork

* Track image: `front.jpg` in its directory, else the track's embedded picture (the first picture jaudiotagger finds:
  ID3 APIC, FLAC/Vorbis picture, MP4 `covr`; not for Opus/ADTS/MIDI). Directory image: `front.jpg`, else the embedded picture of the first track that has one (direct tracks, then
  subdirectories, depth first).
* `rear.jpg` is for the web UI only (never in DIDL).
* DLNA exposes only 160 px JPEG thumbnails (`/art/.../tn`), generated on demand and cached in `<dataDir>/thumbs`
  (cache key covers path, mtime and size of the source, so a changed picture regenerates).
* Upload through the Directory settings page (or `PUT /api/v1/admin/folders/art`): any decodable image, max 10 MiB, stored as
  JPEG (`front.jpg` / `rear.jpg`).
* The server has no editor for embedded pictures; use `front.jpg` to override them.

## Formats matrix

| Format | Extensions | Decoder (for transcoding) | Tags read | Tags write | Native `res` (MIME) | Default profile offers |
|---|---|---|---|---|---|---|
| MP3 | `.mp3` | JLayer (direct API) | jaudiotagger | yes (all fields) | `audio/mpeg`, `DLNA.ORG_PN=MP3` | original only |
| OGG Vorbis | `.ogg` `.oga` | JOrbis | jaudiotagger | yes | `audio/ogg` | MP3, then original |
| FLAC | `.flac` | JFLAC (24-bit reduced to 16) | jaudiotagger | yes | `audio/flac` | MP3, then original |
| WAV | `.wav` | Java Sound (PCM) | jaudiotagger (ID3 chunk) | yes (ID3 chunk) | `audio/wav` | MP3, then original |
| AIFF | `.aiff` `.aif` `.aifc` | Java Sound | jaudiotagger | yes | `audio/aiff` | MP3, then original |
| Opus | `.opus` | Concentus + own Ogg demuxer (single stream, channel-mapping family 0) | own reader (jaudiotagger cannot read `.opus`) | **no** (read-only, `409 not_editable`) | `audio/ogg` | MP3, then original |
| AAC-LC | `.m4a` `.mp4` | JAAD 0.9.4 (two patched classes) + own MP4 demuxer | jaudiotagger | yes (incl. composer, cover kept) | `audio/mp4` | MP3, then original |
| ALAC | `.m4a` (codec decides AAC vs ALAC) | own decoder (`AlacFrames`), 16/24 bit mono/stereo | jaudiotagger | yes | `audio/mp4` | MP3, then original |
| Raw AAC (ADTS) | `.aac` | JAAD + own ADTS frame table | none (empty tags, bitrate computed) | **no** | `audio/aac` | MP3, then original |
| MIDI | `.mid` `.midi` `.kar` | rendered, not decoded: Gervill synthesizer + SoundFont | sequence name / karaoke `@T` lines / copyright year | **title, artist, year only** (byte patcher) | none (only rendered forms) | MP3 only |

Notes:

* "Native" in the table is the original file's `res`; profiles can mark any format native (then no transcode `res`),
  or hide it. MP3 is native in the default profile.
* No `DLNA.ORG_PN` except for MP3 (and `LPCM`): the other formats have no registered DLNA profile for these streams.
* Tags: title, artist, album, album artist, genre, composer, year, track no., disc no., disc total (leading digits of
  values like `7/12`, `2024-05-01`). Multi-value artist/album artist/genre/composer tags are kept and used by the views
  (writes set a single value). MIDI maps: title = sequence name of the first track (else first karaoke `@T` line),
  artist = second `@T` line, year = first 4-digit year (1900-2099) in the first copyright notice; album, genre,
  composer, track and disc stay empty (use the directory overlay).
* Exact length: every decoder reports an exact sample count (MP3: a frame-header walk done lazily; AAC/ALAC: edit list,
  else `iTunSMPB`, else `stts`; ADTS: frames x 1024).
* Output normalisation for transcodes: stereo, 16 bit. Mono is duplicated, multichannel FLAC drops extra channels, AAC
  with 3-8 channels is folded down (LFE dropped), 24-bit is reduced to 16 with rounding.

Unsupported or refused (listed with the reason on the Admin page, never exposed through DLNA):

| Case | Reason shown / note |
|---|---|
| `.wma .ape .wv .mpc .m4b .alac .dsf .dff` | "no decoder for .<ext>" |
| HE-AAC (SBR/PS) | JAAD's SBR decoder fails on real streams, so it is refused up front |
| AAC Main/LTP/SSR/ER/USAC, ADTS with several raw blocks | refused |
| MP4 with a video track, fragmented MP4, DRM, MP3 inside MP4 | refused |
| ALAC with more than 2 channels or other than 16/24 bit | refused |
| AAC channel configuration 0 (PCE layout) or more than 8 channels | refused |
| MIDI without rendering | "MIDI rendering unavailable: ..." - transcoding disabled, JVM started without `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`, or no SoundFont |
| Unreadable/corrupt file | "unreadable: ..." |

Tag edit limits: Opus and raw `.aac` are read-only. MIDI accepts title, artist (needs a title first) and year
(1900-2099) only; other fields of an edit are ignored for MIDI files.

## Transcoding, prefetch and cache

* Pipeline: decoder -> PCM -> jump3r (LAME port): **MP3 CBR** (`transcode.bitrateKbps`, default 320), always 48 kHz
  joint stereo, bit reservoir off, no Xing/Info frame, no ID3. Every frame has the same size
  (`144 * kbps * 1000 / 48000` bytes), so `Content-Length` is exact:
  `ceil(samples at 48 kHz / 1152) * frameSize`. Sources below 32 kHz are upsampled before the encoder (LAME would
  otherwise lower the bitrate); the stream is padded/cut to the announced length.
* **LPCM** (per profile): `audio/L16;rate=<source rate>;channels=2`, big-endian, exact length `samples * 4`, no
  resampling.
* **Seeking**: a Range into a cached transcode is a byte range of the cached file; into an uncached one a fresh
  encoder starts at the matching frame. The live stream from offset 0 and the cached file are byte-identical.
* **Cache**: `<dataDir>/transcode`. LRU with a size cap (`transcode.cacheMaxMb`, default 2048) and a time to live counted
  from last use (`cacheTtlHours`, default 336 = 14 days). Swept every hour (first sweep 10 min after start). Entries are
  written to a temp file and renamed (no partial entries); concurrent requests for one entry wait for a single
  producer. The key covers path, mtime, size, variant, pipeline version and (MIDI) the SoundFont; entries of changed or
  vanished files are dropped when a catalog commit shows the change. A cache entry whose size differs from the exact
  length is discarded.
* **Prefetch** (`transcode.prefetch`, default on): when a client starts item N in a context (folder, playlist, Random N,
  view; no wrap-around), item N+1 is encoded into the cache if it needs transcoding and is not cached. It uses the form
  the client is playing (MP3 bitrate or LPCM); a client playing natively gets no prefetch, except that a MIDI file
  after a natively played item is rendered to the default MP3. One job per client and context; cancelled when the
  client starts a different item; runs on a low-priority pool of `transcode.prefetchJobs` (1 or 2) threads. Client
  queues are ignored.
* Disabling (`transcode.enabled=false`): only originals are offered, MIDI becomes unsupported.
* The Admin page shows cache entries/size and running prefetch jobs.
* Details of the encoder, spike results: [spike-findings.md](spike-findings.md), [transcoding.md](transcoding.md),
  [aac-alac-findings.md](aac-alac-findings.md).

## MIDI rendering and SoundFont

* Rendering: the JDK software synthesizer (Gervill, via its internal `AudioSynthesizer` API, hence the JVM flag
  `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`, set in the jar manifest, by `build.gradle` for runs and
  tests, and needed explicitly only for IDE launches), offline, 48 kHz 16-bit stereo, linear interpolation, max
  polyphony 128. One render at a time (a render is held in memory).
* **Tail trimming**: rendering continues past the last MIDI event until the output stays at or below
  `transcode.midiSilenceThreshold` (default 16 of 32767, about -66 dBFS) for 0.5 s, but at most
  `transcode.midiMaxTailSeconds` (default 10) beyond the last event; the result keeps 50 ms after the last audible
  frame. Leading silence is kept.
* **SoundFont lookup**: `transcode.soundFontPath` if it points to a file; else the bundled
  `src/main/resources/soundfont/GeneralUser-GS.sf2` (GeneralUser GS v2.0.3, extracted once to
  `<dataDir>/soundfont/`; the file is not generated or downloaded by the build and is kept out of Git by the author);
  else MIDI is unavailable. A configured path that is not a file logs a warning and falls back to the bundled one.
* **First request**: the whole file is rendered into the cache before the first byte is sent (also for `HEAD`), so a
  long file delays the first byte. The measured length is stored in the catalog (`sampleCount`, `renderedMillis`) and
  announced as a container update, so later browses show exact `size`/`duration`; before the first render `res` has
  no `size`.
* The SoundFont identity (path, size, mtime) is part of the cache key: replacing the SoundFont re-renders.
* **Editing MIDI tags** (title, artist, year): the file bytes are patched directly (never re-serialised): title = the
  sequence-name event of the first track (and the first karaoke `@T` line, if present, is kept consistent); artist =
  the second `@T` line (needs a title first, otherwise the edit is refused); year = the year in the first copyright
  notice (`(c) 1996 Owner` -> `(c) 2003 Owner`; `(c) YYYY` is added when there is none; clearing removes the digits and
  the event only when nothing else is in it). Year 1900-2099. Procedure: temp copy -> patch -> verify (must parse; same
  division, track count and length; every other event byte-identical at the same tick; requested tags read back
  exactly) -> atomic rename. On any failure the copy is deleted and the original stays untouched; the reason is
  reported per file. Refused: broken structure, RIFF-wrapped (RMID) files, files `javax.sound.midi` cannot read. After a
  successful edit the persisted rendered length is reset and its cache entry dropped.

## Web UI walkthrough

Static single-page app (Vue 3 as **native ES modules**, vue-router 4, hash routes, no build step) served from the jar at
**`/app/`** (any address, login required for the data; `/` redirects there) and, for the admin pages, also at `/admin/`
(LAN-only, same files). It talks to the [REST API](rest-api.md) only. Both roles get the same shell; admins additionally see
the phase-1 admin pages. The sidebar has Home, Folders, Albums, Artists, Genres, Songs, Recently added; *My music* (Favorites, Recently played,
Most played, My / Shared / System playlists); and, for admins only, *Administration* (Library admin, Users, System playlists
(edit), Shared playlists, Server); the top bar has the search with typeahead and the user menu (change password,
keyboard shortcuts, sign out); a player bar sits at the bottom; on phones the sidebar becomes a drawer and the bar is compact.

### Player (all users)

| Part | What it does |
|---|---|
| **Home** (`#/`) | Quick actions (**Continue listening**, **Random playlist** = a random non-empty playlist from mine / shared / system, **Play favorites** shuffled), random songs (Play, Another mix), favorites, recently played, recently added |
| **Folders** (`#/folders?p=<path>`) | Roots (a single root is opened directly), breadcrumbs, folder cover, Play / Shuffle / Random from here, sub-folders as tiles, tracks, infinite scroll. Admins get a "Manage" link to the admin library page |
| **Albums / Artists / Genres** (`#/albums`, `#/artists`, `#/genres`) | Cover tiles with infinite scroll; a tile opens the group (`#/albums/<id>`: tracks with disc/track numbers; `#/artists/<id>`: albums plus "All tracks"; genres likewise). Play on a tile plays all tracks |
| **Songs** (`#/songs`) | Every song of the caller's roots, infinite scroll; header buttons sort by title, album, time (a sort other than title ascending first loads the whole list) |
| **Recently added** (`#/recent`) | Newest first with the date added |
| **Search** (`#/search?q=&kind=`) | Grouped results (artists, albums, tracks, folders) with "Show all n" per group; typeahead in the top bar: arrow keys + Enter, a track suggestion plays, others navigate |
| **Track actions** ("..." button or right click) | Play now, Play next, Add to queue, **Add to / Remove from favorites**, **Add to playlist...**, Go to album / artist / folder, Info, Download (admins, or users with the download permission). Albums, artists, genres, folders and playlists offer Play, Play next, Add to queue, Add all to favorites, Add to playlist... (all their tracks) and Open |
| **Heart** | A heart button on every track row, in the player bar, the full-screen player and the queue toggles the favorite; the state comes from a cache loaded at sign-in |
| **Now playing panel** (queue button, `Q`) | The queue with total time; click to jump; per row move to top / up / down / bottom and remove; Clear; **Save as playlist** (name pre-filled "Now playing <date>") |
| **Full-screen player** (cover or button, `F`) | Large cover, seek bar, controls, next tracks, **Info** tab with all metadata |
| **Player bar** | Cover, title/artist, shuffle, previous (restarts after 3 s), play/pause, next, repeat off / all / one, seek bar with times, volume and mute, queue, full-screen. Shuffle, repeat and volume are remembered per browser |

**Audio engine.** Two `<audio>` elements: one plays, the other preloads the next track when it is played natively. At sign-in
the browser tests `canPlayType` for MP3, OGG Vorbis, Opus, FLAC and WAV and posts the list to `POST /api/v1/stream/formats`
(once per login session); library calls carry the same list so `playability` matches. For a next track that needs a
transcode the player sends `POST /api/v1/stream/prefetch` instead of preloading. A track that fails to play is skipped.
**Media Session** gives OS media keys, lock-screen metadata with cover and seek. **Keyboard**: `Space`/`K` play-pause,
`N` next, `P` previous, left/right seek 5 s, `Shift`+up/down volume, `M` mute, `S` shuffle, `R` repeat, `Q` queue,
`F` full-screen, `?` help. No gapless playback.

### Personal pages (all users)

| Page (route) | What it does |
|---|---|
| **My playlists / Shared / System** (`#/me/playlists`, `#/shared`, `#/system`) | Tabs; "My playlists" creates a playlist (name). Rows show the track count, "n unavailable" (entries outside the viewer's allowed roots), who shared it, a *shared* badge; Play, Shuffle and the action menu per row |
| **Playlist** (`#/me/playlists/<id>`, `#/shared/<owner>/<id>`, `#/system/<key>`) | Own: rename, **reorder** (top / up / down / bottom per row), **remove**, **share / stop sharing**, delete (confirmation), Play / Shuffle. Missing files are shown red with **Fix path** (search for a track or type a library path); entries the owner can no longer access (a root was removed from the account) stay visible, greyed, "Not available to you". Header badges: "n unavailable", "n missing". Shared and system playlists are read-only with **Copy to my playlists** (only accessible entries are copied). **Version conflicts**: every edit sends the playlist version; on `409`/`412` the page reloads the playlist, re-applies the same edit and says so |
| **Favorites** (`#/me/favorites`) | The favorites list (infinite scroll), Play / Shuffle, remove per row |
| **Recently played / Most played** (`#/me/history`, `#/me/most-played`) | Newest plays (each track once) with the time; play counts descending |
| **Add to playlist dialog** | Choose an existing playlist or type a name to create one and add in one step; works for a track, an album, artist, genre, folder or playlist |

**History and resume.** A play counts after 30 s or 50 % of the track (the client only reports plays that qualify,
batches them every few seconds and retries after a `429`; the server applies the same rule again). The queue (items, current
index and position, shuffle, repeat) is written to `PUT /api/v1/me/queue` about 4 s after a change, at most every 15 s
while playing, and at once on pause, track change and page hide; a device writes nothing while the stored queue is
still being offered. On another device (or after reopening) a banner and the Home button offer **Continue listening**
(starts the stored queue at the stored position, restoring shuffle and repeat; browsers need that click to start audio). Every
8 s the player checks the stored queue (`If-None-Match`); when **another device** wrote it, the notice **"Playing on another
device: <track>"** appears with **Play here** (take over) and Dismiss, and this device stops its passive position writes until
the user acts (last writer wins). Signing out saves the queue first.

**PWA.** `manifest.webmanifest` and icons make the app installable; the service worker (`sw.js`) only keeps the app shell
(HTML, CSS, JS) for flaky networks and never touches `/api/`, audio or range requests (no offline audio).

**Extension points**: `js/extensions.js` registry (`registerNavItem`, `registerRoute`, `registerTrackAction` (label and icon may
be functions of the subject), `registerQueueAction`, `registerHomeSection` (`top: true` renders above the random mix)). The
personal UI is `js/extensions/personal.js` (+ `pages/personal.js`, `personal/runtime.js`), the admin additions
`js/extensions/admin.js` (+ `pages/admin-users.js`). Player events (`trackstart`, `trackend`, `queuechange`, `state`, `time` on
`player.events`) feed play reporting and queue sync. Strings live in `js/i18n/en.js` (add a locale table and register it
in `js/i18n.js`; `src/test/js/i18n-coverage.test.js` checks that every literal key of the translated pages exists).

**Code layout** (`src/main/resources/web`): `js/core/` pure, DOM-free modules (queue, shuffle, store, pager, format
negotiation, URL building, i18n, keys, prefs, list helpers, and for the personal/admin UI `playlist-ops` (edit operations,
conflict re-apply), `play-report` (30 s / 50 % rule, batching), `queue-sync` (debounce, resume, remote change),
`favorites`, `user-form`, `track`) covered by `node:test` tests in `src/test/js`
(`./gradlew jsTest`; skipped when Node.js is missing, part of `check` when it is found); `js/player/` (audio engine,
controller, Media Session); `js/components/`, `js/pages/`; `js/vendor/` (Vue 3.5.13 and vue-router 4.5.0 ES builds with their
licences; vue-router's `from"vue"` is rewritten to the relative `./vue.esm-browser.prod.js` so no import map, which the CSP
would block, is needed; all modules import Vue through `js/vue.js`).

### Admin pages (admin only, LAN)

Same routes as before: `#/library`, `#/settings`, `#/metadata`, `#/playlists`, `#/playlists/<key>`, `#/admin`, plus
`#/admin/users` and `#/admin/shared-playlists`; they are listed in the sidebar section *Administration* (shown to admins
only); other users are sent to the home page (and the API refuses them anyway).

| Page (route) | What it does |
|---|---|
| **Sign in** (`#/login`) | User name and password. Shows the lockout message on `429` |
| **Change password** (`#/password`) | Current + new + repeat (min 10 characters). Forced (and the only reachable page) while `mustChange` |
| **Library** (`#/library?p=<path>`) | Directory tree on the left, current directory on the right: breadcrumbs, front/rear cover, counts, sort mode, badges (Random N, Recently added N, enabled views, "out of sync"), settings error banner, sub-directories, and a track table (disc.track, title with file name, artist, album, year, format with "read-only"/"limited" badges, length, bitrate, "sync" badge, Edit link). Buttons: **Directory settings**, **Edit tags** (if the directory has direct tracks), **Rescan** (this subtree) with the **Re-read all tags** tick box, **Reload** |
| **Directory settings** (`#/settings?p=`) | Sections *Name* (display name; directory on disk shown read-only), *Order* (sort mode; for `explicit` a reorderable list of children with up/down/top/bottom, "directory" and "not found" badges, remove-missing), *Album* (title, album artist, year, genre, disc number, disc total), *Virtual containers* (Random N, Recently added N, view checkboxes with reordering), Save/Cancel (changes tracked; "Unsaved changes"). *Sync with tags*: **Apply to tags** opens a file list (nothing pre-selected; only formats that can hold album tags are selectable, others listed disabled as "no album tag"/"read-only"; Select all / None); the button "Write the tags of N files" writes the overlay into exactly those files and reports written/failed/skipped. **From tags** is a **preview**: it fills the Album form from the tracks' tags (nothing saved until you press Save); if the tracks disagree a conflict table (field, value, files) is shown and nothing is filled. Both buttons are disabled while there are unsaved changes. *Pictures*: upload `front.jpg` / `rear.jpg` with preview |
| **Metadata editor** (`#/metadata?p=&t=`) | Track list with checkboxes (Opus and raw AAC rows disabled), form with the ten tag fields. One file selected: the form shows its tags and only changed fields are sent (an emptied field clears the tag). Several files selected (batch): each field shows the value **all selected files share** (trimmed, case-sensitive; `0` counts as empty for numbers), or an empty box with the placeholder "(keep)" when they differ. Only fields you touched **and** that differ from what was shown are written to all selected files; deleting a pre-filled value, or ticking "clear", clears the tag in all files; emptying a field that showed nothing means "keep". Selection changes inside a batch keep your typed text. Only files whose format can store a field take part in the common value of that field. **MIDI**: album, genre, composer, track no., disc no. and disc total are disabled when only MIDI files are selected, and silently skipped for MIDI files in a mixed batch (a note explains it); MIDI keeps title, artist, year. Results of a batch list per-file failures |
| **Playlists** (`#/playlists`) | Create (name), table with song count and broken count, delete with confirmation |
| **Playlist** (`#/playlists/<key>`) | Rename; ordered song list with move buttons, remove, "Fix" for broken entries (opens a search picker); Save/Cancel of the song list; **Add songs** with a search picker (tracks only) |
| **Users** (`#/admin/users`) | Table of accounts (role, status: active / invited / must change password / disabled, allowed roots, download, last login). **New user**: user name (2-64 of letters, digits `. _ - @`, validated before sending), display name, role, **allowed roots as check boxes with the main root preselected** (admins always see every root), download permission, and how the user gets access: **invite link** (shown once with Copy and the days left; one-time, 7 days) or a **temporary password** (shown once, must be changed at first sign-in). Per user: Edit (name, role, roots, download), Disable / Enable (ends sessions), **Reset (invite)** / New invite, Temporary password, Delete (confirmation; removes their data). Your own row cannot be disabled, deleted or reset (the API refuses it as well) |
| **Shared playlists** (`#/admin/shared-playlists`) | Moderation: every shared playlist with owner, track count and update time; **Make private** or **Delete**, each with a confirmation. Private playlists are not listed |
| **Admin** (`#/admin`) | Library scan (live status refreshed every 3 s, rescan whole library or a `root/directory`, "Re-read all tags", last-scan report, scan errors), invalid `settings.json` list (links to the settings page), unsupported files (path, size, reason), active streams (client, track, original/transcoded form, since) with cache size and prefetch jobs, Random N snapshots (directory, client, N, tracks, last activity, streams), version (version, commit, build time, Java, start time) |

## Security and password lifecycle

* Web UI and REST API require login; DLNA endpoints (`/upnp`, `/stream`, `/art`) have no login by design and, like the admin
  API and pages, answer LAN addresses only (`network.lanCidrs`); `/health` is open. The player API (`/api/v1/stream`,
  `/library`) is reachable from anywhere with a login. There is no HTTPS: use a reverse proxy and `network.trustedProxies`
  (README: reverse-proxy example).
* Accounts in the user store `<dataDir>/users/users.json` (roles admin/user, allowed roots, invites; the phase-1 `admin` section of `config.json` is migrated once), PBKDF2-HMAC-SHA256 (310 000 iterations, 16-byte salt).
* First start (or an account without hash): random 20-character password, logged once in a marked block, change forced
  at first login (policy: 10-200 characters, different from the current).
* Session cookie `kd_session` (HttpOnly, SameSite=Strict, path `/api/v1`), optional 30-day remember-me cookie (hashed, rotated), CSRF token header on writes,
  `X-Requested-With` on login, Origin check, login throttling per IP (5 failures / 15 min) and per user name (10), idle timeout 8 h, absolute
  24 h, sessions in memory. Details: [rest-api.md](rest-api.md#authentication-and-session-model).
* Lost password: empty file `reset-password` in the config directory (watched at startup and every 30 s) -> new logged
  password, change forced, all sessions ended.
* Static HTML is served with a Content-Security-Policy (`default-src 'self'`, `script-src 'self' 'unsafe-eval'` because
  Vue compiles its templates in the browser, `frame-ancestors 'none'`).
* Writes to the music directory (tags, `settings.json`, art) need write permission for the server user
  (`PUID`/`PGID` in Docker); on a read-only mount the edits fail with `500 io_error`.

## Deployment pointers

See the README: Docker Compose quick start, Portainer stack from a Git repository (including GitOps updates and the
SoundFont-in-Git caveat), host networking (SSDP needs it on Linux), volumes `/music` `/playlists` `/config` `/data`,
`PUID`/`PGID`, environment variables, health check `GET /health`, password reset, local debugging. Configuration:
[configuration.md](configuration.md). Manual end-to-end tests: [integration-tests.md](integration-tests.md). Renderer
tuning: [client-compatibility.md](client-compatibility.md).

## Known limitations

From the code and the other documents:

* **Explicit order**: DLNA clients see directories before files even with `sort: explicit` (jUPnP's DIDL writer groups
  containers first). See [dlna.md](dlna.md#container-ordering-inside-a-directory).
* **Eventing**: commits within a few milliseconds coalesce (earlier `ContainerUpdateIDs` can be lost, the latest is
  always announced); jUPnP drops `SystemUpdateID` events within 200 ms of the previous one. `[Random N]` containers are
  not announced.
* `SortCriteria` of Browse/Search is ignored (`GetSortCapabilities` is empty).
* No filesystem watcher, no gapless playback, no client queues, no authentication or HTTPS on the DLNA side, sessions
  are lost at restart.
* **Client profiles are untested guesses**: the built-ins are provisional, no real renderer has been verified
  ([client-compatibility.md](client-compatibility.md)); `DLNA.ORG_FLAGS` is one constant for all audio `res`;
  Opus and Vorbis share `audio/ogg`.
* **Formats**: HE-AAC, AAC profiles other than LC, DSD, WMA, APE, WavPack and others are unsupported. AAC encoders other
  than ffmpeg's and Microsoft's, and iTunes-made ALAC, are untested. AAC with perceptual noise substitution decodes
  with a non-reproducible noise (a seeked transcode differs from a continuous one in those bands, not in length).
  Real renderers have not been tried with `audio/mp4` native `res`. Opus: very large files, chained streams and embedded
  pictures not verified.
* **MIDI**: first byte delayed until the file is rendered (also `HEAD`); `size` unknown until the first render; sound
  quality only checked with GeneralUser GS 2.0.3; needs the JVM flag and a SoundFont; one render at a time.
* **Tags**: Opus and raw `.aac` cannot be edited; MIDI only title/artist/year; writes set single values (multi-value
  tags are collapsed on the edited fields); tag edits need write access.
* **Cue sheets / chapters**: cue sheets for formats we cannot decode (APE, WavPack, WMA, ...) are ignored (see
  [todo-ffmpeg.md](todo-ffmpeg.md)); native FLAC CUESHEET blocks and QuickTime chapter tracks are not read; virtual
  tracks are never served as a byte range of the original file.
* **Settings**: no inheritance; `sort: mtime` of directories depends on directory mtimes.
* Random N identity is IP + User-Agent: two apps with the same UA on one device share a snapshot; a client that changes
  UA between Browse and stream loses the context for prefetch.
* REST search returns at most 300 hits; admin "active streams" reports an explicit `/native` URL as transcoded.
* A new playlist created through the API appears at the end of the DLNA list until the next scan sorts the playlists
  by name.
