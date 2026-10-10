/*
 * Queue sync and resume (plan section 7), pure logic:
 *  - createQueueSync: debounced PUT of the player state (also flushed on pause, track change, page hide)
 *  - resumeFromQueue: the stored server queue -> something player.playTracks can start
 *  - remoteChange: did another device write the stored queue since we last looked?
 */

/** Body of PUT /me/queue from a core/queue.js snapshot, the position and the device. */
export function buildPayload(snap, positionSeconds, deviceId, source) {
  return {
    items: snap.items,
    index: Math.max(0, snap.index),
    positionSeconds: Math.max(0, Math.round((positionSeconds || 0) * 10) / 10),
    shuffle: !!snap.shuffle,
    repeat: snap.repeat || 'off',
    source: source || null,
    deviceId: deviceId || null
  };
}

function key(p) {
  return JSON.stringify([p.items, p.index, p.shuffle, p.repeat]);
}

/**
 * deps: {read() -> payload (see buildPayload), put(payload) -> Promise<stored>, setTimer, clearTimer,
 *        debounceMs (default 4000), minMoveSeconds (a position change smaller than this alone is not worth a write)}
 * schedule() after any change; flush() right now (pause, track change, page hide).
 */
export function createQueueSync(deps) {
  const setTimer = deps.setTimer || ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer || (h => clearTimeout(h));
  const debounceMs = deps.debounceMs === undefined ? 4000 : deps.debounceMs;
  const maxIntervalMs = deps.maxIntervalMs === undefined ? 15000 : deps.maxIntervalMs;
  const minMove = deps.minMoveSeconds === undefined ? 5 : deps.minMoveSeconds;
  let timer = null;
  let last = null;       // last payload that reached the server
  let suspended = false;
  let writes = 0;

  async function write(force, options) {
    if (suspended) return null;
    const payload = deps.read();
    if (!payload) return null;
    if (!force && last && key(last) === key(payload) && Math.abs(last.positionSeconds - payload.positionSeconds) < minMove) {
      return null;
    }
    last = payload;
    writes++;
    try {
      return await deps.put(payload, options);
    } catch (e) {
      last = null;          // retry on the next change
      throw e;
    }
  }

  return {
    /** Debounced: the last call within debounceMs wins. */
    schedule() {
      if (suspended) return;
      if (timer !== null) clearTimer(timer);
      timer = setTimer(() => { timer = null; write(false).catch(() => {}); }, debounceMs);
    },
    /**
     * Playback progress: arms a write after maxIntervalMs unless one is already pending, so a long track keeps the
     * stored position fresh without a request per time update.
     */
    touch() {
      if (suspended || timer !== null) return;
      timer = setTimer(() => { timer = null; write(false).catch(() => {}); }, maxIntervalMs);
    },
    /** Immediate write; pass {keepalive: true} on page hide. A no-change state is skipped unless force. */
    flush(options) {
      if (timer !== null) { clearTimer(timer); timer = null; }
      return write(!!(options && options.force), options);
    },
    cancel() { if (timer !== null) { clearTimer(timer); timer = null; } },
    /** While suspended nothing is written (e.g. before the stored queue was offered to the user). */
    suspend(on) { suspended = !!on; if (suspended && timer !== null) { clearTimer(timer); timer = null; } },
    /** Marks a server state as already known (after a resume), so it is not written back at once. */
    known(payload) { last = payload; },
    isPending: () => timer !== null,
    writes: () => writes
  };
}

/**
 * Stored queue (GET /me/queue) -> {tracks, index, positionSeconds, shuffle, repeat} for the playable entries only;
 * index is moved onto the playable list. null when nothing can be played.
 */
export function resumeFromQueue(stored) {
  if (!stored || !Array.isArray(stored.items)) return null;
  const playable = [];
  stored.items.forEach((e, i) => { if (e.status === 'ok' && e.track) playable.push({ track: e.track, at: i }); });
  if (!playable.length) return null;
  let index = playable.findIndex(p => p.at === stored.index);
  const exact = index >= 0;
  if (!exact) {
    // the stored current item is gone: continue at the next playable one, from its start
    index = playable.findIndex(p => p.at > stored.index);
    if (index < 0) index = 0;
  }
  return {
    tracks: playable.map(p => p.track),
    index: index,
    positionSeconds: exact ? Math.max(0, stored.positionSeconds || 0) : 0,
    shuffle: !!stored.shuffle,
    repeat: stored.repeat || 'off'
  };
}

/**
 * What the Home "continue listening" card shows for a stored queue; null for an empty or never written one.
 */
export function resumeSummary(stored) {
  const r = resumeFromQueue(stored);
  if (!r) return null;
  return { track: r.tracks[r.index], count: r.tracks.length, positionSeconds: r.positionSeconds, updated: stored.updated || null };
}

/**
 * Did another device write the queue since we last wrote or saw it?
 * known = {updated, deviceId} we hold (after our own PUT, or after the last check); stored = GET /me/queue.
 */
export function remoteChange(stored, known, myDeviceId) {
  if (!stored || !stored.updated || !stored.deviceId) return false;
  if (stored.deviceId === myDeviceId) return false;
  if (known && known.updated === stored.updated) return false;
  return !!(stored.items && stored.items.length);
}

/** "3:05" style position for the resume card. */
export function formatPosition(seconds) {
  const s = Math.max(0, Math.floor(seconds || 0));
  const m = Math.floor(s / 60);
  return m + ':' + String(s % 60).padStart(2, '0');
}

/** A random device id (kept in localStorage by the caller). */
export function newDeviceId(rng) {
  const r = rng || Math.random;
  let s = '';
  for (let i = 0; i < 12; i++) s += Math.floor(r() * 36).toString(36);
  return 'web-' + s;
}
