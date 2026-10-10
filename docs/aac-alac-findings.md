# AAC and ALAC spike findings

Date: 2026-10-09. Platform: Windows 11, Corretto 25.0.3 (`--release 25`), ffmpeg 2025-01-13 (gyan.dev) as the reference
decoder and fixture generator. The unit tests quoted below live in `src/test/java/org/kendar/dlna/transcoding`
(`Mp4AudioTest`, `AacAlacDecodersTest`) and `src/test/java/org/kendar/dlna/platform/Mp4AudioIngestionTest`; the
fixtures are in `src/test/resources/audio` (`sweep.m4a`, `sweep-alac.m4a`, `transient.m4a` / `.aac`, `mono*.m4a`,
`surround.m4a`, `hires-alac.m4a`, `he-aac.m4a`, `sweep.mp4`, and the ffmpeg reference PCM `*.pcm`).
plan.md excluded both codecs because no pure-Java decoder was assumed. DSD (`.dsf`, `.dff`) is out of scope and stays in
the unsupported list.

## Verdicts

| Item | Verdict |
|---|---|
| **AAC-LC** in `.m4a` / `.mp4` / raw ADTS `.aac` | **GO**, with JAAD 0.9.4 (Apache 2.0) plus two patched JAAD classes (see below) and our own MP4/ADTS demuxer. |
| **ALAC** (`.m4a`, 16 and 24 bit, mono and stereo) | **GO**, with our own decoder (`AlacFrames`); bit-exact against ffmpeg. No third-party decoder was usable as is. |
| HE-AAC v1/v2 (SBR, PS) | **NO-GO, refused with a reason.** JAAD's SBR decoder fails on real streams. |
| AAC Main / LTP / SSR / ER / USAC, ADTS with several raw blocks, `.m4b` | Refused (reason shown) or unsupported extension. |
| ALAC with more than 2 channels, 20 or 32 bit | Refused (reason shown); no sample to verify against. |
| DSD | Untouched: unsupported. |

## 1. Candidates

