/* Minimal i18n: flat string tables with {param} interpolation and a plural helper. Pure, no DOM. */

/** Chooses the best available locale for a list of preferences (e.g. navigator.languages). */
export function pickLocale(preferred, available, fallback) {
  for (const p of preferred || []) {
    if (!p) continue;
    const lower = String(p).toLowerCase();
    for (const a of available) if (a.toLowerCase() === lower) return a;
    const base = lower.split('-')[0];
    for (const a of available) if (a.toLowerCase() === base) return a;
  }
  return fallback || 'en';
}

/**
 * tables: {locale: {key: string}}; options: {fallback: 'en', reactive: fn}
 * (reactive wraps the state so templates re-render on setLocale).
 */
export function createI18n(tables, locale, options) {
  const opts = options || {};
  const fallback = opts.fallback || 'en';
  const wrap = opts.reactive || (x => x);
  const state = wrap({ locale: tables[locale] ? locale : fallback });

  function lookup(key) {
    const own = tables[state.locale];
    if (own && Object.prototype.hasOwnProperty.call(own, key)) return own[key];
    const base = tables[fallback];
    if (base && Object.prototype.hasOwnProperty.call(base, key)) return base[key];
    return undefined;
  }

  function interpolate(text, params) {
    if (!params) return text;
    return text.replace(/\{(\w+)\}/g, (m, name) => (name in params ? String(params[name]) : m));
  }

  function t(key, params) {
    const text = lookup(key);
    return text === undefined ? key : interpolate(text, params);
  }

  /** Plural: looks up key.one / key.other (or other CLDR categories); {n} is filled in. */
  function tn(key, n, params) {
    let category = 'other';
    try { category = new Intl.PluralRules(state.locale).select(n); } catch (e) { /* keep other */ }
    let text = lookup(key + '.' + category);
    if (text === undefined) text = lookup(key + '.other');
    if (text === undefined) return key;
    return interpolate(text, Object.assign({ n: n }, params));
  }

  return {
    t, tn,
    has: key => lookup(key) !== undefined,
    get locale() { return state.locale; },
    setLocale(l) { if (tables[l]) state.locale = l; return state.locale; },
    locales: () => Object.keys(tables)
  };
}
