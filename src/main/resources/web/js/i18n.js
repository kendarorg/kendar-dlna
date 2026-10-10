/* The app's i18n instance. English only for now: add a table in ./i18n/ and register it here. */
import { reactive } from './vue.js';
import { createI18n, pickLocale } from './core/i18n.js';
import { en } from './i18n/en.js';

const tables = { en: en };

export const i18n = createI18n(tables,
  pickLocale(typeof navigator !== 'undefined' ? navigator.languages : [], Object.keys(tables), 'en'),
  { fallback: 'en', reactive: reactive });

export const t = i18n.t;
export const tn = i18n.tn;
