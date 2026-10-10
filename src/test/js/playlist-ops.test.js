import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  entryPaths, playableTracks, statusCounts, locate, applyOp, applyOps, diffBody, isConflict, moveStates, commitOps,
  fetchAllEntries, validName, suggestName, pickRandom, playlistChoices, playlistRoute
} from '../../main/resources/web/js/core/playlist-ops.js';
import { withPlayability, decorateEntries } from '../../main/resources/web/js/core/track.js';

const st = (paths, extra) => Object.assign({ name: 'Mix', description: '', paths: paths, version: 1 }, extra);
const conflict = () => Object.assign(new Error('conflict'), { status: 409, code: 'conflict' });

test('entry helpers: stored order, playable tracks and status counts', () => {
  const entries = [
    { index: 2, path: 'M/c', status: 'ok', track: { path: 'M/c' } },
    { index: 0, path: 'M/a', status: 'ok', track: { path: 'M/a' } },
    { index: 1, path: 'M/gone', status: 'missing' },
    { index: 3, path: 'X/y', status: 'no_access' }
  ];
  assert.deepEqual(entryPaths(entries), ['M/a', 'M/gone', 'M/c', 'X/y']);
  assert.deepEqual(playableTracks(entries).map(t => t.path), ['M/c', 'M/a']);
  assert.deepEqual(statusCounts(entries), { ok: 2, missing: 1, noAccess: 1 });
});

test('locate finds the entry at its index, else by path, else -1', () => {
  const p = ['a', 'b', 'c', 'b'];
  assert.equal(locate(p, 3, 'b'), 3);
  assert.equal(locate(p, 0, 'b'), 1);
  assert.equal(locate(p, 9, 'c'), 2);
  assert.equal(locate(p, 0, 'zzz'), -1);
});

test('operations: add (append and insert), remove, move, rename, describe', () => {
  let s = st(['a', 'b', 'c']);
  assert.deepEqual(applyOp(s, { type: 'add', paths: ['d', 'e'] }).paths, ['a', 'b', 'c', 'd', 'e']);
  assert.deepEqual(applyOp(s, { type: 'add', paths: ['x'], position: 1 }).paths, ['a', 'x', 'b', 'c']);
  assert.deepEqual(applyOp(s, { type: 'add', paths: ['x'], position: 99 }).paths, ['a', 'b', 'c', 'x']);
  assert.deepEqual(applyOp(s, { type: 'remove', index: 1, path: 'b' }).paths, ['a', 'c']);
  assert.equal(applyOp(s, { type: 'remove', index: 1, path: 'nope' }), s, 'removing an entry that is gone changes nothing');
  assert.deepEqual(applyOp(s, { type: 'move', index: 2, path: 'c', direction: 'top' }).paths, ['c', 'a', 'b']);
  assert.deepEqual(applyOp(s, { type: 'move', index: 0, path: 'a', direction: 'bottom' }).paths, ['b', 'c', 'a']);
  assert.deepEqual(applyOp(s, { type: 'move', index: 1, path: 'b', direction: 'up' }).paths, ['b', 'a', 'c']);
  assert.deepEqual(applyOp(s, { type: 'move', index: 1, path: 'b', direction: 'down' }).paths, ['a', 'c', 'b']);
  assert.equal(applyOp(s, { type: 'rename', name: 'New' }).name, 'New');
  assert.equal(applyOp(s, { type: 'describe', description: 'd' }).description, 'd');
  assert.equal(s.paths.length, 3, 'the input state is never mutated');
  assert.equal(applyOps(s, [{ type: 'remove', index: 0, path: 'a' }, { type: 'add', paths: ['z'] }]).paths.join(), 'b,c,z');
});

test('diffBody sends only what changed', () => {
  const a = st(['a', 'b']);
  assert.deepEqual(diffBody(a, a), {});
  assert.deepEqual(diffBody(a, st(['a', 'b'], { name: 'Other' })), { name: 'Other' });
  assert.deepEqual(diffBody(a, st(['b', 'a'])), { tracks: ['b', 'a'] });
  assert.deepEqual(diffBody(a, st(['a', 'b'], { description: 'x' })), { description: 'x' });
});

test('isConflict recognizes 409 conflict and 412', () => {
  assert.equal(isConflict(conflict()), true);
  assert.equal(isConflict({ status: 412, code: 'precondition_failed' }), true);
  assert.equal(isConflict({ status: 409, code: 'too_many' }), false);
  assert.equal(isConflict({ status: 404, code: 'not_found' }), false);
  assert.equal(isConflict(null), false);
});

test('moveStates: which buttons make sense at a position', () => {
  assert.deepEqual(moveStates(0, 3), { up: false, top: false, down: true, bottom: true });
  assert.deepEqual(moveStates(2, 3), { up: true, top: true, down: false, bottom: false });
  assert.deepEqual(moveStates(0, 1), { up: false, top: false, down: false, bottom: false });
});

test('commitOps saves the difference with the version', async () => {
  const saved = [];
  const r = await commitOps({
    base: st(['a', 'b', 'c'], { version: 4 }),
    ops: [{ type: 'move', index: 2, path: 'c', direction: 'up' }],
    load: async () => { throw new Error('no load expected'); },
    save: async (body, version) => { saved.push([body, version]); return { version: 5 }; }
  });
  assert.deepEqual(saved, [[{ tracks: ['a', 'c', 'b'] }, 4]]);
  assert.equal(r.reapplied, 0);
  assert.deepEqual(r.result, { version: 5 });
});

