/*
 * Small reactive store, no framework needed. Pass Vue's `reactive` as makeReactive so templates track `state`;
 * without it the state is a plain object and only subscribers are notified.
 */
export function createStore(initial, makeReactive) {
  const state = (makeReactive || (x => x))(initial);
  const subscribers = new Set();

  function notify(keys) {
    for (const fn of Array.from(subscribers)) fn(state, keys);
  }

  return {
    state,
    /** fn(state, changedKeys); returns an unsubscribe function. */
    subscribe(fn) { subscribers.add(fn); return () => subscribers.delete(fn); },
    /** Merges a patch; subscribers learn which keys really changed (a no-op notifies nobody). */
    set(patch) {
      const changed = [];
      for (const k of Object.keys(patch)) {
        if (state[k] !== patch[k]) { state[k] = patch[k]; changed.push(k); }
      }
      if (changed.length) notify(changed);
      return changed;
    },
    /** Runs a mutation function on the state and notifies. */
    update(fn) { fn(state); notify(null); }
  };
}
