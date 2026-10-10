/* Track helpers (pure). */

/**
 * Personal-data endpoints return slim track objects without `playability`; the browser knows what it plays,
 * so it fills the field in the same way /library does (MIDI is always converted).
 */
export function withPlayability(track, formats) {
  if (!track || track.playability) return track;
  const format = String(track.format || '').toLowerCase();
  const native = format !== 'midi' && (formats || []).includes(format);
  return Object.assign({}, track, { playability: native ? 'native' : 'transcoded', streamFormat: native ? format : 'mp3' });
}

/** Entries of a personal list with their tracks decorated. */
export function decorateEntries(entries, formats) {
  return (entries || []).map(e => (e.track ? Object.assign({}, e, { track: withPlayability(e.track, formats) }) : e));
}