test('commitOps does nothing when the edit changes nothing', async () => {
  const r = await commitOps({
    base: st(['a']), ops: [{ type: 'remove', index: 0, path: 'gone' }],
    load: async () => { throw new Error('x'); }, save: async () => { throw new Error('no save expected'); }
  });
  assert.equal(r.result, null);
});

test('a version conflict reloads the playlist and re-applies the same operations', async () => {
  let loads = 0;
  const saves = [];
  const r = await commitOps({
    base: st(['a', 'b', 'c'], { version: 1 }),
    ops: [{ type: 'remove', index: 1, path: 'b' }, { type: 'rename', name: 'Renamed' }],
    load: async () => { loads++; return st(['z', 'a', 'b', 'c'], { version: 7 }); },   // somebody else added "z" first
    save: async (body, version) => {
      saves.push([body, version]);
      if (version === 1) throw conflict();
      return { version: 8 };
    }
  });
  assert.equal(loads, 1);
  assert.equal(r.reapplied, 1);
  assert.deepEqual(saves[0], [{ name: 'Renamed', tracks: ['a', 'c'] }, 1]);
  assert.deepEqual(saves[1], [{ name: 'Renamed', tracks: ['z', 'a', 'c'] }, 7]);
});

test('a 412 is treated like a 409; other errors and exhausted attempts propagate', async () => {
  const stale = () => ({ status: 412, code: 'precondition_failed' });
  let n = 0;
  await commitOps({
    base: st(['a', 'b']), ops: [{ type: 'remove', index: 0, path: 'a' }],
    load: async () => st(['a', 'b'], { version: 2 }), save: async () => { if (n++ === 0) throw stale(); return {}; }
  });
  assert.equal(n, 2);

  await assert.rejects(commitOps({
    base: st(['a']), ops: [{ type: 'add', paths: ['b'] }],
    load: async () => st(['a'], { version: 2 }), save: async () => { throw conflict(); }, attempts: 2
  }), /conflict/);

  await assert.rejects(commitOps({
    base: st(['a']), ops: [{ type: 'add', paths: ['b'] }],
    load: async () => st(['a']), save: async () => { throw Object.assign(new Error('boom'), { status: 500, code: 'internal_error' }); }
  }), /boom/);
});

test('fetchAllEntries pages until the total is reached', async () => {
  const all = Array.from({ length: 25 }, (_, i) => ({ index: i, path: 'p' + i }));
  const calls = [];
  const r = await fetchAllEntries(async ({ offset, limit }) => {
    calls.push([offset, limit]);
    return { total: all.length, entries: all.slice(offset, offset + limit), name: 'head' };
  }, 10);
  assert.equal(r.entries.length, 25);
  assert.deepEqual(calls, [[0, 10], [10, 10], [20, 10]]);
  assert.equal(r.head.name, 'head');
  const empty = await fetchAllEntries(async () => ({ total: 0, entries: [] }), 10);
  assert.equal(empty.entries.length, 0);
  // a server that stops delivering cannot loop forever
  const stuck = await fetchAllEntries(async () => ({ total: 99, entries: [] }), 10);
  assert.equal(stuck.entries.length, 0);
});

test('names: validation, suggestion', () => {
  assert.equal(validName('  x '), true);
  assert.equal(validName('   '), false);
  assert.equal(validName('x'.repeat(201)), false);
  assert.equal(suggestName('Queue', new Date(2026, 9, 9)), 'Queue 2026-10-09');
});

test('pickRandom and playlistChoices (Home shortcut) skip empty playlists', () => {
  assert.equal(pickRandom([], Math.random), null);
  assert.equal(pickRandom(['a', 'b', 'c'], () => 0), 'a');
  assert.equal(pickRandom(['a', 'b', 'c'], () => 0.999), 'c');
  const choices = playlistChoices([{ id: '1', count: 2 }, { id: '2', count: 0 }], [{ id: '3', count: 1 }], [{ key: 'k', count: 5 }, { key: 'e', count: 0 }]);
  assert.deepEqual(choices.map(c => c.scope + ':' + (c.playlist.id || c.playlist.key)), ['mine:1', 'shared:3', 'system:k']);
});

test('playlistRoute per scope encodes ids', () => {
  assert.equal(playlistRoute('mine', { id: 'a b' }), '/me/playlists/a%20b');
  assert.equal(playlistRoute('shared', { id: 'p', owner: { id: 'u1' } }), '/shared/u1/p');
  assert.equal(playlistRoute('system', { key: 'Rock & Roll' }), '/system/Rock%20%26%20Roll');
});

test('personal tracks get the playability the browser derives from its formats', () => {
  const formats = ['mp3', 'flac'];
  assert.equal(withPlayability({ format: 'flac' }, formats).playability, 'native');
  const w = withPlayability({ format: 'wma' }, formats);
  assert.deepEqual([w.playability, w.streamFormat], ['transcoded', 'mp3']);
  assert.equal(withPlayability({ format: 'midi' }, ['midi', 'mp3']).playability, 'transcoded');
  const given = { format: 'mp3', playability: 'native' };
  assert.equal(withPlayability(given, []), given);
  const e = decorateEntries([{ status: 'ok', track: { format: 'mp3' } }, { status: 'missing', path: 'x' }], formats);
  assert.equal(e[0].track.playability, 'native');
  assert.equal(e[1].track, undefined);
});
