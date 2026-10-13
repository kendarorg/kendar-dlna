# Client compatibility checklist (milestone 10)

Nothing here has been verified with real renderers yet: this is the test plan. Fill in the result column as you go and
turn each finding into a profile change (`clients` in `config.json`, or `/config/clients.json`). The profiles that ship
in `src/main/resources/clients/builtin-profiles.json` are **unverified**: the `ums-*` ones were generated from the
renderer files of the open source Universal Media Server (audio `Supported` formats, `TranscodeAudio`,
`TranscodeAudioTo441kHz`, User-Agent / header match), the older `vlc`, `bubbleupnp`, ... ones are guesses.

Run the server on a Linux host with host networking (see README), a small library (every format; one MIDI file; one
album with `front.jpg`; one folder with `random`, `views` set; one playlist; one file with non-ASCII name) and
a transcode cache you can empty (`rm -r /data/transcode/*`) between runs.

## What the server sends (so you know what to look at)

| Item | Value |
|---|---|
| Native `res` protocolInfo | `http-get:*:<mime>:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000` (MP3 adds `DLNA.ORG_PN=MP3;`) |
| Transcoded MP3 `res` | `audio/mpeg`, `DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=0170...`, CBR 320 kbps, 48 kHz, exact `size`/`duration` |
| LPCM `res` (profile only) | `audio/L16;rate=<source rate>;channels=2`, `DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=1` |
| Thumbnails | `DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=00;DLNA.ORG_FLAGS=00D00000000000000000000000000000` |
| `res` order (default) | MP3: original; other formats: MP3 first, original second; MIDI: MP3 only |
| Stream headers | `Accept-Ranges: bytes`, `transferMode.dlna.org: Streaming`, `contentFeatures.dlna.org`, exact `Content-Length`, 206 for Range, HEAD supported |

## Per client

For every client record: model/version, User-Agent, matching profile id (the built-in that applied, or default), and
the result of each check.

Common checks (all clients):

1. Discovery: server appears within ~10 s of start; disappears after `docker stop` (ssdp:byebye).
2. Browse: Root -> Folders -> root -> album; titles with accents/non-ASCII; paging of a folder with > 100 items;
   child ordering follows `settings.json` (`sort`, `explicit`); Random N / Albums / Artists / Genres / Titles appear
   in the configured order before regular children.
3. Playback of each format: MP3, OGG, FLAC, WAV, AIFF, Opus, MIDI. Note which `res` the client picked.
4. Seek (drag the slider) in a native stream and in a transcoded stream (uncached and cached: replay the track).
5. Duration and progress bar correct for a transcoded track (exact `size` and `duration` must agree).
6. Next/previous track and auto-advance; prefetch effect (second track starts without delay; check the admin page
   for the transcode job and `/data/transcode`).
7. Album art in lists and now-playing (`front.jpg`, embedded art).
8. Search (if the client has it): by title, artist, album, folder name.
9. Playlists container: order, playing the whole playlist.
10. Random N: new snapshot after 30 min idle; tracks keep playing from the old snapshot.
11. Rescan while playing: `SystemUpdateID` change must not make the client lose its place or reload forever.

