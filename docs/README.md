# Documentation index

Reference documentation of Kendar DLNA. The reference pages (marked **ref**) are derived from the source code; where
`plan.md` and the code disagree, the code is right. The project overview, Docker/Portainer deployment and the
`settings.json` / `config.json` quick references are in the top-level [README](../README.md).

| Document | Kind | Contents |
|---|---|---|
| [getting-started.md](getting-started.md) | howto | Illustrated first-run tour with screenshots: sign in, browse, play, queue, playlists, admin pages |
| [features.md](features.md) | ref | Functional overview by area: library and scanning, `settings.json` field reference, virtual containers, playlists, search, artwork, formats matrix, transcoding/prefetch/cache, MIDI and SoundFont, web UI walkthrough, security, known limitations |
| [configuration.md](configuration.md) | ref | Complete `config.json` reference (keys, defaults, validation), `clients` profiles, environment/system-property overrides, relative-path rules, logging, config and data directory layout |
| [rest-api.md](rest-api.md) | ref | Admin REST API under `/api`: session/CSRF/throttling model, error model, every endpoint with schemas and status codes, open endpoints (`/health`, `/stream`, `/art`), curl walkthrough |
| [dlna.md](dlna.md) | ref | UPnP/DLNA side: device and services, ContentDirectory actions, object ids, content hierarchy and ordering, DIDL mapping, `res` and protocolInfo, search grammar, update ids and eventing, client profiles, stream and art URLs |
| [transcoding.md](transcoding.md) | detail | Milestone-8 notes: MP3/LPCM output rules, AAC/ALAC/ADTS handling, cache and prefetch, profile examples, SoundFont lookup |
| [spike-findings.md](spike-findings.md) | findings | Milestone-7 spike results and verdicts: jump3r MP3 encoder, Gervill MIDI rendering, Opus, decoders |
| [aac-alac-findings.md](aac-alac-findings.md) | findings | AAC-LC / ALAC / ADTS decoder spike: JAAD patches, accuracy against ffmpeg, gapless length, seeking, tags, open items |
| [client-compatibility.md](client-compatibility.md) | checklist | Test plan for real renderers (VLC, BubbleUPnP, Kodi, WMP, foobar2000, Samsung, LG), known risk areas, how to capture and tune profiles; **web player browser compatibility** (Chrome, Firefox, Safari/iOS, Android) with a test checklist. Result columns are still empty |
| [integration-tests.md](integration-tests.md) | howto | Manual end-to-end tests (`./gradlew integrationTest`): modes, test classes, known skips |
| [../plan.md](../plan.md) | design | Original design and decisions. Partly outdated (e.g. container titles without brackets, later features such as Recently added, Composers, force re-read, MIDI tag editing) |
| [../plans/](../plans) | design | Per-context design notes (catalog, ingestion, delivery, dlna, ...) |
| [../config/config.example.json](../config/config.example.json) | example | A complete `config.json` |

## Which doc for what

| I want to ... | Read |
|---|---|
| Install and run it (Docker, Portainer, host networking, volumes, PUID/PGID) | [README](../README.md) |
| Change a setting in `config.json` (port, roots, cron, bitrate, cache, SoundFont) | [configuration.md](configuration.md) |
| Override a setting with an environment variable or `-Dkendar.*` | [configuration.md](configuration.md#overrides-system-properties-and-environment) |
| Raise log levels or quiet the logs | [configuration.md](configuration.md#logging) |
| Know what is stored where (`catalog.json`, thumbs, transcode cache, device UUID) | [configuration.md](configuration.md#directory-layout) |
| Configure one directory (sort, order, display name, album overlay, Random N, Recently added, views) | [features.md](features.md#settingsjson-reference) |
| Split a file into tracks with a cue sheet or embedded chapters | [features.md](features.md#cue-sheets-and-chapters-virtual-albums), [todo-ffmpeg.md](todo-ffmpeg.md) |
| Understand which formats work, what is transcoded, what is unsupported and why | [features.md](features.md#formats-matrix) |
| Understand rescans, force re-read, the persisted index, "Recently added" times | [features.md](features.md#library-and-scanning) |
| Use the web UI (Apply to tags, From tags, batch tag editing, MIDI limits, playlists) | [features.md](features.md#web-ui-walkthrough) |
| Edit MIDI tags or set up the SoundFont | [features.md](features.md#midi-rendering-and-soundfont) |
| Script the admin functions (login, tags, settings, rescan, playlists) | [rest-api.md](rest-api.md) |
| Understand login, CSRF, throttling, forced password change, password reset | [rest-api.md](rest-api.md#authentication-and-session-model) |
| Know what a renderer sees (ids, titles, containers, order, DIDL, `res`) | [dlna.md](dlna.md) |
| Debug a stream URL (variants, Range, HEAD, status codes, art URLs) | [dlna.md](dlna.md#stream-urls) |
| Write a DLNA search query | [dlna.md](dlna.md#search) |
| Tune behaviour for a particular renderer (profiles, quirks) | [configuration.md](configuration.md#clients-section), [dlna.md](dlna.md#client-profiles), [client-compatibility.md](client-compatibility.md) |
| Understand MP3/LPCM transcoding details, exact lengths, AAC/ALAC | [transcoding.md](transcoding.md), [aac-alac-findings.md](aac-alac-findings.md) |
| Run or extend the end-to-end tests | [integration-tests.md](integration-tests.md) |
| See what is not supported or not verified | [features.md](features.md#known-limitations) |
