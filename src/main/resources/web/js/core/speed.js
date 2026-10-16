/* Playback speed: the slider works in steps -2..+2 (0 = normal) mapped to a playbackRate of 0.5x..2x. */
export const SPEED_MIN = -2;
export const SPEED_MAX = 2;
const RATES = { '-2': 0.5, '-1': 0.75, '0': 1, '1': 1.5, '2': 2 };

export function clampStep(step) {
  const n = Math.round(Number(step));
  return Number.isFinite(n) ? Math.min(SPEED_MAX, Math.max(SPEED_MIN, n)) : 0;
}

export function rateForStep(step) { return RATES[String(clampStep(step))]; }

export function formatRate(rate) { return String(rate) + 'x'; }
