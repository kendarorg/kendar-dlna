import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createStore } from '../../main/resources/web/js/core/store.js';
import { createPager } from '../../main/resources/web/js/core/pager.js';
import { createPrefs } from '../../main/resources/web/js/core/prefs.js';
import { createEmitter } from '../../main/resources/web/js/core/events.js';
import { createI18n, pickLocale } from '../../main/resources/web/js/core/i18n.js';
import { shortcutFor } from '../../main/resources/web/js/core/keys.js';
import { mulberry32, shuffled } from '../../main/resources/web/js/core/shuffle.js';
import { moveItem, sortItems, parentPath } from '../../main/resources/web/js/core/list.js';
import { formatClock, formatDuration, formatSize, formatTotal } from '../../main/resources/web/js/core/format.js';

test('store: set notifies only about real changes, update always notifies', () => {
  const store = createStore({ a: 1, b: 2 });
  const calls = [];
  const off = store.subscribe((s, keys) => calls.push(keys));
  assert.deepEqual(store.set({ a: 1 }), []);
  assert.deepEqual(store.set({ a: 5, b: 2 }), ['a']);
  store.update(s => { s.b = 9; });
  assert.deepEqual(calls, [['a'], null]);
  assert.equal(store.state.b, 9);
  off();
  store.set({ a: 6 });
  assert.equal(calls.length, 2);
});

test('store: uses the reactive wrapper it is given', () => {
  let wrapped = false;
  const store = createStore({ x: 1 }, o => { wrapped = true; return o; });
  assert.equal(wrapped, true);
  assert.equal(store.state.x, 1);
});

test('emitter: on/emit/unsubscribe and failing listeners do not break others', () => {
  const e = createEmitter();
  const got = [];
  const off = e.on('x', v => got.push(v));
  const origError = console.error;
  console.error = () => {};
  e.on('x', () => { throw new Error('boom'); });
  e.on('x', v => got.push(v * 2));
  e.emit('x', 2);
  console.error = origError;
  off();
  e.emit('x', 3);
  assert.deepEqual(got, [2, 4, 6]);
  e.emit('nobody', 1);
});

test('pager: loads pages until total is reached', async () => {
  const data = Array.from({ length: 25 }, (_, i) => i);
  const calls = [];
  const pager = createPager(async ({ offset, limit }) => {
    calls.push([offset, limit]);
    return { items: data.slice(offset, offset + limit), total: data.length };
  }, { limit: 10 });
  while (!pager.state.done) await pager.loadMore();
  assert.equal(pager.state.items.length, 25);
  assert.deepEqual(calls, [[0, 10], [10, 10], [20, 10]]);
  assert.equal(await pager.loadMore(), false);
});

test('pager: an empty page ends the list even without a total', async () => {
  let n = 0;
  const pager = createPager(async () => ({ items: n++ === 0 ? [1, 2] : [] }), { limit: 2 });
  await pager.loadMore(); assert.equal(pager.state.done, false);
  await pager.loadMore(); assert.equal(pager.state.done, true);
  assert.deepEqual(pager.state.items, [1, 2]);
});

test('pager: reset drops a late answer of the old generation', async () => {
  let release;
  const gate = new Promise(r => { release = r; });
  let first = true;
  const pager = createPager(async () => {
    if (first) { first = false; await gate; return { items: ['old'], total: 1 }; }
    return { items: ['new'], total: 1 };
  });
  const pending = pager.loadMore();
  pager.reset();
  await pager.loadMore();
  release();
  await pending;
  assert.deepEqual(pager.state.items, ['new']);
});

test('pager: errors stop loading and are reported; loadAll collects everything', async () => {
  const bad = createPager(async () => { throw new Error('nope'); });
  await bad.loadMore();
  assert.equal(bad.state.error, 'nope');
  assert.equal(bad.state.done, true);
  const data = Array.from({ length: 7 }, (_, i) => i);
  const pager = createPager(async ({ offset, limit }) => ({ items: data.slice(offset, offset + limit), total: 7 }), { limit: 3 });
  assert.equal((await pager.loadAll()).length, 7);
});

test('prefs: round trip with a fake storage, fallbacks when storage fails', () => {
  const map = new Map();
  const storage = { getItem: k => (map.has(k) ? map.get(k) : null), setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) };
  const prefs = createPrefs(storage, 't.');
  assert.equal(prefs.get('vol', 0.5), 0.5);
  assert.equal(prefs.set('vol', 0.25), true);
  assert.equal(prefs.get('vol', 0.5), 0.25);
  assert.ok(map.has('t.vol'));
  prefs.remove('vol');
  assert.equal(prefs.get('vol', 7), 7);
  const broken = createPrefs({ getItem() { throw new Error('x'); }, setItem() { throw new Error('x'); } });
  assert.equal(broken.get('a', 1), 1);
  assert.equal(broken.set('a', 1), false);
  assert.equal(createPrefs(null).get('a', 3), 3);
});

