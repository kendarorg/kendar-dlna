import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as Q from '../../main/resources/web/js/core/queue.js';
import { mulberry32 } from '../../main/resources/web/js/core/shuffle.js';

const tr = n => ({ path: 'M/' + n + '.mp3', title: n, durationMillis: 1000 });
const names = q => q.items.map(e => e.track.title).join('');
const cur = q => (Q.current(q) || { track: { title: null } }).track.title;
const rng = () => mulberry32(42);

function build(letters, start, opts) {
  return Q.setQueue(Q.createQueue(opts), letters.split('').map(tr), start || 0, rng());
}

test('setQueue sets items and current', () => {
  const q = build('abc', 1);
  assert.equal(names(q), 'abc');
  assert.equal(cur(q), 'b');
  assert.equal(Q.setQueue(Q.createQueue(), [], 0).index, -1);
});

test('operations never mutate their input', () => {
  const q = build('abc');
  const copy = JSON.stringify(q);
  Q.enqueue(q, [tr('d')]); Q.playNext(q, [tr('d')]); Q.removeAt(q, 0); Q.move(q, 0, 'down'); Q.clear(q);
  Q.jump(q, 2); Q.next(q); Q.prev(q, 0); Q.setShuffle(q, true, rng()); Q.setRepeat(q, 'all');
  assert.equal(JSON.stringify(q), copy);
});

test('the same track can be queued twice with distinct entry ids', () => {
  const q = Q.enqueue(Q.enqueue(Q.createQueue(), [tr('a')]), [tr('a')]);
  assert.equal(q.items.length, 2);
  assert.notEqual(q.items[0].id, q.items[1].id);
});

test('enqueue on an empty queue selects the first track without moving others', () => {
  let q = Q.enqueue(Q.createQueue(), [tr('a'), tr('b')]);
  assert.equal(q.index, 0);
  q = Q.enqueue(q, [tr('c')]);
  assert.equal(names(q), 'abc');
  assert.equal(cur(q), 'a');
});

test('playNext inserts after the current track, in order', () => {
  let q = build('abc', 1);
  q = Q.playNext(q, [tr('x'), tr('y')]);
  assert.equal(names(q), 'abxyc');
  assert.equal(cur(q), 'b');
  assert.equal(Q.next(q).queue.items[Q.next(q).queue.index].track.title, 'x');
});

test('remove before, at and after the current index', () => {
  let q = build('abcd', 2);
  q = Q.removeAt(q, 0);
  assert.equal(names(q), 'bcd'); assert.equal(cur(q), 'c');
  q = Q.removeAt(q, 2);
  assert.equal(cur(q), 'c');
  q = Q.removeAt(q, 1);                    // the current one: its successor (none: the new last) becomes current
  assert.equal(names(q), 'b'); assert.equal(cur(q), 'b');
  q = Q.removeAt(q, 0);
  assert.equal(q.index, -1); assert.equal(q.items.length, 0);
  assert.equal(Q.removeAt(q, 5), q);
});

test('removing the current track selects its successor', () => {
  const q = Q.removeAt(build('abc', 1), 1);
  assert.equal(names(q), 'ac'); assert.equal(cur(q), 'c');
});

test('move keeps the current track current', () => {
  let q = build('abcd', 1);
  q = Q.move(q, 1, 'down');
  assert.equal(names(q), 'acbd'); assert.equal(cur(q), 'b');
  q = Q.move(q, 3, 'top');
  assert.equal(names(q), 'dacb'); assert.equal(cur(q), 'b');
  q = Q.move(q, 0, 'bottom');
  assert.equal(names(q), 'acbd'); assert.equal(cur(q), 'b');
  q = Q.move(q, 2, 'up');
  assert.equal(names(q), 'abcd'); assert.equal(cur(q), 'b');
  assert.equal(Q.move(q, 9, 'up'), q);
});

test('clear empties the queue, keepCurrent leaves the playing track', () => {
  const q = build('abc', 1);
  assert.equal(Q.clear(q).items.length, 0);
  assert.equal(Q.clear(q).index, -1);
  const k = Q.clear(q, true);
  assert.equal(names(k), 'b'); assert.equal(k.index, 0);
});

test('next walks the queue and ends at the last track with repeat off', () => {
  let q = build('ab');
  let r = Q.next(q); assert.equal(cur(r.queue), 'b'); assert.equal(r.ended, false);
  r = Q.next(r.queue); assert.equal(r.ended, true); assert.equal(cur(r.queue), 'b');
});

