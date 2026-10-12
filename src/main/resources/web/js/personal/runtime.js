/*
 * Personal runtime (plan milestone 7): glue between the player and the /me endpoints.
 *  - play reporting: 'trackend' -> core/play-report.js (30 s / 50 %, batched)
 *  - queue sync: player changes -> debounced PUT /me/queue; flushed on pause, track change and page hide
 *  - resume: the stored queue is offered as "continue listening" on any device
 *  - remote change: a poll notices another device writing the queue ("playing on another device")
 * The logic lives in core/play-report.js and core/queue-sync.js; this file only wires it to the browser.
 */
import { reactive, watch } from '../vue.js';
import { Api, session } from '../api.js';
import { player, prefs } from '../player/index.js';
import { snapshot } from '../core/queue.js';
import { createPlayReporter } from '../core/play-report.js';
import { createQueueSync, buildPayload, resumeFromQueue, resumeSummary, remoteChange, newDeviceId } from '../core/queue-sync.js';
import { favorites } from './favorites.js';

export const personal = reactive({
  ready: false,
  resume: null,     // {track, count, positionSeconds, updated, data}: the stored queue offered for "continue listening"
  remote: null      // same shape: another device changed the stored queue
});

const POLL_MS = 8000;

function loadDeviceId() {
  let id = prefs.get('deviceId', null);
  if (!id) { id = newDeviceId(); prefs.set('deviceId', id); }
  return id;
}
export const deviceId = loadDeviceId();

let leaving = false;
let known = null;          // {updated, deviceId} of the stored queue as we last wrote or saw it
let etag = null;
let pollTimer = null;
let started = false;

const reporter = createPlayReporter({
  send: r => Api.request('POST', '/me/history', r, { keepalive: leaving })
});

const sync = createQueueSync({
  read() {
    const q = player.state.queue;
    return buildPayload(snapshot(q), player.state.position, deviceId, null);
  },
  async put(payload, options) {
    const meta = {};
    const stored = await Api.putQueue(payload, Object.assign({ meta: meta }, options || {}));
    if (stored && stored.updated) known = { updated: stored.updated, deviceId: deviceId };
    etag = meta.etag || null;
    return stored;
  }
});
sync.suspend(true);

// ---- player hooks ----

let reportedId = null;     // queue entry id whose play was already reported on pause

player.events.on('trackend', e => {
  if (!e.track) return;
  if (reportedId !== null && reportedId === player.state.loadedId) { reportedId = null; return; }   // counted on pause
  reporter.report({ path: e.track.path, playedSeconds: e.playedSeconds, durationSeconds: e.durationSeconds });
});
player.events.on('queuechange', () => {
  if (personal.resume) dismissResume();
  sync.schedule();
});
player.events.on('trackstart', () => { reportedId = null; sync.flush().catch(() => {}); });
player.events.on('state', e => {
  if (e.status === 'paused' || e.status === 'idle') {
    // pausing or stopping counts as a play (otherwise it would only count when the user leaves the track)
    const entry = player.current;
    const st = player.state;
    if (entry && st.loadedId === entry.id && reportedId !== entry.id
        && reporter.report({ path: entry.track.path, playedSeconds: st.position, durationSeconds: st.duration, force: true })) {
      reportedId = entry.id;
    }
    sync.flush().catch(() => {});
  }
});
player.events.on('time', () => { if (!personal.remote) sync.touch(); });   // no passive write over another device's queue

function leave() {
  leaving = true;
  const entry = player.current;
  const st = player.state;
  if (entry && st.loadedId === entry.id && reportedId !== entry.id && (st.status === 'playing' || st.status === 'paused')) {
    reporter.report({ path: entry.track.path, playedSeconds: st.position, durationSeconds: st.duration });
  }
  reporter.flush().catch(() => {});
  sync.flush({ keepalive: true, force: true }).catch(() => {});
}

if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      sync.flush({ keepalive: true }).catch(() => {});
      reporter.flush().catch(() => {});
    } else {
      leaving = false;
      poll();
    }
  });
  window.addEventListener('pagehide', leave);
  window.addEventListener('pageshow', () => { leaving = false; });
}

// ---- resume and remote changes ----

function offer(data) {
  const s = resumeSummary(data);
  return s ? Object.assign({ data: data }, s) : null;
}

export function dismissResume() {
  personal.resume = null;
  sync.suspend(false);
}

export function dismissRemote() { personal.remote = null; }

/** Starts the stored queue (resume card or "playing on another device" notice). */
export function continueFrom(offerObj) {
  const r = resumeFromQueue(offerObj.data);
  if (!r) return false;
  personal.resume = null;
  personal.remote = null;
  sync.suspend(false);
  player.setModes(r.shuffle, r.repeat);
  player.playTracks(r.tracks, r.index, r.positionSeconds);
  return true;
}

async function poll() {
  if (!personal.ready || !session.authenticated || (typeof document !== 'undefined' && document.hidden)) return;
  try {
    const r = await Api.queue(etag);
    if (r.notModified) return;
    etag = r.etag || null;
    const d = r.data;
    if (remoteChange(d, known, deviceId)) { personal.remote = offer(d); sync.cancel(); }
    if (d && d.updated) known = { updated: d.updated, deviceId: d.deviceId };
  } catch (e) { /* offline or signed out: try again at the next tick */ }
}

export async function startPersonal() {
  if (started) return;
  started = true;
  sync.suspend(true);
  favorites.load().catch(() => {});
  let offered = false;
  try {
    const r = await Api.queue();
    etag = r.etag || null;
    const d = r.data;
    if (d && d.updated) known = { updated: d.updated, deviceId: d.deviceId };
    if (!player.state.queue.items.length) {
      personal.resume = offer(d);
      offered = !!personal.resume;
    }
  } catch (e) { /* no stored queue yet */ }
  personal.ready = true;
  if (!offered) sync.suspend(false);
  pollTimer = setInterval(poll, POLL_MS);
}

export function stopPersonal() {
  started = false;
  personal.ready = false;
  personal.resume = null;
  personal.remote = null;
  known = null; etag = null;
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  sync.cancel();
  sync.suspend(true);
  reporter.clear();
  favorites.reset();
}

/** Call before the player is cleared on logout: saves the current state and stops further writes. */
export async function beforeLogout() {
  try { await reporter.flush(); await sync.flush({ force: true }); } catch (e) { /* the session may already be gone */ }
  sync.suspend(true);
}

/** Starts and stops the runtime with the sign-in state. */
export function installPersonalRuntime() {
  watch(() => session.authenticated && !session.mustChange, on => {
    if (on) startPersonal(); else stopPersonal();
  }, { immediate: true });
}
