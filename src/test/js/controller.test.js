import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createController } from '../../main/resources/web/js/player/controller.js';
import { createPrefs } from '../../main/resources/web/js/core/prefs.js';
import { createEmitter } from '../../main/resources/web/js/core/events.js';
import { mulberry32 } from '../../main/resources/web/js/core/shuffle.js';

function fakeEngine() {
  const bus = createEmitter();
  const calls = [];
  return {
    bus, calls,
    on: bus.on,
    play(url, at) { calls.push(['play', url, at]); },
    preload(url) { calls.push(['preload', url]); },
    resume() { calls.push(['resume']); }, pause() { calls.push(['pause']); }, stop() { calls.push(['stop']); },
    seek(s) { calls.push(['seek', s]); },
    setVolume(v) { calls.push(['volume', v]); }, setMuted(m) { calls.push(['muted', m]); },
    setRate(r) { calls.push(['rate', r]); }
  };
}

const tr = (n, extra) => Object.assign({ path: 'M/' + n + '.mp3', title: n, durationMillis: 60000, playability: 'native' }, extra);

function make() {
  const engine = fakeEngine();
  const prefetched = [];
  const store = new Map();
  const prefs = createPrefs({ getItem: k => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, v) });
  const player = createController({
    engine, prefs, rng: mulberry32(1), streamUrl: p => '/s?path=' + p, prefetch: p => { prefetched.push(p); return Promise.resolve(); }
  });
  const log = [];
  player.events.on('trackstart', e => log.push('start:' + e.track.title));
  player.events.on('trackend', e => log.push('end:' + e.track.title + ':' + e.completed));
  return { engine, player, prefetched, log, store };
}

const urls = e => e.calls.filter(c => c[0] === 'play').map(c => c[1]);

test('playTracks starts the chosen track and preloads a native successor', () => {
  const { engine, player, log } = make();
  player.playTracks([tr('a'), tr('b'), tr('c')], 1);
  assert.deepEqual(urls(engine), ['/s?path=M/b.mp3']);
  assert.deepEqual(engine.calls.filter(c => c[0] === 'preload').pop(), ['preload', '/s?path=M/c.mp3']);
  assert.equal(player.state.status, 'loading');
  assert.deepEqual(log, ['start:b']);
});

test('a transcoded successor gets a prefetch hint instead of a preload', () => {
  const { engine, player, prefetched } = make();
  player.playTracks([tr('a'), tr('b', { playability: 'transcoded' })], 0);
  assert.deepEqual(prefetched, ['M/b.mp3']);
  assert.deepEqual(engine.calls.filter(c => c[0] === 'preload').pop(), ['preload', null]);
});

test('ended advances, reports the finished track, and stops after the last one', () => {
  const { engine, player, log } = make();
  player.playTracks([tr('a'), tr('b')], 0);
  engine.bus.emit('playing');
  engine.bus.emit('ended');
  assert.equal(player.current.track.title, 'b');
  assert.deepEqual(log, ['start:a', 'end:a:true', 'start:b']);
  engine.bus.emit('ended');
  assert.equal(player.state.status, 'paused');
  assert.equal(player.current.track.title, 'b');
  assert.deepEqual(log.slice(-1), ['end:b:true']);
});

test('repeat one replays the same track, repeat all wraps', () => {
  const { engine, player } = make();
  player.playTracks([tr('a'), tr('b')], 1);
  player.cycleRepeat();                       // all
  engine.bus.emit('ended');
  assert.equal(player.current.track.title, 'a');
  player.cycleRepeat();                       // one
  engine.bus.emit('ended');
  assert.equal(player.current.track.title, 'a');
  assert.equal(urls(engine).length, 3);
  assert.equal(player.state.queue.repeat, 'one');
});

test('toggle pauses, resumes and starts a queued but not yet loaded track', () => {
  const { engine, player } = make();
  player.enqueue([tr('a')]);
  assert.equal(urls(engine).length, 0);       // enqueue alone does not start playback
  player.toggle();
  assert.equal(urls(engine).length, 1);
  engine.bus.emit('playing');
  player.toggle();
  assert.equal(player.state.status, 'paused');
  player.toggle();
  assert.deepEqual(engine.calls.pop(), ['resume']);
});

