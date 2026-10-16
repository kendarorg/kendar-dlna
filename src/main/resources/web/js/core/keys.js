/*
 * Keyboard shortcuts of the player, as a pure mapping from a key event to an action name.
 *   space / k  play-pause        n / p     next / previous      left / right  seek -/+ 5 s
 *   shift+up/down  volume        m  mute   s  shuffle           r  repeat
 *   q  queue panel               f  full-screen player          l  lyrics      ?  help
 */
export const SHORTCUTS = [
  { keys: 'Space / K', action: 'toggle' },
  { keys: 'N', action: 'next' },
  { keys: 'P', action: 'prev' },
  { keys: '← / →', action: 'seek' },
  { keys: 'Shift + ↑ / ↓', action: 'volume' },
  { keys: 'M', action: 'mute' },
  { keys: 'S', action: 'shuffle' },
  { keys: 'R', action: 'repeat' },
  { keys: 'Q', action: 'queue' },
  { keys: 'F', action: 'fullscreen' },
  { keys: 'L', action: 'lyrics' }
];

const TYPING = new Set(['INPUT', 'TEXTAREA', 'SELECT']);
const ACTIVATES_ON_SPACE = new Set(['BUTTON', 'A', 'SUMMARY']);

/** ev: {key, shiftKey, ctrlKey, altKey, metaKey, target: {tagName, isContentEditable}} */
export function shortcutFor(ev) {
  if (!ev || ev.ctrlKey || ev.altKey || ev.metaKey) return null;
  const tag = ev.target && ev.target.tagName ? String(ev.target.tagName).toUpperCase() : '';
  if (TYPING.has(tag) || (ev.target && ev.target.isContentEditable)) return null;
  const key = ev.key;
  if (key === ' ' || key === 'Spacebar') return ACTIVATES_ON_SPACE.has(tag) ? null : 'toggle';
  if (key === 'ArrowLeft') return 'seekBack';
  if (key === 'ArrowRight') return 'seekForward';
  if (key === 'ArrowUp') return ev.shiftKey ? 'volumeUp' : null;
  if (key === 'ArrowDown') return ev.shiftKey ? 'volumeDown' : null;
  switch (String(key).toLowerCase()) {
    case 'k': return 'toggle';
    case 'n': return 'next';
    case 'p': return 'prev';
    case 'm': return 'mute';
    case 's': return 'shuffle';
    case 'r': return 'repeat';
    case 'q': return 'queue';
    case 'f': return 'fullscreen';
    case 'l': return 'lyrics';
    case '?': return 'help';
    default: return null;
  }
}
