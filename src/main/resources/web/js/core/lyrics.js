/* Lyrics helpers: which synced line is current, and the shape the pages work with. */

/** Index of the line being sung at positionSeconds (the last line starting at or before it), -1 before the first. */
export function activeLine(lines, positionSeconds) {
  const ms = positionSeconds * 1000;
  let lo = 0, hi = lines.length - 1, found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].t <= ms) { found = mid; lo = mid + 1; } else { hi = mid - 1; }
  }
  return found;
}

/** Only synced lyrics highlight a line. */
export function isSynced(lyrics) { return !!lyrics && lyrics.synced && lyrics.lines.length > 0; }
