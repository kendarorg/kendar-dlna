# Things that need ffmpeg (not done, pure Java only)

Everything in this project is pure Java. The items below would need ffmpeg (or another native decoder) and are
deliberately **not** implemented; the behaviour meanwhile is listed for each.

| Wanted | Why it needs ffmpeg | Behaviour today |
|---|---|---|
| Cue sheets / chapters for **APE, WavPack, WMA, MPC, DSD** images | no pure-Java decoder, so the range of a section cannot be decoded | the audio file is listed as unsupported (as before); its `.cue` is ignored |
| Native byte-range streaming of cue / chapter tracks without re-encoding (cutting MP3/FLAC/AAC at frame boundaries and rewriting headers) | doable for some formats in Java, but not frame exact for all without a muxer; ffmpeg `-ss/-to -c copy` would do it | virtual tracks are always decoded and served as MP3 (or LPCM) with an exact length |
