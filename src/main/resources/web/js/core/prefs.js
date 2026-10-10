/* Per-browser preferences in a Storage-like object; every access is guarded (private mode, blocked storage). */
export function createPrefs(storage, prefix) {
  const p = prefix || 'kd.';
  return {
    get(key, fallback) {
      try {
        const raw = storage && storage.getItem(p + key);
        return raw === null || raw === undefined ? fallback : JSON.parse(raw);
      } catch (e) { return fallback; }
    },
    set(key, value) {
      try { if (storage) storage.setItem(p + key, JSON.stringify(value)); return true; } catch (e) { return false; }
    },
    remove(key) { try { if (storage) storage.removeItem(p + key); } catch (e) { /* ignore */ } }
  };
}
