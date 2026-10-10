/*
 * Play queue operations. Pure: every function returns a new queue object and never mutates its input.
 *
 * queue = { items: [{id, track}], index, shuffle, repeat: 'off'|'all'|'one', order: [ids] }
 *   index  position of the current entry in items (-1: none)
 *   order  play order as entry ids while shuffle is on (every id once); [] otherwise
 * Entries have their own id, so the same track can be queued twice.
 */
import { moveItem } from './list.js';
import { shuffled } from './shuffle.js';

export const REPEAT_MODES = ['off', 'all', 'one'];

let counter = 0;

export function makeEntry(track) {
  counter++;
  return { id: 'q' + counter, track: track };
}

export function createQueue(options) {
  const o = options || {};
  return {
    items: [], index: -1,
    shuffle: !!o.shuffle,
    repeat: REPEAT_MODES.includes(o.repeat) ? o.repeat : 'off',
    order: []
  };
}

export function current(q) {
  return q.index >= 0 && q.index < q.items.length ? q.items[q.index] : null;
}

function indexOfId(items, id) {
  return items.findIndex(e => e.id === id);
}

/** Shuffled ids with the current one first. */
function buildOrder(items, currentId, rng) {
  const rest = items.filter(e => e.id !== currentId).map(e => e.id);
  const mixed = shuffled(rest, rng);
  return currentId ? [currentId].concat(mixed) : mixed;
}

function withItems(q, items, index, order) {
  return { items: items, index: index, shuffle: q.shuffle, repeat: q.repeat, order: order };
}

/** Replaces the queue by tracks and makes startIndex current. */
export function setQueue(q, tracks, startIndex, rng) {
  const items = tracks.map(makeEntry);
  const index = items.length === 0 ? -1 : Math.max(0, Math.min(startIndex || 0, items.length - 1));
  const order = q.shuffle && items.length ? buildOrder(items, items[index].id, rng) : [];
  return withItems(q, items, index, order);
}

/** Appends tracks to the end. An empty queue gets its first track as the (not yet playing) current one. */
export function enqueue(q, tracks, rng) {
  if (!tracks.length) return q;
  const added = tracks.map(makeEntry);
  const items = q.items.concat(added);
  const index = q.index < 0 ? 0 : q.index;
  let order = [];
  if (q.shuffle) {
    order = q.index < 0 ? buildOrder(items, items[0].id, rng)
      : q.order.concat(shuffled(added.map(e => e.id), rng));
  }
  return withItems(q, items, index, order);
}

/** Inserts tracks right after the current one (and next in the shuffle order). */
export function playNext(q, tracks, rng) {
  if (!tracks.length) return q;
  if (q.index < 0) return enqueue(q, tracks, rng);
  const added = tracks.map(makeEntry);
  const items = q.items.slice();
  items.splice(q.index + 1, 0, ...added);
  let order = [];
  if (q.shuffle) {
    order = q.order.slice();
    const pos = order.indexOf(q.items[q.index].id);
    order.splice(pos + 1, 0, ...added.map(e => e.id));
  }
  return withItems(q, items, q.index, order);
}

/** Removes entry i. The current entry's successor becomes current. */
export function removeAt(q, i) {
  if (i < 0 || i >= q.items.length) return q;
  const removed = q.items[i];
  const items = q.items.filter((e, k) => k !== i);
  if (items.length === 0) return withItems(q, [], -1, []);
  const orderPos = q.order.indexOf(removed.id);
  const order = q.order.filter(id => id !== removed.id);
  let index = q.index;
  if (i < q.index) {
    index = q.index - 1;
  } else if (i === q.index) {
    if (q.shuffle) {
      const nextId = order[Math.min(orderPos, order.length - 1)];
      index = indexOfId(items, nextId);
    } else {
      index = Math.min(i, items.length - 1);
    }
  }
  return withItems(q, items, index, order);
}

/** Moves entry i ('up' | 'down' | 'top' | 'bottom'); the current entry stays current. */
export function move(q, i, direction) {
  if (i < 0 || i >= q.items.length) return q;
  const cur = current(q);
  const items = moveItem(q.items, i, direction);
  return withItems(q, items, cur ? indexOfId(items, cur.id) : -1, q.order);
}

/** Clears the queue; keepCurrent leaves only the current entry. */
export function clear(q, keepCurrent) {
  const cur = current(q);
  if (keepCurrent && cur) return withItems(q, [cur], 0, q.shuffle ? [cur.id] : []);
  return withItems(q, [], -1, []);
}

