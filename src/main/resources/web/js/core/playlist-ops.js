/*
 * Playlist editing logic (pure). A playlist state is {name, description, paths, version}; edits are recorded as
 * operations so they can be re-applied on a fresh copy of the playlist after a version conflict (409/412):
 *
 *   {type: 'add', paths, position?}          insert/append tracks
 *   {type: 'remove', index, path}            remove the entry (found by path when the index moved)
 *   {type: 'move', index, path, direction}   direction: up | down | top | bottom
 *   {type: 'rename', name}   {type: 'describe', description}
 */
import { moveItem } from './list.js';

export const MAX_NAME = 200;
export const MAX_DESCRIPTION = 1000;

/** Stored paths of owner entries (every status), in stored order. */
export function entryPaths(entries) {
  return entries.slice().sort((a, b) => a.index - b.index).map(e => e.path);
}

/** Entries the viewer can play (status ok with a track). */
export function playableEntries(entries) {
  return entries.filter(e => e.status === 'ok' && e.track);
}

export function playableTracks(entries) {
  return playableEntries(entries).map(e => e.track);
}

/** {ok, missing, noAccess} counts of owner entries. */
export function statusCounts(entries) {
  const c = { ok: 0, missing: 0, noAccess: 0 };
  for (const e of entries) {
    if (e.status === 'missing') c.missing++;
    else if (e.status === 'no_access') c.noAccess++;
    else c.ok++;
  }
  return c;
}

/** Position of the entry: at `index` when the path still sits there, else its first occurrence, else -1. */
export function locate(paths, index, path) {
  if (index >= 0 && index < paths.length && paths[index] === path) return index;
  return paths.indexOf(path);
}

export function applyOp(state, op) {
  const paths = state.paths.slice();
  switch (op.type) {
    case 'add': {
      const at = typeof op.position === 'number' && op.position >= 0 && op.position <= paths.length ? op.position : paths.length;
      paths.splice(at, 0, ...op.paths);
      return Object.assign({}, state, { paths: paths });
    }
    case 'remove': {
      const i = locate(paths, op.index, op.path);
      if (i < 0) return state;
      paths.splice(i, 1);
      return Object.assign({}, state, { paths: paths });
    }
    case 'move': {
      const i = locate(paths, op.index, op.path);
      if (i < 0) return state;
      return Object.assign({}, state, { paths: moveItem(paths, i, op.direction) });
    }
    case 'rename': return Object.assign({}, state, { name: op.name });
    case 'describe': return Object.assign({}, state, { description: op.description });
    default: return state;
  }
}

export function applyOps(state, ops) {
  return ops.reduce(applyOp, state);
}

function sameList(a, b) {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

/** Body of the PUT that turns `before` into `after`: only the fields that changed ({} when nothing did). */
export function diffBody(before, after) {
  const body = {};
  if (after.name !== before.name) body.name = after.name;
  if ((after.description || '') !== (before.description || '')) body.description = after.description || '';
  if (!sameList(before.paths, after.paths)) body.tracks = after.paths;
  return body;
}

/** True for the "edited elsewhere" answers: 409 conflict (version in body) and 412 precondition_failed (If-Match). */
export function isConflict(err) {
  if (!err) return false;
  return (err.status === 409 && err.code === 'conflict') || (err.status === 412 || err.code === 'precondition_failed');
}

/** Moves of the row buttons: which directions are possible at index i of n. */
export function moveStates(i, n) {
  return { up: i > 0, top: i > 0, down: i < n - 1, bottom: i < n - 1 };
}

/**
 * Applies `ops` to `base` and saves the difference. On a conflict the playlist is reloaded with load() and the same ops
 * are applied to the fresh copy (up to `attempts` times). save(body, version) resolves to the new playlist detail.
 * Resolves to {result, reapplied, state} (result null when nothing changed).
 */
export async function commitOps(opts) {
  const attempts = opts.attempts || 3;
  let base = opts.base;
  let reapplied = 0;
  for (let n = 0; ; n++) {
    const next = applyOps(base, opts.ops);
    const body = diffBody(base, next);
    if (!Object.keys(body).length) return { result: null, reapplied: reapplied, state: base };
    try {
      const result = await opts.save(body, base.version);
      return { result: result, reapplied: reapplied, state: next };
    } catch (e) {
      if (!isConflict(e) || n + 1 >= attempts) throw e;
      base = await opts.load();
      reapplied++;
    }
  }
}

/** Reads every entry of a paged list: fetchPage({offset, limit}) -> {entries, total}. */
export async function fetchAllEntries(fetchPage, limit, maxPages) {
  const lim = limit || 1000;
  const out = [];
  let head = null;
  for (let page = 0; page < (maxPages || 50); page++) {
    const r = await fetchPage({ offset: out.length, limit: lim });
    if (!head) head = r;
    const got = r.entries || [];
    for (const e of got) out.push(e);
    if (!got.length || out.length >= r.total) break;
  }
  return { head: head, entries: out };
}

export function validName(name) {
  const n = (name || '').trim();
  return n.length >= 1 && n.length <= MAX_NAME;
}

/** Default name for a playlist saved from the queue, e.g. "Queue 2026-10-09". */
export function suggestName(prefix, date) {
  const d = date || new Date();
  const p = n => String(n).padStart(2, '0');
  return prefix + ' ' + d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

/** A random element (or null); rng returns [0, 1). */
export function pickRandom(list, rng) {
  if (!list || !list.length) return null;
  return list[Math.min(list.length - 1, Math.floor((rng || Math.random)() * list.length))];
}

/** Playlists a user may play from the Home shortcut: the ones with something playable, from all three sources. */
export function playlistChoices(mine, shared, system) {
  const usable = p => (p.count || 0) > 0;
  return []
    .concat((mine || []).filter(usable).map(p => ({ scope: 'mine', playlist: p })))
    .concat((shared || []).filter(usable).map(p => ({ scope: 'shared', playlist: p })))
    .concat((system || []).filter(usable).map(p => ({ scope: 'system', playlist: p })));
}

/** Route of a playlist summary in the given scope. */
export function playlistRoute(scope, p) {
  if (scope === 'system') return '/system/' + encodeURIComponent(p.key);
  if (scope === 'shared') return '/shared/' + encodeURIComponent(p.owner ? p.owner.id : '') + '/' + encodeURIComponent(p.id);
  return '/me/playlists/' + encodeURIComponent(p.id);
}
