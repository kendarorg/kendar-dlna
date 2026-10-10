/*
 * Favorites cache (pure logic, framework-free). Holds the set of favorite paths so every heart in the UI can be drawn
 * without a request, and changes are optimistic (rolled back when the server refuses).
 *
 * deps: {list({offset, limit}) -> {entries, total}, add(paths) -> Promise, remove(paths) -> Promise, reactive?}
 */
export function createFavorites(deps) {
  const wrap = deps.reactive || (x => x);
  const state = wrap({ paths: {}, loaded: false, count: 0, error: '' });
  const listeners = new Set();

  function changed() { for (const fn of Array.from(listeners)) fn(state); }
  function setPath(path, on) {
    if (on) { if (!state.paths[path]) { state.paths[path] = true; state.count++; } }
    else if (state.paths[path]) { delete state.paths[path]; state.count--; }
  }

  async function load() {
    const paths = {};
    let count = 0;
    for (let guard = 0; guard < 100; guard++) {
      const r = await deps.list({ offset: count, limit: 1000 });
      const got = r.entries || [];
      for (const e of got) if (!paths[e.path]) { paths[e.path] = true; }
      count += got.length;
      if (!got.length || count >= r.total) break;
    }
    state.paths = paths;
    state.count = Object.keys(paths).length;
    state.loaded = true;
    state.error = '';
    changed();
  }

  function has(path) { return !!state.paths[path]; }

  async function change(paths, on) {
    const todo = Array.from(new Set(paths)).filter(p => has(p) !== on);
    if (!todo.length) return 0;
    todo.forEach(p => setPath(p, on));
    changed();
    try {
      await (on ? deps.add(todo) : deps.remove(todo));
    } catch (e) {
      todo.forEach(p => setPath(p, !on));
      state.error = (e && e.message) || String(e);
      changed();
      throw e;
    }
    return todo.length;
  }

  return {
    state, load, has,
    add: paths => change(paths, true),
    remove: paths => change(paths, false),
    /** Flips one track; resolves to the new state (true = now a favorite). */
    async toggle(path) { const on = !has(path); await change([path], on); return on; },
    /** Adds the tracks that are not favorites yet; resolves to how many were added. */
    addAll: paths => change(paths, true),
    onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    reset() { state.paths = {}; state.count = 0; state.loaded = false; state.error = ''; changed(); }
  };
}
