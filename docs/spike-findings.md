# Milestone 7 spike findings

Date: 2026-10-09. Platform: Windows 11, Corretto 25.0.3 (`--release 25`). Everything below is backed by JUnit unit tests under
`src/test/java/org/kendar/dlna/transcoding` (no Jetty, no SSDP) except where marked **not verified**.
Console numbers quoted here are printed by those tests as `[spike] ...` lines
(run `./gradlew test -i --tests 'org.kendar.dlna.transcoding.*'`).

## Verdicts

| # | Item | Verdict |
|---|---|---|
| 1 | jump3r MP3 encoder (CBR 320, 48 kHz, no reservoir, no Xing/ID3, exact length, fresh-encoder seek, resampling) | **GO**, with two mandatory workarounds (reflection for flags; upsample sources below 32 kHz). No failure that forces LPCM-only was found; LPCM stays as a client-profile option. |
| 2 | Gervill offline MIDI at 48 kHz, headless | **GO**, needs `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` (reflection plus JVM flag / manifest `Add-Exports`). Silence trim designed and tested. GeneralUser GS **v2.0.3** loads and renders; the SoftSynth edition could not be obtained. |
| 3 | Opus via Concentus plus own Ogg demux | **GO (viable, keep Opus)**. Exact length and seeking work. **jaudiotagger 3.0.1 cannot read `.opus`** (no reader registered), so Opus tags and duration come from our own `OggOpusReader`. |
| 4 | Decoders: JLayer, JOrbis, JFLAC, Java Sound | All decode on Java 25. Use the **direct APIs**, not the SPIs (mp3spi and vorbisspi expose no frame length; vorbisspi under-read in a test). Exact sample counts are available for every format. |

## 1. jump3r (de.sciss:jump3r:1.0.5)

Seed code: `transcoding/Mp3Encoder`, `Mp3Frames`, `LowRateUpsampler`. Tests: `Mp3EncoderTest`.

### How it is driven
- `de.sciss.jump3r.lowlevel.LameEncoder` is the usable API: `LameEncoder(AudioFormat src, AudioFormat dst)`; target format
  `LameEncoder.MPEG1L3`, 48000 Hz, properties map with `bitrate` (Integer), `vbr` (Boolean false), `quality` (Integer).
  The `chmode` property must not be passed as an int (throws `illegal type of chmode property`); the default (joint stereo) is used.
- The public API has **no switch for the bit reservoir or the Xing/Info frame**, and it turns ReplayGain on. Workaround (tested):
  after construction, reflect the two package-private fields `lame` and `gfp`, build a fresh `LameGlobalFlags` with
  `lame.lame_init()`, copy channels, rates, mode, VBR, brate, quality, and set the public fields `disable_reservoir = true`,
  `bWriteVbrTag = false`, `findReplayGain = false`, `write_id3tag_automatic = false`, then `lame_init_params`.
  It is the only reflection, guarded by an `IllegalStateException`, and the jump3r version is pinned.
- Throughput: about 24-28x real time (30 s of 44.1 kHz stereo, including JIT warm-up). Output is deterministic (two encodes byte-identical).

### Results (all asserted by tests)
- **Constant frames**: at 320 kbps every frame is 960 bytes, MPEG-1 Layer III, 48 kHz, no padding bit, no CRC. `Mp3Frames.scan`
  walks the stream with zero leading and zero trailing bytes.
- **No Info/Xing frame, no ID3**: the first frame is audio; no frame carries `Xing`/`Info`; no `ID3`.
- **Reservoir off**: every frame has `main_data_begin = 0`. Control experiment with the reservoir on at 128 kbps: 121 frames with
  `main_data_begin > 0`, so the check is meaningful. (At 320 kbps CBR LAME barely uses the reservoir anyway.)
