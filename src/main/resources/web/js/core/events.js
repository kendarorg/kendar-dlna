/* Tiny event emitter: the extension seam between the player and later features (history, queue sync...). */
export function createEmitter() {
  const handlers = new Map();
  return {
    on(name, fn) {
      if (!handlers.has(name)) handlers.set(name, new Set());
      handlers.get(name).add(fn);
      return () => handlers.get(name).delete(fn);
    },
    emit(name, payload) {
      const set = handlers.get(name);
      if (!set) return;
      for (const fn of Array.from(set)) {
        try { fn(payload); } catch (e) { if (typeof console !== 'undefined') console.error('listener for ' + name + ' failed', e); }
      }
    }
  };
}
