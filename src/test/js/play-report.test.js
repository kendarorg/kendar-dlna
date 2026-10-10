import { test } from 'node:test';
import assert from 'node:assert/strict';
import { qualifies, createPlayReporter } from '../../main/resources/web/js/core/play-report.js';

/** Manual timers: tick() runs everything that is due. */
function clock() {
  const timers = new Map();
  let id = 0;
  return {
    setTimer: (fn, ms) => { timers.set(++id, { fn, ms }); return id; },
    clearTimer: h => timers.delete(h),
    count: () => timers.size,
    delays: () => Array.from(timers.values()).map(t => t.ms),
    async tick() {
      for (const [k, t] of Array.from(timers)) { timers.delete(k); t.fn(); }
      await new Promise(r => setImmediate(r));
    }
  };
}

test('a play counts after 30 seconds or half of the track', () => {
  assert.equal(qualifies(30, 400), true);
  assert.equal(qualifies(29.9, 400), false);
  assert.equal(qualifies(30, 100), true);
  assert.equal(qualifies(29, 100), false);     // a long track needs the 30 s
  assert.equal(qualifies(10, 20), true);       // a 20 s jingle played to the half
  assert.equal(qualifies(9.9, 20), false);
  assert.equal(qualifies(10, 0), false);       // unknown duration: only the 30 s rule
  assert.equal(qualifies(0, 0), false);
  assert.equal(qualifies(undefined, 5), false);
});

test('only qualifying plays are queued and they leave in one batch after the flush delay', async () => {
  const c = clock();
  const sent = [];
  const rep = createPlayReporter({ send: async r => { sent.push(r); }, setTimer: c.setTimer, clearTimer: c.clearTimer, flushMs: 3000 });
  assert.equal(rep.report({ path: 'M/skip.mp3', playedSeconds: 5, durationSeconds: 200 }), false);
  assert.equal(rep.report({ path: 'M/a.mp3', playedSeconds: 61.4, durationSeconds: 120 }), true);
  assert.equal(rep.report({ path: 'M/b.mp3', playedSeconds: 100, durationSeconds: 100 }), true);
  assert.equal(rep.report({ path: '', playedSeconds: 100, durationSeconds: 100 }), false);
  assert.equal(rep.pending(), 2);
  assert.deepEqual(c.delays(), [3000], 'one timer for the whole batch');
  assert.deepEqual(sent, []);
  await c.tick();
  assert.deepEqual(sent, [
    { path: 'M/a.mp3', playedSeconds: 61, durationSeconds: 120 },
    { path: 'M/b.mp3', playedSeconds: 100, durationSeconds: 100 }
  ]);
  assert.equal(rep.pending(), 0);
  assert.equal(rep.stats().sent, 2);
});

test('flush sends at once (page hide) and cancels the timer', async () => {
  const c = clock();
  const sent = [];
  const rep = createPlayReporter({ send: async r => { sent.push(r.path); }, setTimer: c.setTimer, clearTimer: c.clearTimer });
  rep.report({ path: 'M/a.mp3', playedSeconds: 40, durationSeconds: 90 });
  await rep.flush();
  assert.deepEqual(sent, ['M/a.mp3']);
  assert.equal(c.count(), 0);
});

test('a 429 keeps the plays and retries after the advertised delay', async () => {
  const c = clock();
  let limited = true;
  const sent = [];
  const rep = createPlayReporter({
    send: async r => {
      if (limited) throw { status: 429, details: { retryAfterSeconds: 7 } };
      sent.push(r.path);
    },
    setTimer: c.setTimer, clearTimer: c.clearTimer
  });
  rep.report({ path: 'M/a.mp3', playedSeconds: 40, durationSeconds: 90 });
  await rep.flush();
  assert.equal(rep.pending(), 1);
  assert.deepEqual(c.delays(), [7000]);
  limited = false;
  await c.tick();
  assert.deepEqual(sent, ['M/a.mp3']);
  assert.equal(rep.pending(), 0);
});

test('refused plays are dropped, network errors are retried a few times then dropped', async () => {
  const c = clock();
  const calls = [];
  const rep = createPlayReporter({
    send: async r => { calls.push(r.path); throw r.path === 'M/forbidden.mp3' ? { status: 403 } : new TypeError('network'); },
    setTimer: c.setTimer, clearTimer: c.clearTimer, maxAttempts: 3
  });
  rep.report({ path: 'M/forbidden.mp3', playedSeconds: 40, durationSeconds: 90 });
  await rep.flush();
  assert.equal(rep.pending(), 0);
  assert.equal(rep.stats().dropped, 1);

  rep.report({ path: 'M/net.mp3', playedSeconds: 40, durationSeconds: 90 });
  await rep.flush();
  assert.equal(rep.pending(), 1);
  await c.tick();
  await c.tick();
  assert.equal(rep.pending(), 0, 'dropped after maxAttempts');
  assert.equal(calls.filter(p => p === 'M/net.mp3').length, 3);
});

test('the queue is bounded and clear() forgets everything', () => {
  const c = clock();
  const rep = createPlayReporter({ send: async () => {}, setTimer: c.setTimer, clearTimer: c.clearTimer, maxQueue: 3 });
  for (let i = 0; i < 5; i++) rep.report({ path: 'M/' + i, playedSeconds: 40, durationSeconds: 60 });
  assert.equal(rep.pending(), 3);
  assert.equal(rep.stats().dropped, 2);
  rep.clear();
  assert.equal(rep.pending(), 0);
  assert.equal(c.count(), 0);
});
