/* Browser tab title: "artist - title" of the current track, the app name when nothing is loaded. */
export const APP_TITLE = 'Kendar Music';

export function pageTitle(track) {
  if (!track) return APP_TITLE;
  const title = (track.title || track.name || '').trim();
  const artist = (track.artist || '').trim();
  if (title && artist) return artist + ' - ' + title;
  return title || artist || APP_TITLE;
}

/** Characters of a scrolling tab title that stay readable in a tab strip / window title. */
export const TITLE_WIDTH = 30;
const GAP = '   •   ';

/**
 * One frame of the scrolling tab title: the text as a loop (text, gap, text, ...) read from `step` for `width`
 * characters. A text that fits is returned as it is.
 */
export function scrollFrame(text, step, width) {
  const w = width || TITLE_WIDTH;
  const chars = Array.from(String(text || ''));
  if (chars.length <= w) return chars.join('');
  const loop = chars.concat(Array.from(GAP));
  const at = ((step || 0) % loop.length + loop.length) % loop.length;
  const out = [];
  for (let i = 0; i < w; i++) out.push(loop[(at + i) % loop.length]);
  return out.join('');
}
