/* Display formatting helpers. Pure functions. */

/** Milliseconds as m:ss (h:mm:ss from one hour); empty for 0/unknown. */
export function formatDuration(ms) {
  if (!ms) return '';
  return formatClock(Math.round(ms / 1000));
}

/** Seconds as m:ss or h:mm:ss; "0:00" for invalid input. */
export function formatClock(seconds) {
  const total = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = (s < 10 ? '0' : '') + s;
  if (h > 0) return h + ':' + (m < 10 ? '0' : '') + m + ':' + ss;
  return m + ':' + ss;
}

export function formatSize(bytes) {
  if (bytes === null || bytes === undefined) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

export function formatTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Total duration of a group: "1 h 05 min" / "42 min". */
export function formatTotal(ms) {
  if (!ms) return '';
  const minutes = Math.round(ms / 60000);
  if (minutes < 60) return minutes + ' min';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h + ' h ' + (m < 10 ? '0' : '') + m + ' min';
}

/**
 * The part of a source file a cue sheet / chapter track plays, "1:05 - 4:32"; empty for a normal track.
 * `segment` is the `segment` object of the track JSON ({ startMillis, endMillis, ... }).
 */
export function formatSegmentRange(segment) {
  if (!segment || !Number.isFinite(segment.startMillis) || !Number.isFinite(segment.endMillis)) return '';
  const clock = ms => formatClock(Math.max(0, ms) / 1000);
  return clock(segment.startMillis) + ' - ' + clock(segment.endMillis);
}

/** Name shown for the origin of a virtual album: "cue", "tag-cue" and "chapters" are kept as the badge text. */
export function segmentOrigin(segment) {
  return segment && segment.origin ? String(segment.origin) : '';
}
