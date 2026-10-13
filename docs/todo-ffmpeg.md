# Things that would still need ffmpeg (not done)

WMA, APE, DSD, WavPack, Musepack, `.m4b`, HE-AAC and the FLAC / ALAC output already go through the optional ffmpeg
binary. The items below would need more of it and are deliberately **not** implemented; the behaviour meanwhile is
listed for each.

| Wanted | Why it needs ffmpeg | Behaviour today |
|---|---|---|
| Native byte-range streaming of cue / chapter tracks without re-encoding for FLAC / AAC | needs a muxer to cut and rewrite headers; ffmpeg `-ss/-to -c copy` would do it | WAV sections are cut exactly and MP3 sections at frame boundaries (`transcode.passthrough`); the others are decoded and served as MP3 / LPCM / WAV / FLAC with an exact length (FLAC: known after encoding) |