| Client | Specific points |
|---|---|
| **VLC** (Media Explorer / UPnP discovery, Windows/Linux/Android) | Expected to play every native format; pick the profile `vlc`. Check that with `nativeFormats` all formats, FLAC/OGG/Opus play natively without the MP3 `res`. Check Opus and AIFF, and AAC, ALAC (`audio/mp4` without a profile name) and raw `.aac`. Check seeking in the MP3 transcode. |
| **BubbleUPnP** (Android, with a renderer such as a Chromecast/another DLNA player) | Often does its own transcoding; verify it does not request `audio/L16`. Check gapless/next-track pre-fetch behaviour and the "Local renderer" path as well as a real renderer. Browse caching: SystemUpdateID / ContainerUpdateIDs after a rescan. |
| **Kodi** (UPnP browser / Estuary) | Uses `res` with `protocolInfo` to choose a source; check which `res` it picks when two are offered; MP3 with exact duration (Kodi reads length from the stream if missing). Album art via `upnp:albumArtURI`; thumbnails with `JPEG_TN`. |
| **Windows Media Player** / Windows "Cast to device" / Groove | Picky about `DLNA.ORG_PN` and `contentFeatures`; native list assumed MP3 + WAV, test FLAC (Windows 10+ plays FLAC natively but may not advertise it). Check `audio/wav` vs `audio/x-wav`, `audio/aiff` vs `audio/x-aiff`. Check that the original `res` is not preferred over the transcoded one when it fails. |
| **foobar2000** (foo_upnp) | Plays everything it has decoders for; check streaming of OGG/FLAC/Opus over HTTP with Range; large `res` lists. |
| **Samsung TV** | One `res` only is assumed (`SINGLE_RES` quirk): confirm whether two `res` break browsing or playback. DLNA.ORG_FLAGS sensitivity; Samsung-specific `sec:` namespace/headers (`getCaptionInfo.sec`, `getMediaInfo.sec`) in the requests; whether the TV needs `DLNA.ORG_PN=MP3` on the first `res`; check that OGG/FLAC are really native (guess: no). |
| **LG TV** (webOS/NetCast) | Same questions as Samsung; FLAC assumed native; Opus and OGG not. Check sorting: LG sometimes re-sorts by title. `SINGLE_RES` assumed. |

## Known risk areas (from the earlier milestones' reports)

1. **`DLNA.ORG_FLAGS` value** `01700000000000000000000000000000` is used for every audio `res` (streaming transfer
   mode, background/interactive transfer, byte seek via `OP=01`). TVs and WMP can be strict: variants to try per
   profile are `01500000000000000000000000000000` (no interactive mode), `0D700000...` and `ED100000...` (Time-seek
   bits). Today the value is a single constant in `MediaTypes`, not per profile: a failing renderer means adding a
   per-profile override.
2. **`DLNA.ORG_PN` only on MP3**: OGG/FLAC/WAV/AIFF/Opus have no registered DLNA profile and are sent without PN;
   some TVs refuse items without PN. Also `LPCM` as PN for `audio/L16`; some devices want `LPCM` with
   `rate=44100;channels=2` exactly.
3. **Multi-`res` ordering**: transcoded MP3 first, original second. Renderers that use only the first `res` get the
   MP3 (good); those that scan all and pick the first they can play are fine; those that choke on a second `res` need
   `SINGLE_RES`. Confirm each client's choice and record it; consider `NATIVE_FIRST` for capable clients (FLAC to a
   hi-fi renderer).
4. **MIDI**: always rendered to MP3 before the first byte (a long render delays the first byte and may time out a
   renderer; measure the time-to-first-byte of a 5-minute `.mid`; prefetch helps only after the first track). `size`
   is absent until the first render (some clients need `size` for seeking). Check the SoundFont choice (v2.0.3 loads;
   the SoftSynth edition was never compared) and listen for wrong instruments/volume.
5. **Seeking in transcodes**: a Range into an uncached transcode starts a fresh encoder (0-1 sample shift); verify
   audibly that there is no click/glitch and that the client's displayed position matches. Check a cached transcode
   too. MP3 in the transcode has no Xing/Info header: some players (VLC, Kodi) then estimate the duration from
   bitrate; compare with DIDL `duration`.
6. **Profile table in the clients config**: matching is by User-Agent regex, headers, IP ranges; built-ins are
   provisional and checked in this order: inline profiles, `profilesFile`, built-ins. Samsung/LG regexes
   (`Samsung`, `LGE|NetCast|WebOS|\bLG\b`) are guesses; real TV UAs often are generic ("DLNADOC/1.50 ..." or
   "Linux/3.x UPnP/1.0 ...") so use header predicates (`X-AV-Client-Info`, `User-Agent`, `FriendlyName.DLNA.ORG`,
   `X-Samsung-*`) or `ipCidrs`.
7. **Client identity for Random N** is IP + User-Agent: two apps on one phone with the same UA share a snapshot;
   a client that changes UA between Browse and stream requests loses the context (prefetch then does nothing).
8. **Update IDs**: after a rescan or an admin edit, clients that cache by `SystemUpdateID` must refresh; verify there
   is no refresh storm (container update IDs only for changed containers).
