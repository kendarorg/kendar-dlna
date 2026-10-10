import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildPayload, createQueueSync, resumeFromQueue, resumeSummary, remoteChange, formatPosition, newDeviceId
} from '../../main/resources/web/js/core/queue-sync.js';
import { createQueue, setQueue, snapshot } from '../../main/resources/web/js/core/queue.js';

function clock() {
  const timers = new Map();
  let id = 0;
  return {
    setTimer: (fn, ms) => { timers.set(++id, { fn, ms }); return id; },
    clearTimer: h => timers.delete(h),
    count: () => timers.size,
    delays: () => Array.from(timers.values()).map(t => t.ms),
    async fire() { for (const [k, t] of Array.from(timers)) { timers.delete(k); t.fn(); } await new Promise(r => setImmediate(r)); }
  };
}

const track = n => ({ path: 'M/' + n + '.mp3', title: n });
const payload = (items, index, pos) => ({ items, index, positionSeconds: pos, shuffle: false, repeat: 'off', source: null, deviceId: 'd1' });

test('buildPayload turns a queue snapshot into the PUT body', () => {
  const q = setQueue(createQueue({ repeat: 'all' }), [track('a'), track('b')], 1, Math.random);
  const p = buildPayload(snapshot(q), 12.345, 'dev', { kind: 'album' });
  assert.deepEqual(p, { items: ['M/a.mp3', 'M/b.mp3'], index: 1, positionSeconds: 12.3, shuffle: false, repeat: 'all', source: { kind: 'album' }, deviceId: 'dev' });
  assert.equal(buildPayload({ items: [], index: -1, shuffle: false, repeat: 'off' }, 0, 'x').index, 0);
});

test('schedule debounces: the last call wins and only one write happens', async () => {
  const c = clock();
  const puts = [];
  let state = payload(['a'], 0, 0);
  const sync = createQueueSync({ read: () => state, put: async p => { puts.push(p); }, setTimer: c.setTimer, clearTimer: c.clearTimer, debounceMs: 4000 });
  sync.schedule();
  state = payload(['a', 'b'], 0, 0);
  sync.schedule();
  state = payload(['a', 'b', 'c'], 0, 0);
  sync.schedule();
  assert.equal(c.count(), 1);
  assert.deepEqual(c.delays(), [4000]);
  await c.fire();
  assert.equal(puts.length, 1);
  assert.deepEqual(puts[0].items, ['a', 'b', 'c']);
});

test('flush writes immediately (pause, track change, page hide) and cancels the pending debounce', async () => {
  const c = clock();
  const puts = [];
  const opts = [];
  const sync = createQueueSync({ read: () => payload(['a'], 0, 31), put: async (p, o) => { puts.push(p); opts.push(o); }, setTimer: c.setTimer, clearTimer: c.clearTimer });
  sync.schedule();
  await sync.flush({ keepalive: true });
  assert.equal(c.count(), 0);
  assert.equal(puts.length, 1);
  assert.deepEqual(opts[0], { keepalive: true });
});

test('an unchanged state is not written again; a moved position or other change is', async () => {
  const c = clock();
  const puts = [];
  let state = payload(['a', 'b'], 0, 10);
  const sync = createQueueSync({ read: () => state, put: async p => { puts.push(p); }, setTimer: c.setTimer, clearTimer: c.clearTimer, minMoveSeconds: 5 });
  await sync.flush();
  await sync.flush();
  assert.equal(puts.length, 1, 'same state');
  state = payload(['a', 'b'], 0, 12);
  await sync.flush();
  assert.equal(puts.length, 1, 'position moved less than 5 s');
  state = payload(['a', 'b'], 0, 40);
  await sync.flush();
  assert.equal(puts.length, 2);
  state = payload(['a', 'b'], 1, 40);
  await sync.flush();
  assert.equal(puts.length, 3, 'another track is current');
  await sync.flush({ force: true });
  assert.equal(puts.length, 4, 'force writes anyway');
});

test('touch arms one progress write after the max interval and never postpones a pending one', async () => {
  const c = clock();
  let pos = 0;
  const puts = [];
  const sync = createQueueSync({ read: () => payload(['a'], 0, pos), put: async p => { puts.push(p.positionSeconds); }, setTimer: c.setTimer, clearTimer: c.clearTimer, maxIntervalMs: 15000 });
  sync.touch(); sync.touch(); sync.touch();
  assert.equal(c.count(), 1);
  assert.deepEqual(c.delays(), [15000]);
  pos = 30;
  await c.fire();
  assert.deepEqual(puts, [30]);
});