test('repeat all wraps around, repeat one replays on auto only', () => {
  let q = Q.setRepeat(build('ab', 1), 'all');
  assert.equal(cur(Q.next(q).queue), 'a');
  q = Q.setRepeat(q, 'one');
  const auto = Q.next(q, true);
  assert.equal(auto.replay, true); assert.equal(cur(auto.queue), 'b');
  const manual = Q.next(q, false);
  assert.equal(manual.ended, true);       // manual next at the end with repeat one: nothing after b
  assert.equal(Q.next(Q.createQueue()).ended, true);
});

test('prev restarts after 3 seconds, else goes back, wraps with repeat all', () => {
  let q = build('abc', 1);
  assert.equal(Q.prev(q, 10).restart, true);
  assert.equal(cur(Q.prev(q, 1).queue), 'a');
  q = build('abc', 0);
  assert.equal(Q.prev(q, 0).restart, true);
  assert.equal(cur(Q.prev(Q.setRepeat(q, 'all'), 0).queue), 'c');
});

test('peekNext follows the same rules as next', () => {
  let q = build('abc', 1);
  assert.equal(Q.peekNext(q).track.title, 'c');
  assert.equal(Q.peekNext(build('abc', 2)), null);
  assert.equal(Q.peekNext(Q.setRepeat(build('abc', 2), 'all')).track.title, 'a');
  assert.equal(Q.peekNext(Q.setRepeat(q, 'one')), null);
  assert.equal(Q.peekNext(Q.createQueue()), null);
});

test('shuffle plays every track exactly once, starting with the current one', () => {
  const letters = 'abcdefgh';
  let q = Q.setShuffle(build(letters, 3), true, rng());
  assert.equal(q.order.length, letters.length);
  assert.equal(q.order[0], q.items[3].id);
  const seen = [cur(q)];
  for (;;) {
    const r = Q.next(q, true, rng());
    if (r.ended) break;
    q = r.queue; seen.push(cur(q));
  }
  assert.equal(seen.length, letters.length);
  assert.equal(new Set(seen).size, letters.length);
});

test('shuffle prev goes back through the played order', () => {
  let q = Q.setShuffle(build('abcdef', 0), true, rng());
  const first = cur(q);
  q = Q.next(q, false, rng()).queue;
  const second = cur(q);
  assert.notEqual(first, second);
  assert.equal(cur(Q.prev(q, 0).queue), first);
});

test('shuffle with repeat all reshuffles and never repeats the track just played', () => {
  let q = Q.setRepeat(Q.setShuffle(build('abc', 0), true, rng()), 'all');
  let last = cur(q);
  for (let i = 0; i < 12; i++) {
    const r = Q.next(q, true, mulberry32(i));
    assert.equal(r.ended, false);
    q = r.queue;
    if (i % 3 === 2) assert.notEqual(cur(q), last);
    last = cur(q);
  }
});

test('shuffle stays consistent through enqueue, playNext, remove, jump and move', () => {
  let q = Q.setShuffle(build('abcd', 0), true, rng());
  q = Q.enqueue(q, [tr('e')], rng());
  q = Q.playNext(q, [tr('x')], rng());
  assert.equal(Q.peekNext(q).track.title, 'x');
  q = Q.removeAt(q, 1);
  q = Q.move(q, 0, 'bottom');
  q = Q.jump(q, 2);
  assert.equal(q.order.length, q.items.length);
  assert.deepEqual(q.order.slice().sort(), q.items.map(e => e.id).sort());
  assert.equal(q.items[q.index].id, Q.current(q).id);
});

test('turning shuffle off clears the order and keeps the current track', () => {
  let q = Q.setShuffle(build('abc', 2), true, rng());
  q = Q.setShuffle(q, false);
  assert.deepEqual(q.order, []);
  assert.equal(cur(q), 'c');
  assert.equal(Q.setShuffle(q, false), q);
});

test('repeat mode helpers', () => {
  assert.equal(Q.nextRepeat('off'), 'all');
  assert.equal(Q.nextRepeat('all'), 'one');
  assert.equal(Q.nextRepeat('one'), 'off');
  const q = build('a');
  assert.equal(Q.setRepeat(q, 'bogus'), q);
});

test('snapshot and duration', () => {
  const q = build('abc', 1);
  assert.deepEqual(Q.snapshot(q), { items: ['M/a.mp3', 'M/b.mp3', 'M/c.mp3'], index: 1, shuffle: false, repeat: 'off' });
  assert.equal(Q.totalDurationMs(q), 3000);
});
