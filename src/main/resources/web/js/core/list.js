/* Small list helpers. Pure functions. */

/** Moves an array element; returns a new array. Directions: 'up', 'down', 'top', 'bottom'. */
export function moveItem(list, index, direction) {
  const copy = list.slice();
  if (index < 0 || index >= copy.length) return copy;
  let target = index;
  if (direction === 'up') target = Math.max(0, index - 1);
  else if (direction === 'down') target = Math.min(copy.length - 1, index + 1);
  else if (direction === 'top') target = 0;
  else if (direction === 'bottom') target = copy.length - 1;
  const item = copy.splice(index, 1)[0];
  copy.splice(target, 0, item);
  return copy;
}

const collator = typeof Intl !== 'undefined' ? new Intl.Collator(undefined, { sensitivity: 'base', numeric: true }) : null;

function isEmpty(v) { return v === null || v === undefined || v === ''; }

function compareValues(a, b) {
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  const x = String(a), y = String(b);
  return collator ? collator.compare(x, y) : (x < y ? -1 : x > y ? 1 : 0);
}

/**
 * Stable sort of a copy. key is a property name or a function; empty values always sort last.
 * dir is 'asc' (default) or 'desc'.
 */
export function sortItems(items, key, dir) {
  const get = typeof key === 'function' ? key : (x => x[key]);
  const sign = dir === 'desc' ? -1 : 1;
  return items.map((item, i) => ({ item, i, v: get(item) })).sort((p, q) => {
    const pe = isEmpty(p.v), qe = isEmpty(q.v);
    if (pe || qe) return (pe === qe ? 0 : (pe ? 1 : -1)) || p.i - q.i;
    return (sign * compareValues(p.v, q.v)) || p.i - q.i;
  }).map(p => p.item);
}

/** Parent of a root-qualified path ("Music/A/B" -> "Music/A"); null for a root. */
export function parentPath(path) {
  if (!path) return null;
  const i = path.lastIndexOf('/');
  return i < 0 ? null : path.slice(0, i);
}
