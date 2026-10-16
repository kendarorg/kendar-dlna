/* Pane resizing helpers (pure): width clamping and keyboard steps. */
export const KEY_STEP = 16;

export function clampWidth(px, min, max) {
  const n = Number(px);
  if (!Number.isFinite(n)) return min;
  return Math.round(Math.min(max, Math.max(min, n)));
}

/** New width for a drag: start width plus the pointer movement. */
export function dragWidth(startWidth, startX, x, min, max) {
  return clampWidth(startWidth + (x - startX), min, max);
}

/** Width after an arrow/home key, null when the key is not handled. */
export function keyWidth(width, key, min, max, fallback) {
  if (key === 'ArrowLeft') return clampWidth(width - KEY_STEP, min, max);
  if (key === 'ArrowRight') return clampWidth(width + KEY_STEP, min, max);
  if (key === 'Home') return clampWidth(fallback, min, max);
  return null;
}