/** Makes entry i current. In shuffle mode it is slotted right after the previous current one. */
export function jump(q, i) {
  if (i < 0 || i >= q.items.length) return q;
  if (!q.shuffle) return withItems(q, q.items, i, q.order);
  const cur = current(q);
  const id = q.items[i].id;
  const order = q.order.filter(x => x !== id);
  const pos = cur ? order.indexOf(cur.id) : -1;
  order.splice(pos + 1, 0, id);
  return withItems(q, q.items, i, order);
}

/**
 * Next entry. auto: the track ended by itself (repeat 'one' then replays it).
 * Returns {queue, ended, replay}: ended when there is nothing more to play (queue unchanged),
 * replay when the same entry plays again.
 */
export function next(q, auto, rng) {
  const n = q.items.length;
  if (n === 0) return { queue: q, ended: true, replay: false };
  if (auto && q.repeat === 'one') return { queue: q, ended: false, replay: true };
  if (q.shuffle) {
    const pos = q.order.indexOf(q.items[q.index].id);
    if (pos + 1 < q.order.length) {
      return { queue: withItems(q, q.items, indexOfId(q.items, q.order[pos + 1]), q.order), ended: false, replay: false };
    }
    if (q.repeat === 'all') {
      let order = buildOrder(q.items, null, rng);
      if (n > 1 && order[0] === q.items[q.index].id) order = order.slice(1).concat(order[0]);
      return { queue: withItems(q, q.items, indexOfId(q.items, order[0]), order), ended: false, replay: n === 1 };
    }
    return { queue: q, ended: true, replay: false };
  }
  if (q.index + 1 < n) return { queue: withItems(q, q.items, q.index + 1, q.order), ended: false, replay: false };
  if (q.repeat === 'all') return { queue: withItems(q, q.items, 0, q.order), ended: false, replay: n === 1 };
  return { queue: q, ended: true, replay: false };
}

/**
 * Previous entry; after more than 3 seconds of the current one it restarts instead.
 * Returns {queue, restart}.
 */
export function prev(q, positionSeconds) {
  const n = q.items.length;
  if (n === 0) return { queue: q, restart: false };
  if (positionSeconds > 3) return { queue: q, restart: true };
  if (q.shuffle) {
    const pos = q.order.indexOf(q.items[q.index].id);
    if (pos > 0) return { queue: withItems(q, q.items, indexOfId(q.items, q.order[pos - 1]), q.order), restart: false };
    if (q.repeat === 'all' && n > 1) {
      return { queue: withItems(q, q.items, indexOfId(q.items, q.order[n - 1]), q.order), restart: false };
    }
    return { queue: q, restart: true };
  }
  if (q.index > 0) return { queue: withItems(q, q.items, q.index - 1, q.order), restart: false };
  if (q.repeat === 'all' && n > 1) return { queue: withItems(q, q.items, n - 1, q.order), restart: false };
  return { queue: q, restart: true };
}

/** The entry that will play after the current one (for preloading), or null when unknown or the same one. */
export function peekNext(q) {
  const n = q.items.length;
  if (n === 0 || q.index < 0 || q.repeat === 'one') return null;
  if (q.shuffle) {
    const pos = q.order.indexOf(q.items[q.index].id);
    return pos + 1 < q.order.length ? q.items[indexOfId(q.items, q.order[pos + 1])] : null;
  }
  if (q.index + 1 < n) return q.items[q.index + 1];
  return q.repeat === 'all' && n > 1 ? q.items[0] : null;
}

export function setShuffle(q, on, rng) {
  if (!!on === q.shuffle) return q;
  const cur = current(q);
  return {
    items: q.items, index: q.index, repeat: q.repeat, shuffle: !!on,
    order: on && q.items.length ? buildOrder(q.items, cur ? cur.id : null, rng) : []
  };
}

export function setRepeat(q, mode) {
  if (!REPEAT_MODES.includes(mode) || mode === q.repeat) return q;
  return { items: q.items, index: q.index, shuffle: q.shuffle, order: q.order, repeat: mode };
}

export function nextRepeat(mode) {
  return REPEAT_MODES[(REPEAT_MODES.indexOf(mode) + 1) % REPEAT_MODES.length];
}

/** Serializable form for the future server-side queue sync (/api/v1/me/queue). */
export function snapshot(q) {
  return { items: q.items.map(e => e.track.path), index: q.index, shuffle: q.shuffle, repeat: q.repeat };
}

export function totalDurationMs(q) {
  return q.items.reduce((sum, e) => sum + (e.track.durationMillis || 0), 0);
}
