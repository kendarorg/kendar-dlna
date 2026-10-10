/*
 * Browser format negotiation (plan section 7): which of the server's formats the audio element can play.
 * The names are the lower-case format names of track objects; the server falls back to an MP3 transcode for the rest.
 */

export const CANDIDATES = {
  mp3: ['audio/mpeg', 'audio/mp3'],
  ogg: ['audio/ogg; codecs="vorbis"'],
  opus: ['audio/ogg; codecs="opus"', 'audio/opus', 'audio/webm; codecs="opus"'],
  flac: ['audio/flac', 'audio/x-flac'],
  wav: ['audio/wav', 'audio/wave', 'audio/x-wav']
};

/** canPlay(mime) behaves like HTMLMediaElement.canPlayType: '' (no), 'maybe' or 'probably'. */
export function detectFormats(canPlay) {
  const out = [];
  for (const name of Object.keys(CANDIDATES)) {
    if (CANDIDATES[name].some(m => { try { return !!canPlay(m); } catch (e) { return false; } })) out.push(name);
  }
  return out;
}

/** Comma separated form used by the optional `formats` query parameter. */
export function formatsParam(formats) {
  return (formats || []).join(',');
}

export function sameFormats(a, b) {
  const x = (a || []).slice().sort().join(','), y = (b || []).slice().sort().join(',');
  return x === y;
}

/**
 * Detects the formats and posts them to /stream/formats unless this session already did so with the same list.
 * deps: {canPlay, post(formats) -> Promise, storage (sessionStorage-like, optional), sessionKey (changes per login)}
 * Returns the list that applies (the detected one, also when posting failed: the server then uses its default).
 */
export async function negotiateFormats(deps) {
  const formats = detectFormats(deps.canPlay);
  const key = 'kd.formats.' + (deps.sessionKey || '');
  const signature = formats.slice().sort().join(',');
  try {
    if (deps.storage && deps.storage.getItem(key) === signature) return formats;
  } catch (e) { /* storage unavailable: post again */ }
  try {
    await deps.post(formats);
    try { if (deps.storage) deps.storage.setItem(key, signature); } catch (e) { /* ignore */ }
  } catch (e) { /* the server keeps its default list */ }
  return formats;
}