| Candidate | Licence | Result |
|---|---|---|
| `de.sfuhrm:jaad:0.8.7` (JAAD, 16-bit PCM bytes API) | public domain | Works for plain streams but has the two defects below as well; no PS. Not chosen. |
| `com.tianscar.javasound:jaad:0.9.4` (JAAD fork, float output, `Receiver` API) | Apache 2.0 (POM), JAAD is public domain | **Chosen.** Pure Java, no native code, Java 25 clean, 640 KB. Two defects patched (below). |
| `io.vacco.jaad`, `com.gitlab.9lukas5:jaad` | | Same code base; not tried. |
| `org.jcodec:jcodec:0.2.5` | BSD-2 | 2 MB; its AAC decoder is a thin wrapper around a bundled copy of JAAD (same code, same defects); it adds a demuxer we do not need. Not used. |
| `com.tianscar.javasound:javasound-alac:0.2.3` (`com.beatofthedrum.alacdecoder`, Peter McQuillan's port) | BSD-3 | Decodes 24-bit stereo bit-exactly but: the decoder core is package-private, the frame count it reports is wrong (90112 for 88200 samples; it counts whole frames, so no exact length), output is a byte-per-int buffer that is wrong for 16-bit stereo through its public API, 20/32-bit unimplemented, and it drags in a Java Sound SPI. Not used as a dependency; used as a cross-check while writing our own decoder. |
| Own ALAC decoder (`AlacFrames`, about 300 lines) | ours | **Chosen.** Written from the public format description and cross-checked against the BSD port (credited in NOTICE). Verified bit-exact against ffmpeg. |
| Own MP4 demuxer (`Mp4Audio`) | ours | JAAD's and the ALAC port's demuxers do not expose the data we need (edit list, iTunSMPB, exact sample table, ALAC cookie) and the ALAC one miscounts. |

### JAAD defects found (and fixed)

1. **TNS (temporal noise shaping) is a stub.** `TNS.process` only logs "TNS unavailable". Most encoders use TNS on
   transients, so the decoded audio was wrong on those frames: per-block SNR against ffmpeg fell to 15 to 27 dB (with
   `-aac_tns 0` the same file is clean). Fixed by `src/main/java/net/sourceforge/jaad/aac/tools/TNS.java`, a copy of the
   class with the missing synthesis filter implemented (FAAD2/ffmpeg algorithm; the coefficient tables hold the negated
   reflection coefficients). Result: worst block 87 dB or better on 44.1, 48, 32, 22.05 and 96 kHz material, mono, 8 kHz mono, with M/S
   and intensity stereo, and with the Microsoft Media Foundation encoder (which uses different tools than ffmpeg).
2. **Inverse quantisation table off by one.** `IQTable` has 8191 entries (0..8190) but 8191 is a legal escape value, so a
   loud coefficient threw `ArrayIndexOutOfBoundsException` (hit by a 48 kHz ffmpeg encode with M/S or intensity on).
   Fixed by `src/main/java/net/sourceforge/jaad/aac/syntax/IQTable.java` (8192 entries, computed at start-up).
3. **SBR fails** on a real HE-AAC v1 file (Microsoft encoder, `EOSException: stream overrun` in `sbr_noise`), so HE-AAC is
   refused rather than played wrongly. PS is untested for the same reason.
4. **The jar registers a Java Sound `AudioFileReader`** that throws `NullPointerException` on foreign input and made
   existing WAV tests fail. `build.gradle` repackages the JAAD jar (task `jaadNoSpi`) without `META-INF/services` and
   `net/sourceforge/jaad/spi`, and without the two replaced classes, so the patched ones are the only ones on the class path.
5. **PNS noise is not reproducible.** Perceptual noise substitution fills bands from a JVM-wide static generator
   (`ICStream.randomState`), so two decodes (or a decode and a seek) of a stream that uses PNS differ in the noise bands.
   Everything else is deterministic (verified: three runs and a seek are bit-identical without PNS). PNS is rare (ffmpeg's
   native encoder enables it by default, `-aac_pns 0` was used for the fixtures; iTunes, Nero and FAAC do not use it by
   default). Consequence: for such files the live transcode and a seeked transcode differ in the noise, not in length.
   Not patched.

## 2. Decoding correctness (against ffmpeg's decoder)

Per-block (1024 frames) SNR of our output against `ffmpeg -f s16le`, worst block of the file:

| File | Result |
|---|---|
| AAC-LC stereo 44.1 kHz sweep, 128 kbit/s | about 90 dB (maximum difference 1 LSB) |
| transient material with TNS (`transient.m4a`) | above 70 dB (test threshold); 89 dB measured on the 6 s original |
| 48 / 32 / 22.05 / 96 kHz, mono 44.1 kHz, mono 8 kHz, M/S and intensity stereo, `aac_mf` encoder | 87 to 92 dB |
| ADTS | same as M4A (ADTS has no priming metadata, both decoders output every frame) |
| 5.1 (`surround.m4a`) | channel order and fold-down checked against ffmpeg's six-channel decode (levels agree to within 1 %) |
| ALAC 16-bit stereo | **bit-identical** to the source WAV (and to ffmpeg) |
| ALAC 24-bit, 96 kHz | bit-identical in 24 bit to ffmpeg; reduced to 16 bit with rounding, maximum difference to ffmpeg's 16-bit conversion 1 LSB |
| ALAC mono | bit-identical, duplicated to both channels |

Not verified: files from iTunes, Nero, FAAC or Fraunhofer encoders (only ffmpeg's native encoder and Microsoft's
Media Foundation encoder were available), AAC-LTP (ffmpeg refuses to encode it without `-strict -2`), channel
configuration 7 (7.1) fold-down, ER profiles, and ALAC from a real iTunes rip.

## 3. Exact sample count and gapless

Output is `totalSamples` frames at the stream rate, the same contract as the other decoders (`FixedLengthDecoder` pads or cuts).

`Mp4Audio` decides the window in this order:

1. a single non-empty edit-list entry (`elst`, what ffmpeg and iTunes write): skip `media_time` samples, play
   `segment_duration * media timescale / movie timescale` (clipped to the media; 0 means to the end). Leading empty
   edits are ignored; two or more real edits disable the list.
2. the iTunes `iTunSMPB` comment: skip = priming, play = valid sample count (else total minus priming minus padding).
3. otherwise nothing is skipped and the length is the sum of `stts`.

ffmpeg's M4A: 1024 priming samples in `elst`, last access unit shortened in `stts` (the sweep: 88 units, 89224 decoded
samples, 88200 played). Our length is the true 88200; ffmpeg's decoder itself outputs 89088 (it trims the start only),
so reference comparisons use the common prefix. Alignment: JAAD's output needs no extra delay compensation, sample 0
after the 1024 skip equals ffmpeg's sample 0 (lag 0 is the SNR maximum, +-1 sample is 10 dB worse).
Raw ADTS has no gapless data: length = frames * 1024. ALAC has no priming normally: length = `stts` total (22 frames of
4096 give 88200 for the sweep because the last `stts` entry is the short frame).
The `iTunSMPB` and multi-edit paths are tested on synthetic MP4 structures (`Mp4Synth`), not on iTunes files.