test('i18n: lookup, interpolation, fallback, plurals, locale choice', () => {
  const i18n = createI18n({
    en: { hello: 'Hello {name}', 'songs.one': '{n} song', 'songs.other': '{n} songs', only: 'English only' },
    it: { hello: 'Ciao {name}', 'songs.one': '{n} brano', 'songs.other': '{n} brani' }
  }, 'it');
  assert.equal(i18n.t('hello', { name: 'Ann' }), 'Ciao Ann');
  assert.equal(i18n.t('only'), 'English only');
  assert.equal(i18n.t('missing.key'), 'missing.key');
  assert.equal(i18n.t('hello'), 'Ciao {name}');
  assert.equal(i18n.tn('songs', 1), '1 brano');
  assert.equal(i18n.tn('songs', 3), '3 brani');
  i18n.setLocale('en');
  assert.equal(i18n.tn('songs', 1), '1 song');
  assert.equal(i18n.setLocale('xx'), 'en');
  assert.equal(i18n.has('only'), true);
  assert.equal(pickLocale(['de-AT', 'it-IT'], ['en', 'it'], 'en'), 'it');
  assert.equal(pickLocale(['de'], ['en', 'it'], 'en'), 'en');
  assert.equal(pickLocale(null, ['en']), 'en');
});

test('shortcuts: mapping and exclusions', () => {
  const body = { tagName: 'BODY' };
  assert.equal(shortcutFor({ key: ' ', target: body }), 'toggle');
  assert.equal(shortcutFor({ key: 'ArrowLeft', target: body }), 'seekBack');
  assert.equal(shortcutFor({ key: 'ArrowRight', target: body }), 'seekForward');
  assert.equal(shortcutFor({ key: 'ArrowUp', target: body }), null);
  assert.equal(shortcutFor({ key: 'ArrowUp', shiftKey: true, target: body }), 'volumeUp');
  assert.equal(shortcutFor({ key: 'N', target: body }), 'next');
  assert.equal(shortcutFor({ key: 'p', target: body }), 'prev');
  assert.equal(shortcutFor({ key: 's', target: body }), 'shuffle');
  assert.equal(shortcutFor({ key: 'r', target: body }), 'repeat');
  assert.equal(shortcutFor({ key: 'x', target: body }), null);
  assert.equal(shortcutFor({ key: 'n', ctrlKey: true, target: body }), null);
  assert.equal(shortcutFor({ key: 'n', target: { tagName: 'INPUT' } }), null);
  assert.equal(shortcutFor({ key: ' ', target: { tagName: 'BUTTON' } }), null);
  assert.equal(shortcutFor({ key: 'n', target: { tagName: 'DIV', isContentEditable: true } }), null);
  assert.equal(shortcutFor(null), null);
});

test('shuffle: deterministic with a seed and a permutation', () => {
  const list = [1, 2, 3, 4, 5, 6, 7, 8, 9];
  const a = shuffled(list, mulberry32(7)), b = shuffled(list, mulberry32(7));
  assert.deepEqual(a, b);
  assert.deepEqual(a.slice().sort(), list);
  assert.deepEqual(list, [1, 2, 3, 4, 5, 6, 7, 8, 9]);
  assert.notDeepEqual(shuffled(list, mulberry32(8)), a);
});

test('list helpers: moveItem, sortItems, parentPath', () => {
  assert.deepEqual(moveItem([1, 2, 3], 2, 'top'), [3, 1, 2]);
  assert.deepEqual(moveItem([1, 2, 3], 0, 'down'), [2, 1, 3]);
  assert.deepEqual(moveItem([1, 2, 3], 5, 'up'), [1, 2, 3]);
  const items = [{ t: 'b', n: 2 }, { t: 'A', n: null }, { t: 'a', n: 10 }, { t: 'c', n: 1 }];
  assert.deepEqual(sortItems(items, 't').map(x => x.t), ['A', 'a', 'b', 'c']);   // stable, case-insensitive
  assert.deepEqual(sortItems(items, 'n').map(x => x.n), [1, 2, 10, null]);
  assert.deepEqual(sortItems(items, 'n', 'desc').map(x => x.n), [10, 2, 1, null]);   // empty last either way
  assert.equal(parentPath('Music/A/B'), 'Music/A');
  assert.equal(parentPath('Music'), null);
  assert.equal(parentPath(''), null);
});

test('format helpers', () => {
  assert.equal(formatClock(65), '1:05');
  assert.equal(formatClock(3725), '1:02:05');
  assert.equal(formatClock(NaN), '0:00');
  assert.equal(formatDuration(0), '');
  assert.equal(formatDuration(61000), '1:01');
  assert.equal(formatSize(2048), '2 KB');
  assert.equal(formatSize(null), '');
  assert.equal(formatTotal(42 * 60000), '42 min');
  assert.equal(formatTotal(65 * 60000), '1 h 05 min');
});