- **Configurable bitrate**: 320, 256 checked (frame size is `3 * kbps` bytes, an integer for every bitrate).
- **Length**: raw LAME output without flush is typically `target - 1` frames (44 100 samples gives 41 frames against the formula's 42).
  `Mp3Encoder.encodeExact` truncates at a frame boundary if too long and pads with encoder-generated silence if too short.
  Verified `length == ceil(samples48k / 1152) * frameSize` for source rates 44.1/48/32/22.05 kHz and sample counts 1, 100, 1151, 1152,
  1153, 2304, 44100, 44101, 100003, 220500. JLayer decodes every frame (`frames * 1152` PCM frames at 48 kHz, no errors).
- **Fresh-encoder seek**: encoding from source sample `k*1152*rate/48000` yields exactly the expected remaining frame count, all
  with `main_data_begin == 0`, decodable from byte 0 with JLayer. The decoded audio aligns with the continuous encode within
  **0-1 sample** (the "about 1 frame shift" in plan §11 is conservative). Tested at frames 1, 37, 100, 211.
- **Byte-exact Range of a cached transcode**: cutting the full stream at any frame boundary and decoding from there is
  bit-identical to the continuous decode after a 2-frame warm-up of the synthesis filterbank (frames 3, 50, 120).
- **44.1 to 48 kHz quality** (1 s tones, 320 kbps, decoded with JLayer, least-squares sine fit): gain -0.02 dB and SNR 78-87 dB at
  100, 440, 1000, 5000, 10000, 15000 and 18000 Hz. Flat to 18 kHz. 32, 48, 88.2, 96 and 192 kHz sources also give 320 kbps and 80+ dB SNR.

### Failure found and its fix
- **Sources below 32 kHz (8, 11.025, 12, 16, 22.05, 24 kHz): LAME silently lowers the CBR bitrate to 160 kbps** (480-byte frames)
  although 320 was requested and 48 kHz output is set. That breaks the constant-size and Content-Length rule.
  Fix in the seed: `LowRateUpsampler` (integer-factor windowed sinc) before the encoder; `Mp3Encoder` refuses sources below 32 kHz.
  After the fix: 320 kbps, exact length, gain -0.02 dB, SNR 82-85 dB. The seed works on whole buffers; milestone 8 needs a streaming
  variant (keep the last 16 input frames as state).
- Mono: the seed encoder is stereo only; the decoder layer must duplicate mono to stereo. Not otherwise tested.

### Not verified
- Real client behaviour (VLC, BubbleUPnP, Kodi, TVs seeking into the stream) is milestone 10. Only decoder-level (JLayer) independence was tested.

## 2. Gervill MIDI

Seed code: `transcoding/MidiRenderer`, `SilenceTrimmer`. Tests: `MidiRendererTest`, `SilenceTrimmerTest`.

- **Offline, headless, 48 kHz: works.** `com.sun.media.sound.SoftSynthesizer` is created by reflection and
  `AudioSynthesizer.openStream(AudioFormat, Map)` returns an `AudioInputStream` (48000 Hz, 16-bit, stereo, little-endian).
  No audio device is touched. Events go to `getReceiver()` with microsecond timestamps from our own tick-to-time map
  (PPQ with tempo meta events, SMPTE); tempo handling is unit tested.
- **Requires `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`.** Verified with a plain `java` launch:
  `MidiRenderer.isAvailable()` is false without the flag and true with it. Already wired in `build.gradle`:
  `applicationDefaultJvmArgs`, test JVM args, and the shadow jar manifest `Add-Exports: java.desktop/com.sun.media.sound`
  (confirmed present in `build/libs/kendar-dlna.jar`; `java -jar` honours it). Milestone 8 must derive `Capabilities.midi` from `isAvailable()`.
- **Determinism**: two renders of the same sequence are byte-identical (generated sequence with the built-in bank; real 111 s file with GeneralUser GS).
- **Silence trim (`SilenceTrimmer.readTrimmed`)**: the synth stream is endless. Read until the last MIDI event, then keep reading until
  the output has stayed at or below the threshold for `holdFrames`, or `maxTailFrames` after the event end, whichever comes first;
  then cut at `last audible frame + keepFrames`. Defaults at 48 kHz: threshold 16 (about -66 dBFS), hold 0.5 s, cap 10 s, keep 50 ms.
  Tested: tone then silence, exponentially decaying tail (cut at the computed crossing), never-ending tone (capped), all silence
  (empty), short finite stream, leading silence preserved, sub-threshold noise counts as silence, empty input.
  With the built-in bank an arpeggio whose last event is at 1.92 s ends at 3.10 s.
- **Speed**: a real 111 s MIDI (Bond.mid from the GeneralUser GS repo) renders in about 4.8 s (about 23x real time, linear interpolation).
  Short sequences cost 0.8-1.9 s each, dominated by `loadAllInstruments` per synthesizer. The parsed `Soundbank` is shared across
  renders (cached in `MidiRenderer`), so the 32 MB file is parsed once. Tests ran with a 512 MB heap.
- **SoundFont**: loaded from a configurable path via `MidiSystem.getSoundbank(File)` (`new MidiRenderer(Path)`; `null` means Gervill's
  built-in emergency bank, which works but sounds crude).
- **GeneralUser GS**: downloaded from `https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/main/GeneralUser-GS.sf2`
  (32 319 396 bytes, **v2.0.3**; its licence allows use in software projects). It is not committed to the repo.
  That repository contains **only the v2 edition**; the **SoftSynth edition (v1.44) is not published there**, and the project site
  gave no direct link from this environment, so the **SoftSynth versus v2 comparison could not be done**.
  Verified: v2.0.3 loads in Gervill and renders five instruments (programs 0, 24, 40, 56, 73) and a full real file without errors,
  with sane levels, deterministically. Sound quality and modulator fidelity were **not verified** (no listening possible).
- **Where to place the SoundFont**: for development, put the file anywhere and run
  `./gradlew test -PsoundFont=C:/path/GeneralUser-GS.sf2` (system property `dlna.test.soundfont`; a `Bond.mid` next to it enables the real-file test).
  For milestone 8: copy it to `src/main/resources/soundfont/GeneralUser-GS.sf2` (bundled in the fat jar, plan §16) and let the config key
  `transcode.soundFontPath` (for example `/data/GeneralUser-GS.sf2`) override it; add its licence text to `NOTICE`.
  To try the SoftSynth edition, place it at the configured path.

## 3. Opus

Seed code: `transcoding/OggOpusReader`, `OggCrc`. Tests: `OpusTest`; test-only muxer `OggOpusWriter` (Concentus encoder plus Ogg pages).

- **Dependency**: Concentus is `io.github.jaredmdobson:concentus:1.0.2` on Maven Central (the plan's `org.concentus:Concentus` does not exist).
  It has a decoder and an encoder but **no Ogg support**; the demuxer is ours (pages scanned from a `RandomAccessFile`, packets reassembled
  across lacing and continued pages, page CRC verifiable).
- **Decoding**: works on Java 25. A Concentus-encoded stream (96 kbps stereo 1 kHz tone) decodes to amplitude 0.498 (expected 0.5), SNR 45 dB.
  An independent ffmpeg/libopus file decodes with valid CRCs.
- **Exact length**: `totalSamples = last page granule - OpusHead pre-skip` (48 kHz, RFC 7845). Verified: 3 s + 123 samples gives exactly 144 123;
  the ffmpeg 2.000 s file gives exactly 96 000 (pre-skip 312).
- **Tags**: parsed from `OpusTags` by `OggOpusReader` (vendor, multi-value keys, UTF-8; ffmpeg TITLE/ARTIST/ALBUM read correctly).
- **Seeking**: position accurate, restart at a page boundary at least N samples earlier and discard. The Concentus decoder needs history to converge.
  Measured max difference against the continuous decode: 80 ms preroll about 1000 LSB, 200 ms about 22 LSB, 400 ms 12 LSB, 1 s bit-exact.
  **Decision: 200 ms preroll** (default in the seed, `setPreroll` to change).
- **jaudiotagger 3.0.1**: `AudioFileIO.read(x.opus)` throws `CannotReadException: No Reader associated with this extension:opus`.
  Ingestion must use a small Opus tag reader built on `OggOpusReader`; no tag writing for Opus (editor disabled).
- Only channel mapping family 0 (mono and stereo) is supported; others throw `IOException`.
- **Verdict: viable, keep Opus.** Not verified: very large files, chained Ogg streams, embedded `METADATA_BLOCK_PICTURE` art.

## 4. Decoder survey

Fixtures (`src/test/resources/audio`): 2 s, 44.1 kHz stereo sweep generated once with ffmpeg (Opus at 48 kHz). ffmpeg is not needed to run the tests.

| Format | Library / API | Java 25 | Exact sample count | Seeking |
|---|---|---|---|---|
| MP3 | JLayer 1.0.1.4 direct (`Bitstream`, `Decoder`) | Yes | Header walk only (`readFrame`/`closeFrame`, no decode): `frames * 1152` (576 for MPEG-2/2.5) equals the decoded count exactly (79 frames = 91 008 for the fixture). The LAME tag (delay 576, padding 1080 in the fixture) gives the gapless length 89 352 but decoders emit whole frames; a transcode only needs a self-consistent count | Frame granular: skip headers to frame k-4, decode and discard 4 warm-up frames (bit reservoir); output identical to the continuous decode (tested k=40) |
| OGG Vorbis | JOrbis 0.0.17 direct (`SyncState`, `StreamState`, `DspState`, `Block`) | Yes | Last page granule (88 200, exact); decoder output is within one block of it, so truncate to the granule | Not exercised in code; same page-index-and-discard approach as Opus (milestone 8 work) |
| FLAC | JFLAC 1.5.2 (`FLACDecoder`, `PCMProcessor`) | Yes, bit-exact against the WAV sibling | `StreamInfo.getTotalSamples()` (88 200). 24-bit FLAC is delivered as 3 bytes per sample (the first fixture was 24-bit), so convert to 16-bit | Decode and discard: skipping to 80 000 samples took 18 frames, 5.5 ms. `seek(long)` and SEEKTABLE exist, not exercised |
| WAV / AIFF | Java Sound (`AudioSystem`) | Yes; AIFF is big-endian, convert with `getAudioInputStream(targetFormat, ais)`; AIFF equals WAV sample for sample | `getFrameLength()` exact (88 200) | Byte exact via `skip()` (tested at frame 12 345) |
| Opus | Concentus plus `OggOpusReader` | Yes | granule minus pre-skip | Position exact with 200 ms preroll |

SPIs: mp3spi 1.9.5.4 reports `frameLength = -1`; vorbisspi 1.0.3.3 reports `frameLength = -1` and returned only 44 608 of 88 200 frames
through `readAllBytes` on a converted stream. Neither can give an exact Content-Length; both are `testImplementation` only and **must not be shipped**.

Coordinates confirmed on Maven Central and resolved: `de.sciss:jump3r:1.0.5`, `io.github.jaredmdobson:concentus:1.0.2`,
`com.googlecode.soundlibs:jlayer:1.0.1.4`, `org.jcraft:jorbis:0.0.17`, `org.jflac:jflac-codec:1.5.2`.
Test only: `com.googlecode.soundlibs:mp3spi:1.9.5.4`, `com.googlecode.soundlibs:vorbisspi:1.0.3.3`.
Licences for `NOTICE`: jump3r/LAME, JLayer, JOrbis and JFLAC are LGPL; Concentus is BSD-style; GeneralUser GS has its own licence file.

## Decisions and plan changes for milestone 8

1. MP3 transcoding is **GO** with CBR 320, 48 kHz, no reservoir, no Xing. LPCM stays only as a profile option.
2. Use `Mp3Encoder` and `Mp3Frames` (exact length by pad/truncate; typically one silent frame is padded). Never construct `LameEncoder` directly.
3. **Upsample sources below 32 kHz** before LAME (streaming version of `LowRateUpsampler`); otherwise LAME produces 160 kbps and the length math breaks. Duplicate mono to stereo.
4. Frame count rule: `ceil(round(n * 48000 / rate) / 1152)`; Content-Length = frames x 960.
5. Seek: frame index to source sample `k*1152*rate/48000`, decoder seek, fresh encoder, limit to the remaining frame count. Update plan §11: the shift is 0-1 samples, not a frame.
6. **Direct codec APIs only** (JLayer, JOrbis, JFLAC, Java Sound for WAV/AIFF, own Opus reader). Sample counts: MP3 `frames*spf` from a header walk, Vorbis last granule, FLAC StreamInfo, WAV/AIFF frame length, Opus granule minus pre-skip.
7. MIDI: `Capabilities.midi = MidiRenderer.isAvailable()`; keep `Add-Exports` in manifest and JVM args; keep the soundbank cached; default tail policy 16 / 0.5 s / 10 s / 50 ms. Bundle GeneralUser GS v2.0.3 as described above (the user must copy the file). SoftSynth comparison remains open.
8. Opus stays supported. Fix plan §2 and `plans/03-transcoding.md` to `io.github.jaredmdobson:concentus:1.0.2`. Ingestion needs an Opus reader on `OggOpusReader` because jaudiotagger cannot read `.opus`. 200 ms seek preroll.
9. FLAC pipeline must handle 24-bit input; WAV/AIFF other than 16-bit were not tested.
10. `./gradlew build` (all unit tests, ArchUnit, shadowJar) passes with the spike code.

## Files added or changed
- `build.gradle`: codec dependencies (pinned), `Add-Exports` manifest attribute and JVM flag, `-PsoundFont` test property.
- `src/main/java/org/kendar/dlna/transcoding/`: `Mp3Encoder`, `Mp3Frames`, `LowRateUpsampler`, `SilenceTrimmer`, `MidiRenderer`, `OggOpusReader`, `OggCrc`.
- `src/test/java/org/kendar/dlna/transcoding/`: `Mp3EncoderTest`, `SilenceTrimmerTest`, `MidiRendererTest`, `OpusTest`, `DecoderSurveyTest`, helpers `TestAudio`, `OggOpusWriter`.
- `src/test/resources/audio/`: ffmpeg-generated fixtures (`sweep.mp3/.ogg/.flac/.wav/.aiff/.opus`).