9. **Opus/OGG over `audio/ogg`**: both use the same MIME type; clients that choose by MIME may mishandle Opus.
10. **Non-ASCII names**: container locale is `C.UTF-8`; check accents, CJK and emoji in file names, titles and URLs.
11. **Bridge address**: stream URLs carry the advertised IP; if a renderer reports "cannot play" while browsing
    works, compare the URL host with the renderer's reachable address.

## How to capture logs and headers for tuning

The server does not log every HTTP request (only jUPnP's servlet INFO lines in `docker logs`, plus errors). To see
what a renderer sends:

1. **Packet capture on the host** (best): `sudo tcpdump -i <lan-if> -s0 -w client.pcap host <renderer-ip> or port 1900`
   then open in Wireshark with filter `http || ssdp`. Look for the SSDP `NOTIFY`/`M-SEARCH`, the device description
   fetch, `Browse` SOAP calls (headers `User-Agent`, `X-AV-Client-Info`, `FriendlyName.DLNA.ORG`, `SOAPACTION`) and the
   `GET`/`HEAD` of `/stream/...` (note `Range`, `getcontentFeatures.dlna.org`, `transferMode.dlna.org`, `User-Agent`).
   Samsung/LG add `getCaptionInfo.sec: 1` or `X-AV-Client-Info` which make good profile matchers.
2. **Reproduce a client by hand**:
   `curl -A "<User-Agent>" -H 'getcontentFeatures.dlna.org: 1' -H 'Range: bytes=960-1919' -D - -o /dev/null <stream-url>`
   (to send a `Browse` SOAP call yourself, the control URL is
   `/upnp/dev/<uuid>/svc/upnp-org/ContentDirectory/action`, the uuid is in `/data/device-uuid`). Send the same
   Browse with different `-A` values to see which `res` a profile yields.
3. **Server side**: `docker logs -f kendar-dlna` (SSDP/UPnP messages at INFO). For more detail mount a
   `logging.properties` and add `-Djava.util.logging.config.file=/config/logging.properties` in `JAVA_OPTS`
   (set `org.jupnp.level=FINE`, `org.kendar.dlna.level=FINE`; slf4j goes through java.util.logging). The admin page
   lists active streams, transcodes and Random N snapshots, useful to see what a client is actually pulling.
4. **Renderer side**: BubbleUPnP and Kodi have their own logs (BubbleUPnP: Settings -> About -> Logs; Kodi:
   `kodi.log` with debug logging on and "Component-specific logging: UPnP"); VLC: Tools -> Messages, verbosity 2.

## Turning a finding into a profile

```json
"clients": {
  "profiles": [
    { "id": "lg-oled", "description": "LG OLED, measured 2026-xx-xx",
      "match": { "userAgentRegex": "WebOS", "headers": [ { "name": "X-AV-Client-Info", "valueRegex": "LG" } ] },
      "nativeFormats": ["MP3", "FLAC", "WAV"], "resOrder": "TRANSCODED_FIRST",
      "transcodeTarget": "MP3", "bitrateKbps": 320, "quirks": ["SINGLE_RES"] }
  ]
}
```

Restart the container (profiles are read at startup), browse again, and confirm the `res` list; then update the
built-in table in `src/main/resources/clients/builtin-profiles.json` once a profile is proven and remove its
"provisional" description.

## Result log (fill in)

| Date | Client / version | Profile | Formats OK | Seek OK | Art OK | Problems and fix |
|---|---|---|---|---|---|---|
| | VLC | | | | | |
| | BubbleUPnP | | | | | |
| | Kodi | | | | | |
| | Windows Media Player | | | | | |
| | foobar2000 | | | | | |
| | Samsung TV | | | | | |
| | LG TV | | | | | |

## Web player: browser compatibility

The web player (`/app/`) is a pure REST client with no build step; it needs a current evergreen browser. The notes below
come from the code and the platforms' documented behaviour; only **desktop Chrome (headless) was exercised against a real
server** (sign-in, browsing, playback, queue sync, resume, playlists, admin pages, phone-size layout). Firefox, Safari and
real phones are still to be tested: use the checklist and fill in the result log.

### What the app relies on

| Feature | Used for | Minimum / fallback |
|---|---|---|
| ES modules, `fetch`, `IntersectionObserver` | the whole app, infinite scroll | Chrome/Edge 80+, Firefox 75+, Safari 13.1+ (no `IntersectionObserver`: a "Load more" button is shown) |
| CSS `:has()` | track and entry rows with an extra column | Chrome/Edge 105+, Firefox 121+, Safari 15.4+. Older browsers keep working but the "date added" column of Recently added is misaligned |
| `HTMLAudioElement.canPlayType` | format negotiation at sign-in | everywhere; the server transcodes what the browser cannot play to MP3 |
| Media Session API | lock-screen / media-key controls | Chrome, Edge, Safari 15+, Firefox 82+. Without it playback works but has no OS controls |
| `fetch(..., {keepalive: true})` | queue and play reports when the page is hidden | Chrome 66+, Firefox 133+, Safari 13+. The body limit of 64 KB means a queue of more than ~500 tracks may not be saved at page hide (it is saved at the next pause / track change anyway) |
| Clipboard API | "Copy" of the invite link (admin) | needs a secure context (HTTPS or `localhost`); otherwise the text is selected and the admin presses Ctrl+C |
| Service worker, install prompt | PWA (app shell only) | needs HTTPS (or `localhost`); on plain `http://<lan-ip>:8200` the app runs without the service worker and is not installable |
| `localStorage` / `sessionStorage` | volume, shuffle, repeat, device id, format negotiation | every access is guarded; private mode only loses the preferences |

### Browsers and platforms

| Platform | Expected behaviour | Watch for |
|---|---|---|
| **Chrome / Edge (desktop)** | MP3, OGG Vorbis, Opus, FLAC and WAV play natively; everything else (AAC/ALAC/AIFF, MIDI) is the MP3 transcode | Autoplay: the first play must follow a click (the "Continue listening" button is one) |
| **Firefox (desktop)** | MP3, OGG, Opus, FLAC, WAV native | Media Session metadata and artwork appear in the OS media panel from Firefox 82; the `keepalive` limit above |
| **Safari (macOS)** | MP3, WAV, FLAC and AAC/ALAC are native; OGG Vorbis and Opus depend on the version, which is why the player asks `canPlayType` once per session and the server transcodes the rest | Test OGG/Opus on the target version; Range requests on transcodes (exact `Content-Length`) are required for seeking and are supported |
| **Safari (iOS / iPadOS)** | Same engine as Safari. Playback continues with the screen locked while the audio element plays and the Media Session is set | iOS plays one audio element at a time: preloading the next track may be ignored (no gapless, a short gap between tracks); when the screen is locked the move to the next track must start from the `ended` event, so verify an album plays through with the phone locked. Autoplay needs a tap; there is no `Secure` cookie issue as long as the proxy sends `X-Forwarded-Proto: https`. Add to Home Screen needs HTTPS |
| **Chrome (Android)** | As desktop Chrome; media notification with cover and controls; installable PWA (HTTPS) | Battery savers can suspend the tab: playback in an installed PWA is the most reliable. Data saver may delay artwork |
| **Other WebKit / Chromium browsers** | Follow their engine | Not tested |

### Browser test checklist

For each browser/device record the version and tick the checks:

1. Sign in with a remembered session; close and reopen: still signed in.
2. Play an MP3, a FLAC and a MIDI (transcoded) track; seek inside each; the next track starts by itself.
3. Pause, close the tab, open the player on **another** device: "Continue listening" offers the same track and position.
4. Lock the screen / switch apps while playing an album: it keeps playing and moves on (iOS and Android especially).
5. Media keys or lock-screen controls: play/pause, next, previous, seek.
6. Phone layout (about 400 px): sidebar drawer, compact player bar, playlist rows with their buttons, Users page table scrolls.
7. Add a track to a playlist, reorder it, share it, open it from a second account; favorite a track; the heart state follows.
8. Admin (LAN): create a user, copy the invite link, accept it in a private window.

| Date | Browser / OS / version | Formats native | Seek OK | Lock-screen / background OK | PWA install | Problems and fix |
|---|---|---|---|---|---|---|
| | Chrome (Windows/Linux/macOS) | | | | | |
| | Firefox | | | | | |
| | Safari (macOS) | | | | | |
| | Safari (iOS) | | | | | |
| | Chrome (Android) | | | | | |
