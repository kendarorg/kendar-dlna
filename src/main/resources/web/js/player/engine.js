/*
 * Audio engine: two <audio> elements. One plays, the other preloads the next track; when the preloaded URL is
 * started the elements swap roles. Emits: 'time' {position, duration}, 'playing', 'paused', 'waiting', 'ended',
 * 'error' {code}, 'blocked' (autoplay refused by the browser).
 */
import { createEmitter } from '../core/events.js';

export function createEngine(createAudio) {
  const make = createAudio || (() => new Audio());
  const els = [make(), make()];
  const urls = [null, null];       // what each element was given (not normalized by the browser)
  let primary = 0;
  const bus = createEmitter();
  let volume = 1;
  let muted = false;

  els.forEach((el, i) => {
    el.preload = 'auto';
    const mine = () => i === primary;
    const time = () => {
      if (mine()) bus.emit('time', { position: el.currentTime || 0, duration: Number.isFinite(el.duration) ? el.duration : 0 });
    };
    el.addEventListener('timeupdate', time);
    el.addEventListener('durationchange', time);
    el.addEventListener('playing', () => { if (mine()) bus.emit('playing'); });
    el.addEventListener('play', () => { if (mine()) bus.emit('playing'); });
    el.addEventListener('pause', () => { if (mine() && !el.ended) bus.emit('paused'); });
    el.addEventListener('waiting', () => { if (mine()) bus.emit('waiting'); });
    el.addEventListener('ended', () => { if (mine()) bus.emit('ended'); });
    el.addEventListener('error', () => {
      if (mine() && urls[i]) bus.emit('error', { code: el.error ? el.error.code : 0 });
    });
  });

  function applyVolume() {
    for (const el of els) { el.volume = volume; el.muted = muted; }
  }

  function startElement(el) {
    const p = el.play();
    if (p && typeof p.catch === 'function') {
      p.catch(err => {
        if (err && err.name === 'NotAllowedError') bus.emit('blocked');
        else if (err && err.name !== 'AbortError') bus.emit('error', { code: 0 });
      });
    }
  }

  function clear(i) {
    const el = els[i];
    urls[i] = null;
    try { el.pause(); el.removeAttribute('src'); el.load(); } catch (e) { /* ignore */ }
  }

  return {
    on: bus.on,
    /** Starts url on the primary element (or on the preloaded one when it matches). */
    play(url, startAt) {
      const other = 1 - primary;
      if (urls[other] === url) {
        clear(primary);
        primary = other;
      } else if (urls[primary] !== url) {
        if (urls[other] === url) clear(other);
        urls[primary] = url;
        els[primary].src = url;
      }
      const el = els[primary];
      applyVolume();
      try { el.currentTime = startAt || 0; } catch (e) { /* not seekable yet */ }
      startElement(el);
    },
    /** Preloads url on the idle element; null cancels. */
    preload(url) {
      const idle = 1 - primary;
      if (!url) { if (urls[idle]) clear(idle); return; }
      if (urls[idle] === url) return;
      urls[idle] = url;
      els[idle].src = url;
      els[idle].load();
    },
    resume() { startElement(els[primary]); },
    pause() { els[primary].pause(); },
    stop() { clear(primary); clear(1 - primary); },
    seek(seconds) { try { els[primary].currentTime = Math.max(0, seconds); } catch (e) { /* ignore */ } },
    setVolume(v) { volume = Math.min(1, Math.max(0, v)); applyVolume(); },
    setMuted(m) { muted = !!m; applyVolume(); },
    get position() { return els[primary].currentTime || 0; },
    get paused() { return els[primary].paused; }
  };
}
