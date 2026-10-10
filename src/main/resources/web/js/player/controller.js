/*
 * Player controller: ties the pure queue (core/queue.js) to the audio engine. Framework-free; the caller injects
 * the engine, a reactive wrapper, the stream URL builder and the prefetch hint, so it runs under node in tests.
 *
 * Events (controller.events.on):
 *   'trackstart'  {track}                                     a track begins (extension: history)
 *   'trackend'    {track, playedSeconds, durationSeconds, completed}   a track was left or finished
 *   'queuechange' {queue}                                     any change of items/index/shuffle/repeat (extension: queue sync)
 *   'state'       {status}                                    idle | loading | playing | paused
 *   'time'        {position, duration}
 */
import * as Q from '../core/queue.js';
import { createEmitter } from '../core/events.js';

export function createController(deps) {
  const engine = deps.engine;
  const prefs = deps.prefs;
  const events = createEmitter();
  const wrap = deps.reactive || (x => x);
  const rng = deps.rng || Math.random;

  const state = wrap({
    queue: Q.createQueue({ shuffle: prefs.get('shuffle', false), repeat: prefs.get('repeat', 'off') }),
    status: 'idle',
    position: 0,
    duration: 0,
    volume: prefs.get('volume', 1),
    muted: prefs.get('muted', false),
    error: '',
    loadedId: null
  });
  let maxPosition = 0;
  let errorStreak = 0;

  engine.setVolume(state.volume);
  engine.setMuted(state.muted);

  function current() { return Q.current(state.queue); }
  function setStatus(s) { if (state.status !== s) { state.status = s; events.emit('state', { status: s }); } }
  function setQueue(q) { state.queue = q; events.emit('queuechange', { queue: q }); }

  /** Reports the end of the loaded track (once) before another one replaces it. */
  function leaveTrack(completed) {
    const entry = state.queue.items.find(e => e.id === state.loadedId);
    if (!entry) return;
    events.emit('trackend', {
      track: entry.track, playedSeconds: completed ? Math.max(maxPosition, state.duration) : maxPosition,
      durationSeconds: state.duration || (entry.track.durationMillis || 0) / 1000, completed: !!completed
    });
    state.loadedId = null;
  }

  function startCurrent(startAt) {
    const entry = current();
    if (!entry) { stop(); return; }
    if (state.loadedId && state.loadedId !== entry.id) leaveTrack(false);
    state.loadedId = entry.id;
    state.position = startAt || 0;
    state.duration = (entry.track.durationMillis || 0) / 1000;
    maxPosition = 0;
    state.error = '';
    setStatus('loading');
    engine.play(deps.streamUrl(entry.track.path), startAt || 0);
    events.emit('trackstart', { track: entry.track });
    scheduleNext();
  }

  /** Preload (native) or prefetch hint (transcoded) for the track after the current one. */
  function scheduleNext() {
    const nxt = Q.peekNext(state.queue);
    if (!nxt) { engine.preload(null); return; }
    if (nxt.track.playability === 'transcoded') {
      engine.preload(null);
      if (deps.prefetch) Promise.resolve(deps.prefetch(nxt.track.path)).catch(() => {});
    } else {
      engine.preload(deps.streamUrl(nxt.track.path));
    }
  }

  function stop() {
    leaveTrack(false);
    engine.stop();
    state.position = 0;
    setStatus('idle');
  }

  engine.on('time', e => {
    state.position = e.position;
    if (e.duration) state.duration = e.duration;
    if (e.position > maxPosition) maxPosition = e.position;
    events.emit('time', e);
  });
  engine.on('playing', () => { errorStreak = 0; setStatus('playing'); });
  engine.on('paused', () => { if (state.status !== 'idle') setStatus('paused'); });
  engine.on('waiting', () => { if (state.status === 'playing') setStatus('loading'); });
  engine.on('blocked', () => setStatus('paused'));
  engine.on('ended', () => {
    leaveTrack(true);
    const r = Q.next(state.queue, true, rng);
    if (r.ended) { state.position = 0; setStatus('paused'); return; }
    setQueue(r.queue);
    startCurrent();
  });
  engine.on('error', () => {
    const entry = current();
    state.error = 'error';
    errorStreak++;
    const r = Q.next(state.queue, false, rng);
    if (!r.ended && errorStreak < state.queue.items.length && entry) {
      leaveTrack(false);
      setQueue(r.queue);
      startCurrent();
    } else {
      leaveTrack(false);
      setStatus('paused');
    }
  });

  const api = {
    state, events,
    get current() { return current(); },
    get hasNext() { return !!Q.peekNext(state.queue) || state.queue.repeat === 'one'; },

    /** Replaces the queue and plays from startIndex. */
    playTracks(tracks, startIndex, startAt) {
      if (!tracks.length) return;
      leaveTrack(false);
      setQueue(Q.setQueue(state.queue, tracks, startIndex || 0, rng));
      startCurrent(startAt || 0);
    },
    /** Sets shuffle and repeat without touching the items (resume of a stored queue). */
    setModes(shuffle, repeat) {
      let q = Q.setShuffle(state.queue, !!shuffle, rng);
      q = Q.setRepeat(q, repeat);
      prefs.set('shuffle', q.shuffle);
      prefs.set('repeat', q.repeat);
      setQueue(q);
    },
    /** Plays a track right now; the rest of the queue stays behind it. */
    playNow(track) {
      const wasEmpty = state.queue.items.length === 0;
      let q = Q.playNext(state.queue, [track], rng);
      q = Q.jump(q, wasEmpty ? 0 : state.queue.index + 1);
      leaveTrack(false);
      setQueue(q);
      startCurrent();
    },
    playNext(tracks) { setQueue(Q.playNext(state.queue, tracks, rng)); scheduleNextIfPlaying(); },
    enqueue(tracks) { setQueue(Q.enqueue(state.queue, tracks, rng)); scheduleNextIfPlaying(); },
    jump(i) {
      if (i < 0 || i >= state.queue.items.length) return;
      leaveTrack(false);
      setQueue(Q.jump(state.queue, i));
      startCurrent();
    },
    removeAt(i) {
      const removedCurrent = i === state.queue.index;
      const wasActive = state.status === 'playing' || state.status === 'loading';
      setQueue(Q.removeAt(state.queue, i));
      if (removedCurrent) {
        leaveTrack(false);
        if (!current()) { stop(); } else if (wasActive) { startCurrent(); } else { engine.stop(); setStatus('paused'); }
      } else {
        scheduleNextIfPlaying();
      }
    },
    move(i, direction) { setQueue(Q.move(state.queue, i, direction)); scheduleNextIfPlaying(); },
    clear() { setQueue(Q.clear(state.queue, false)); stop(); },

    toggle() {
      const entry = current();
      if (!entry) return;
      if (state.loadedId !== entry.id) { startCurrent(); return; }
      if (state.status === 'playing' || state.status === 'loading') { engine.pause(); setStatus('paused'); } else { engine.resume(); }
    },
    pause() { if (state.status === 'playing' || state.status === 'loading') { engine.pause(); setStatus('paused'); } },
    resume() { const entry = current(); if (entry && state.loadedId === entry.id) engine.resume(); else startCurrent(); },
    next() {
      const r = Q.next(state.queue, false, rng);
      if (r.ended) return;
      leaveTrack(false);
      setQueue(r.queue);
      startCurrent();
    },
    prev() {
      const r = Q.prev(state.queue, state.position);
      if (r.restart) {
        if (state.loadedId && current() && state.loadedId === current().id) { engine.seek(0); state.position = 0; } else startCurrent();
        return;
      }
      leaveTrack(false);
      setQueue(r.queue);
      startCurrent();
    },
    seek(seconds) {
      const max = state.duration || Infinity;
      const s = Math.min(Math.max(0, seconds), max);
      engine.seek(s);
      state.position = s;
    },
    seekBy(delta) { api.seek(state.position + delta); },
    setVolume(v) {
      state.volume = Math.min(1, Math.max(0, v));
      if (state.volume > 0 && state.muted) { state.muted = false; engine.setMuted(false); prefs.set('muted', false); }
      engine.setVolume(state.volume);
      prefs.set('volume', state.volume);
    },
    toggleMute() { state.muted = !state.muted; engine.setMuted(state.muted); prefs.set('muted', state.muted); },
    toggleShuffle() {
      setQueue(Q.setShuffle(state.queue, !state.queue.shuffle, rng));
      prefs.set('shuffle', state.queue.shuffle);
      scheduleNextIfPlaying();
    },
    cycleRepeat() {
      setQueue(Q.setRepeat(state.queue, Q.nextRepeat(state.queue.repeat)));
      prefs.set('repeat', state.queue.repeat);
      scheduleNextIfPlaying();
    }
  };

  function scheduleNextIfPlaying() {
    if (state.loadedId) scheduleNext();
  }

  return api;
}