Time bases: `mdhd` time scale must equal the sample rate (always true for LC and ALAC); otherwise the file is refused.

## 4. Seeking

* AAC: restart the decoder two access units (2048 samples) before the target and discard; the MDCT overlap-add only
  needs the previous frame, so the result is **bit-identical** to the continuous decode (tested at 0, 1, 1023, 1024, 1025,
  5000, 9000, last sample, end; also a backward seek on one decoder). Exception: PNS (above).
* ALAC: frames are independent; binary search in the sample table and discard inside the frame. Bit-identical.
* ADTS: the frame table is built by one header walk when the file is opened (a few thousand 7-byte reads).

## 5. Performance

Cold JVM including JIT warm-up (unit tests, real files): AAC 40 to 70 times real time, ALAC 100 to 130 times real time,
96 kHz / 24-bit ALAC about 100 times. A 5 minute track therefore decodes in under 10 seconds, the MP3 encoder (24 to 28
times real time) stays the bottleneck.

## 6. Channels, rates, depths

* AAC channel configurations 1 to 7 are folded to stereo (centre and surrounds at -3 dB, LFE dropped, normalised so that
  full scale in every channel cannot clip; `AacDecoder.mixMatrix`). Configuration 0 (layout in a PCE) and channel counts
  above 8 are refused. Mono is duplicated.
* ALAC: mono and stereo, 16 and 24 bit. More channels, 20 and 32 bit are refused (`UnsupportedAudioException`, shown as
  the reason in the unsupported list).
* Sample rates: whatever the stream has (8 kHz to 96 kHz tested); rates below 32 kHz go through the existing
  upsampler before the MP3 encoder.
* HE-AAC, explicit (AOT 5/29) or backward compatible (sync extension 0x2b7), is refused up front from the
  AudioSpecificConfig; implicit signalling in ADTS is caught when the first access units decode to 2048 samples.

## 7. Tags (jaudiotagger 3.0.1)

Read and write verified on ffmpeg-made AAC and ALAC files (`Mp4AudioIngestionTest`, `TagServiceTest`): title (`nam`),
artist, album, album artist (`aART`), composer (`wrt`), genre, year, track `trkn`, disc `disk`, cover `covr` (kept
through a rewrite), non-ASCII text. jaudiotagger reports `Aac` / `Alac` as the encoding type, which is how the scanner
tells ALAC from AAC in an `.m4a`. Quirks: the channel count is nonsense for 5.1 and HE-AAC (-10, -14; treated as unknown),
the reported bit rate of ALAC is the PCM rate, and there is **no reader for raw `.aac`** (hence no tags and no tag
editing for ADTS; its bit rate is computed from size and duration). Writes go through the existing safe path
(temp copy with the same name suffix, tag write, atomic rename): after a write the container still decodes to exactly
the same samples (tested for AAC and ALAC) and no temp file is left.

## 8. What the server does with them

See README and `docs/transcoding.md`. Short version: `.m4a` / `.mp4` are `AAC` or `ALAC` according to the codec, `.aac`
is `ADTS`; native `res` is `audio/mp4` (AAC, ALAC) or `audio/aac` (ADTS) without a `DLNA.ORG_PN` (the AAC_ISO / AAC_ADTS
profile names depend on bit rate and layout limits we do not check); the default profile offers the transcoded MP3
first and the original second; VLC, Kodi, foobar2000 (AAC, ALAC, ADTS), BubbleUPnP (AAC, ADTS) and the TV and WMP
profiles (AAC) list them as native.

## Open items

* HE-AAC would need a working SBR decoder (JAAD's fails); a patch of its SBR code or another library would be needed.
* Real-world encoders other than ffmpeg and Media Foundation (iTunes, Nero, Fraunhofer) have not been tried.
* PNS noise determinism (static generator in JAAD).
* Real renderers have not been tried with the `audio/mp4` native `res`.
