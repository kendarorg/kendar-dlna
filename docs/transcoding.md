# Transcoding, clients and prefetch (milestone 8)

Reference for the pieces added in milestone 8; the design is in `plan.md` section 11, the spike results in
`docs/spike-findings.md`.

## What is served

| Source | Default client (profile `default`) | Notes |
|---|---|---|
| MP3 | original only | transcoded only when a profile sets `AVOID_NATIVE_MP3` |
| OGG, FLAC, WAV, AIFF, Opus, AAC, ALAC | MP3 first, original second | exact `size` and `duration` on the MP3 `res` |
| raw ADTS `.aac` | MP3 first, original second | like the above; no tags |
| MIDI (`.mid`, `.midi`, `.kar`) | MP3 only | rendered with a SoundFont; size appears after the first render |

Stream URLs: `/stream/{containerId}/{itemId}[/{variant}]` with variant `native` (or none), `mp3`
(configured bitrate), `mp3-<kbps>` or `lpcm`. The URL alone decides the output.

* MP3 output is CBR (default 320 kbps), 48 kHz stereo, bit reservoir off, no Xing/Info frame, no ID3. Content-Length is
  `ceil(samples at 48 kHz / 1152) * frame size` (960 bytes at 320 kbps), the stream is padded or cut to it. Sources
  below 32 kHz are upsampled first (LAME would otherwise lower the bitrate), mono is duplicated, 24-bit FLAC/WAV is
  reduced to 16 bit.
* A Range into an uncached transcode starts a fresh encoder at the matching frame; a cached transcode serves exact
  byte ranges of the file. The live stream from offset 0 and the cached file are byte-identical.
* LPCM (`audio/L16;rate=<source rate>;channels=2`, big-endian) has an exact length and byte-exact seeking. It keeps the
  source sample rate (no resampling), always stereo.
* MIDI is rendered completely (trailing silence trimmed) into the cache before the first byte is sent, also for a
  HEAD request. The measured length is stored in the catalog index (`sampleCount`, `renderedMillis`) and bumps the
  ContainerUpdateIDs, so later browses show the exact size.

## AAC, ALAC and ADTS

`.m4a` / `.mp4` files are classified by the codec in the `stsd` box (jaudiotagger reports `Aac` or `Alac`; the decoder probe
refuses anything else), `.aac` is raw ADTS. Decoders: JAAD 0.9.4 for AAC-LC (two patched classes: TNS and the
inverse-quantisation table, see `docs/aac-alac-findings.md`), our own `AlacFrames` for Apple Lossless, our own
`Mp4Audio` demuxer for both. The rules of the other decoders apply:

* Exact length: the edit list (`elst`), else the iTunes `iTunSMPB` comment, else the whole stream (`stts`); raw ADTS
  is `frames * 1024`. The MP3 and LPCM sizes derive from that count, so `Content-Length` equals the delivered bytes.
* Seek: AAC restarts the decoder 2048 samples early (bit-identical to a continuous decode), ALAC decodes the
  frame that holds the target.
* Output is stereo s16: mono is duplicated, AAC with 3 to 8 channels is folded down (LFE dropped), 24-bit ALAC is
  reduced to 16 bit with rounding, sample rates below 32 kHz are upsampled before the MP3 encoder.
* Refused with a reason in the unsupported list: HE-AAC (SBR/PS), AAC Main/LTP/SSR/ER, MP4 with a video track,
  fragmented MP4, DRM, MP3 inside MP4, ALAC with more than 2 channels or other than 16/24 bit.
* Native `res`: `audio/mp4` (AAC, ALAC) and `audio/aac` (ADTS), no `DLNA.ORG_PN`. Built-in profiles list `AAC` (and
  `ALAC`, `ADTS` for VLC, Kodi, foobar2000) as native.
* PNS (perceptual noise substitution) uses a JVM-wide random generator in JAAD, so noise-coded bands differ between
  two decodes of the same file; lengths are unaffected.

## Cache and prefetch

* Directory `<dataDir>/transcode`, LRU with `cacheMaxMb`, entries unused for `cacheTtlHours` are dropped (swept hourly).
  Entries are keyed by file path, mtime, size, variant and (MIDI) the SoundFont, and removed when a catalog change
  shows the source file changed or vanished.
  Editing a MIDI file's title, artist or year (see README, "Editing MIDI tags") rewrites the file, which changes its
  mtime and size: the catalog commit resets its persisted rendered length and drops its cache entry; the next request
  renders it again and records the new length.
* Prefetch (default on): when a client starts item N of a context, item N+1 of the same context (folder, playlist,
  Random N or view order; no wrap-around) is encoded into the cache if it needs transcoding, using the form of the
  stream the client is playing. One job per client and context; it is cancelled when the client plays a different item.
  A MIDI file after a natively played item is rendered to the default MP3.

## Configuration (`config.json`)

```json
{
  "transcode": {
    "enabled": true,
    "bitrateKbps": 320,
    "prefetch": true,
    "prefetchJobs": 1,
    "cacheMaxMb": 2048,
    "cacheTtlHours": 336,
    "soundFontPath": "/data/GeneralUser-GS.sf2",
    "midiSilenceThreshold": 16,
    "midiMaxTailSeconds": 10
  },
  "clients": {
    "builtins": true,
    "profilesFile": "clients.json",
    "profiles": [
      { "id": "my-tv", "match": { "userAgentRegex": "MyTV", "ipCidrs": ["192.168.1.40"] },
        "nativeFormats": ["MP3", "WAV"], "resOrder": "TRANSCODED_FIRST", "transcodeTarget": "MP3",
        "bitrateKbps": 256, "quirks": ["SINGLE_RES"] }
    ],
    "defaultProfile": { "nativeFormats": ["MP3"] }
  }
}
```

Profiles are checked in order: inline, then `profilesFile` (`{"profiles":[...]}`), then the provisional built-ins
(VLC, BubbleUPnP, Kodi, foobar2000, WMP, Samsung TV, LG TV); the first match wins, otherwise the default profile.
`match` may combine `userAgentRegex` (substring, case-insensitive), header predicates
(`{"name":"X-Foo","valueRegex":"bar","negate":false}`) and `ipCidrs`; all given parts must hold. Quirks:
`AVOID_NATIVE_MP3`, `HIDE_UNPLAYABLE_ORIGINAL`, `SINGLE_RES`. `transcodeTarget` is `MP3` or `LPCM`; `bitrateKbps` 0 =
the global bitrate. Invalid values stop the server at startup with a message listing every problem.

## SoundFont for MIDI

MIDI needs transcoding enabled, the JVM flag `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` (set for
`java -jar` through the jar manifest, for Gradle runs and tests through `build.gradle`) and a SoundFont:

1. `transcode.soundFontPath` (any `.sf2`, for example the GeneralUser GS file in the data volume), else
2. the bundled `src/main/resources/soundfont/GeneralUser-GS.sf2` (about 32 MB, extracted once to
   `<dataDir>/soundfont/`; it is not generated or downloaded by the build), else
3. no MIDI: the files are listed as unsupported with the reason ("MIDI rendering unavailable: no SoundFont ...").

Changing the availability of MIDI makes the next start do one full scan (the persisted index carries a capability
marker), so files move between supported and unsupported without a manual rescan.