test('playNow slots the track after the current one and keeps the rest', () => {
  const { player } = make();
  player.playTracks([tr('a'), tr('b')], 0);
  player.playNow(tr('x'));
  assert.equal(player.current.track.title, 'x');
  assert.deepEqual(player.state.queue.items.map(e => e.track.title), ['a', 'x', 'b']);
});

test('removing the playing track starts its successor, clearing stops', () => {
  const { engine, player } = make();
  player.playTracks([tr('a'), tr('b'), tr('c')], 1);
  engine.bus.emit('playing');
  player.removeAt(1);
  assert.equal(player.current.track.title, 'c');
  assert.equal(urls(engine).pop(), '/s?path=M/c.mp3');
  player.clear();
  assert.equal(player.state.status, 'idle');
  assert.equal(player.current, null);
});

test('previous restarts the track after 3 seconds, else goes back', () => {
  const { engine, player } = make();
  player.playTracks([tr('a'), tr('b')], 1);
  engine.bus.emit('time', { position: 10, duration: 60 });
  player.prev();
  assert.deepEqual(engine.calls.filter(c => c[0] === 'seek').pop(), ['seek', 0]);
  assert.equal(player.current.track.title, 'b');
  engine.bus.emit('time', { position: 1, duration: 60 });
  player.prev();
  assert.equal(player.current.track.title, 'a');
});

test('a playback error skips to the next track, and gives up when all fail', () => {
  const { engine, player } = make();
  player.playTracks([tr('a'), tr('b'), tr('c')], 0);
  engine.bus.emit('error', { code: 4 });
  assert.equal(player.current.track.title, 'b');
  engine.bus.emit('error', { code: 4 });
  assert.equal(player.current.track.title, 'c');
  engine.bus.emit('error', { code: 4 });
  assert.equal(player.state.status, 'paused');
  assert.equal(player.state.error, 'error');
});

test('trackend carries the played position when the track is left early', () => {
  const { engine, player } = make();
  const ends = [];
  player.events.on('trackend', e => ends.push(e));
  player.playTracks([tr('a'), tr('b')], 0);
  engine.bus.emit('time', { position: 42, duration: 60 });
  player.next();
  assert.equal(ends.length, 1);
  assert.equal(ends[0].completed, false);
  assert.equal(ends[0].playedSeconds, 42);
  assert.equal(ends[0].durationSeconds, 60);
});

test('volume, mute, shuffle and repeat are persisted', () => {
  const { player, store, engine } = make();
  player.setVolume(0.4);
  player.toggleMute();
  player.toggleShuffle();
  player.cycleRepeat();
  assert.equal(store.get('kd.volume'), '0.4');
  assert.equal(store.get('kd.muted'), 'true');
  assert.equal(store.get('kd.shuffle'), 'true');
  assert.equal(store.get('kd.repeat'), '"all"');
  assert.deepEqual(engine.calls.filter(c => c[0] === 'volume').pop(), ['volume', 0.4]);
  player.setVolume(0.7);                       // raising the volume unmutes
  assert.equal(player.state.muted, false);
});

test('queuechange is emitted for every queue edit', () => {
  const { player } = make();
  let n = 0;
  player.events.on('queuechange', () => n++);
  player.enqueue([tr('a'), tr('b')]);
  player.move(0, 'down');
  player.removeAt(0);
  player.toggleShuffle();
  assert.equal(n, 4);
});

test('playTracks can start inside a track (resume) and setModes restores shuffle and repeat', () => {
  const { engine, player } = make();
  player.setModes(true, 'all');
  assert.equal(player.state.queue.shuffle, true);
  assert.equal(player.state.queue.repeat, 'all');
  player.playTracks([tr('a'), tr('b'), tr('c')], 1, 42.5);
  assert.deepEqual(engine.calls.filter(c => c[0] === 'play').pop(), ['play', '/s?path=M/b.mp3', 42.5]);
  assert.equal(player.state.position, 42.5);
  assert.equal(player.state.queue.order[0], player.current.id, 'the resumed track leads the shuffle order');
  player.setModes(false, 'bogus');
  assert.equal(player.state.queue.shuffle, false);
  assert.equal(player.state.queue.repeat, 'all', 'an unknown repeat mode is ignored');
});