test('suspended: nothing is written until resumed (before the stored queue was offered)', async () => {
  const c = clock();
  const puts = [];
  const sync = createQueueSync({ read: () => payload([], 0, 0), put: async p => { puts.push(p); }, setTimer: c.setTimer, clearTimer: c.clearTimer });
  sync.suspend(true);
  sync.schedule(); sync.touch();
  assert.equal(c.count(), 0);
  assert.equal(await sync.flush(), null);
  assert.equal(puts.length, 0);
  sync.suspend(false);
  await sync.flush();
  assert.equal(puts.length, 1);
});

test('a failed write is retried at the next change', async () => {
  const c = clock();
  let fail = true;
  const puts = [];
  const sync = createQueueSync({ read: () => payload(['a'], 0, 0), put: async p => { if (fail) throw new Error('offline'); puts.push(p); }, setTimer: c.setTimer, clearTimer: c.clearTimer });
  await assert.rejects(sync.flush(), /offline/);
  fail = false;
  await sync.flush();
  assert.equal(puts.length, 1);
});

test('known() marks a server state as already stored', async () => {
  const puts = [];
  const sync = createQueueSync({ read: () => payload(['a'], 0, 5), put: async p => { puts.push(p); } });
  sync.known(payload(['a'], 0, 5));
  await sync.flush();
  assert.equal(puts.length, 0);
});

const entry = (i, status, name) => (status === 'ok' ? { index: i, path: 'M/' + name, status: 'ok', track: track(name) } : { index: i, path: 'M/' + name, status: status });

test('resumeFromQueue: playable entries only, index and position carried over', () => {
  const stored = { items: [entry(0, 'ok', 'a'), entry(1, 'missing', 'gone'), entry(2, 'ok', 'c'), entry(3, 'ok', 'd')], index: 2, positionSeconds: 71.5, shuffle: true, repeat: 'all' };
  const r = resumeFromQueue(stored);
  assert.deepEqual(r.tracks.map(t => t.title), ['a', 'c', 'd']);
  assert.equal(r.index, 1);
  assert.equal(r.positionSeconds, 71.5);
  assert.equal(r.shuffle, true);
  assert.equal(r.repeat, 'all');
});

test('resumeFromQueue: when the current entry is gone, continue at the next playable one from its start', () => {
  const stored = { items: [entry(0, 'ok', 'a'), entry(1, 'missing', 'gone'), entry(2, 'ok', 'c')], index: 1, positionSeconds: 99, repeat: 'off' };
  const r = resumeFromQueue(stored);
  assert.equal(r.tracks[r.index].title, 'c');
  assert.equal(r.positionSeconds, 0);
  const tail = resumeFromQueue({ items: [entry(0, 'ok', 'a'), entry(1, 'missing', 'gone')], index: 1, positionSeconds: 5 });
  assert.equal(tail.index, 0);
});

test('resumeFromQueue / resumeSummary: nothing playable means nothing to offer', () => {
  assert.equal(resumeFromQueue(null), null);
  assert.equal(resumeFromQueue({ items: [] }), null);
  assert.equal(resumeFromQueue({ items: [entry(0, 'missing', 'x')], index: 0 }), null);
  assert.equal(resumeSummary({ items: [] }), null);
  const s = resumeSummary({ items: [entry(0, 'ok', 'a'), entry(1, 'ok', 'b')], index: 1, positionSeconds: 8, updated: '2026-10-09T10:00:00Z' });
  assert.equal(s.track.title, 'b');
  assert.equal(s.count, 2);
  assert.equal(s.positionSeconds, 8);
});

test('remoteChange: another device wrote the queue since we last looked', () => {
  const stored = { updated: 't2', deviceId: 'phone', items: [entry(0, 'ok', 'a')] };
  assert.equal(remoteChange(stored, { updated: 't1', deviceId: 'web' }, 'web'), true);
  assert.equal(remoteChange(stored, { updated: 't2', deviceId: 'phone' }, 'web'), false, 'already seen');
  assert.equal(remoteChange({ updated: 't3', deviceId: 'web', items: [entry(0, 'ok', 'a')] }, { updated: 't2' }, 'web'), false, 'our own write');
  assert.equal(remoteChange(Object.assign({}, stored, { items: [] }), null, 'web'), false, 'an empty queue is nothing to take over');
  assert.equal(remoteChange({ updated: null, deviceId: null, items: [] }, null, 'web'), false);
  assert.equal(remoteChange(null, null, 'web'), false);
  assert.equal(remoteChange(stored, null, 'web'), true);
});

test('formatPosition and newDeviceId', () => {
  assert.equal(formatPosition(0), '0:00');
  assert.equal(formatPosition(65.9), '1:05');
  assert.equal(formatPosition(3725), '62:05');
  assert.equal(formatPosition(-4), '0:00');
  const id = newDeviceId(() => 0.5);
  assert.match(id, /^web-[0-9a-z]{12}$/);
  assert.notEqual(newDeviceId(), newDeviceId());
});
