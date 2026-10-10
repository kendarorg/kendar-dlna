/*
 * Play reporting (plan section 5): a play counts after 30 s or 50 % of the track. The client only reports plays that
 * qualify, queues them and sends them in batches (one flush per few seconds, sequentially), so a burst of skipped
 * tracks costs nothing and a failing network loses nothing until the retry limit.
 */
export const COUNT_SECONDS = 30;
export const COUNT_FRACTION = 0.5;

export function qualifies(playedSeconds, durationSeconds) {
  const played = Number(playedSeconds) || 0;
  const duration = Number(durationSeconds) || 0;
  if (played >= COUNT_SECONDS) return true;
  return duration > 0 && played >= duration * COUNT_FRACTION;
}

/**
 * deps: {send(report) -> Promise, setTimer, clearTimer, flushMs, maxQueue, maxAttempts}
 * A report is {path, playedSeconds, durationSeconds}. send rejects with {status, retryAfterSeconds?}:
 *   429 -> try again later (retry-after honored), 401/403/404 -> drop, anything else -> retry up to maxAttempts.
 */
export function createPlayReporter(deps) {
  const setTimer = deps.setTimer || ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer || (h => clearTimeout(h));
  const flushMs = deps.flushMs === undefined ? 3000 : deps.flushMs;
  const maxQueue = deps.maxQueue || 100;
  const maxAttempts = deps.maxAttempts || 3;
  let queue = [];
  let timer = null;
  let flushing = null;
  let sent = 0, dropped = 0;

  function schedule(ms) {
    if (timer !== null) return;
    timer = setTimer(() => { timer = null; flush(); }, ms === undefined ? flushMs : ms);
  }

  /** Returns true when the play was queued (it qualified). */
  function report(play) {
    if (!play || !play.path || !qualifies(play.playedSeconds, play.durationSeconds)) return false;
    queue.push({ path: play.path, playedSeconds: Math.round(play.playedSeconds), durationSeconds: Math.round(play.durationSeconds || 0), attempts: 0 });
    while (queue.length > maxQueue) { queue.shift(); dropped++; }
    schedule();
    return true;
  }

  async function run() {
    while (queue.length) {
      const item = queue[0];
      try {
        await deps.send({ path: item.path, playedSeconds: item.playedSeconds, durationSeconds: item.durationSeconds });
        queue.shift();
        sent++;
      } catch (e) {
        const status = e && e.status;
        if (status === 429) {
          schedule(Math.max(1, e.retryAfterSeconds || (e.details && e.details.retryAfterSeconds) || 5) * 1000);
          return;
        }
        if (status === 401 || status === 403 || status === 404 || status === 400) { queue.shift(); dropped++; continue; }
        item.attempts++;
        if (item.attempts >= maxAttempts) { queue.shift(); dropped++; continue; }
        schedule(flushMs * 2);
        return;
      }
    }
  }

  /** Sends everything queued now (also used on page hide). */
  function flush() {
    if (timer !== null) { clearTimer(timer); timer = null; }
    if (flushing) return flushing;
    flushing = run().finally(() => { flushing = null; });
    return flushing;
  }

  return {
    report, flush,
    pending: () => queue.length,
    stats: () => ({ sent: sent, dropped: dropped, pending: queue.length }),
    clear() { queue = []; if (timer !== null) { clearTimer(timer); timer = null; } }
  };
}
