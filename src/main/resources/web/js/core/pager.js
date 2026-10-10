/*
 * Offset pager for infinite scroll. fetchPage({offset, limit}) resolves to {items, total}.
 * Late answers of an older generation (after reset()) are dropped.
 */
export function createPager(fetchPage, options) {
  const opts = options || {};
  const limit = opts.limit || 50;
  const state = (opts.reactive || (x => x))({
    items: [], total: null, loading: false, done: false, error: '', generation: 0
  });

  async function loadMore() {
    if (state.loading || state.done) return false;
    const generation = state.generation;
    state.loading = true;
    state.error = '';
    try {
      const page = await fetchPage({ offset: state.items.length, limit: limit });
      if (generation !== state.generation) return false;
      const items = (page && page.items) || [];
      if (page && typeof page.total === 'number') state.total = page.total;
      for (const item of items) state.items.push(item);
      state.done = items.length === 0 || (state.total !== null && state.items.length >= state.total);
      return true;
    } catch (e) {
      if (generation === state.generation) {
        state.error = (e && e.message) || String(e);
        state.done = true;
      }
      return false;
    } finally {
      if (generation === state.generation) state.loading = false;
    }
  }

  function reset() {
    state.generation++;
    state.items = [];
    state.total = null;
    state.loading = false;
    state.done = false;
    state.error = '';
  }

  /** Loads everything that is left (bounded by maxPages), e.g. to play a whole album. */
  async function loadAll(maxPages) {
    let pages = 0;
    while (!state.done && pages++ < (maxPages || 100)) {
      if (state.loading) { await new Promise(r => setTimeout(r, 20)); continue; }
      const before = state.items.length;
      await loadMore();
      if (state.items.length === before && !state.done) break;
    }
    return state.items;
  }

  return { state, loadMore, reset, loadAll, limit };
}
