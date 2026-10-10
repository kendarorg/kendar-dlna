/* list.json editor: row model, validation and request body. Pure functions (no DOM, no network). */

export const MAX_FIELD = 500;
export const FIELDS = ['author', 'partOf', 'title', 'transcriber'];
/** How far (ms) an entry start may be from a section start and still be the same section (as on the server). */
export const START_TOLERANCE_MS = 500;

let nextId = 1;

/** A fresh row; `id` is a stable key for the table. `index` / `start` (strings) locate a section of the file. */
export function emptyRow(filename) {
  return { id: nextId++, filename: filename || '', index: '', start: '', author: '', partOf: '', title: '', transcriber: '' };
}

function rowFromEntry(e) {
  const r = emptyRow(e.filename);
  FIELDS.forEach(f => { r[f] = e[f] || ''; });
  r.index = e.index === null || e.index === undefined ? '' : String(e.index);
  r.start = e.start || '';
  return r;
}

/** Rows for the entries of a server view ({entries: [...]}); null values become ''. */
export function rowsFromView(view) {
  return (view.entries || []).map(rowFromEntry);
}

/** Comparable snapshot of the rows (without the ids), to tell whether the form is dirty. */
export function snapshot(rows) {
  return JSON.stringify(rows.map(r => [r.filename, r.author, r.partOf, r.title, r.transcriber, r.index, r.start]));
}

/** Rows rebuilt from a {@link snapshot} (discard changes). */
export function rowsFromSnapshot(text) {
  return JSON.parse(text).map(a => rowFromEntry({
    filename: a[0], author: a[1], partOf: a[2], title: a[3], transcriber: a[4], index: a[5], start: a[6]
  }));
}

export function isDirty(rows, original) {
  return snapshot(rows) !== original;
}

const key = name => (name || '').trim().toLowerCase();
const isSection = r => !!(String(r.index).trim() || String(r.start).trim());

/** Parses `[[h:]m:]s[.fff]` into milliseconds; -1 when malformed (same rules as the server). */
export function parseTime(s) {
  const m = /^(\d{1,9}(?::\d{1,2}){0,2})(?:[.,](\d{1,9}))?$/.exec(String(s === null || s === undefined ? '' : s).trim());
  if (!m) return -1;
  let secs = 0;
  m[1].split(':').forEach(p => { secs = secs * 60 + Number(p); });
  const frac = m[2] ? Number((m[2] + '00').slice(0, 3)) : 0;
  return secs * 1000 + frac;
}

/** `mm:ss.fff`, minutes not limited to 59. */
export function formatTime(ms) {
  const v = Math.max(0, Math.round(ms));
  const p = (n, w) => String(n).padStart(w, '0');
  return p(Math.floor(v / 60000), 2) + ':' + p(Math.floor(v / 1000) % 60, 2) + '.' + p(v % 1000, 3);
}

/** Identity of a row: the file, plus `#n` for a track number, else `@ms` for a start. */
export function rowKey(r) {
  const k = key(r.filename);
  const idx = String(r.index).trim();
  if (idx) return k + '#' + Number(idx);
  const st = String(r.start).trim();
  return st ? k + '@' + parseTime(st) : k;
}

/** Directory files that have no row yet, in directory order. */
export function missingFiles(rows, files) {
  const used = new Set(rows.map(r => key(r.filename)));
  return (files || []).filter(f => !used.has(key(f)));
}

/**
 * The choices of the file select of row `index`: its own file plus the files no other whole-file row uses (a
 * section row may name a file that already has other section rows).
 */
export function fileChoices(rows, files, index) {
  const used = new Set(rows.filter((r, i) => i !== index && !isSection(r)).map(r => key(r.filename)));
  const own = rows[index] ? rows[index].filename : '';
  const sectionRow = rows[index] ? isSection(rows[index]) : false;
  const out = (files || []).filter(f => sectionRow || !used.has(key(f)));
  if (own && !out.some(f => key(f) === key(own))) out.unshift(own);   // an entry for a file that is gone stays visible
  return out;
}

/** Appends one empty row per file without a row ("add the missing files in one click"). Returns how many. */
export function addMissing(rows, files) {
  const add = missingFiles(rows, files);
  add.forEach(f => rows.push(emptyRow(f)));
  return add.length;
}

/** Sets `field` on every row (only the empty ones when `onlyEmpty`). Returns how many rows changed. */
export function bulkSet(rows, field, value, onlyEmpty) {
  if (!FIELDS.includes(field)) throw new Error('unknown field ' + field);
  let n = 0;
  rows.forEach(r => {
    if (onlyEmpty && r[field].trim()) return;
    if (r[field] !== value) { r[field] = value; n++; }
  });
  return n;
}

/** Removes a row in place (keeps the array identity). */
export function removeRow(rows, index) {
  if (index >= 0 && index < rows.length) rows.splice(index, 1);
}

/** Problems as {rowIndex: i18nKey}; empty object = valid. `files` are the names present in the directory. */
export function validateRows(rows, files) {
  const errors = {};
  const known = new Set((files || []).map(key));
  const seen = new Set();
  rows.forEach((r, i) => {
    const k = key(r.filename);
    const rk = rowKey(r);
    const idx = String(r.index).trim();
    const st = String(r.start).trim();
    const legacyBase = k.replace(/#\d+$/, '');   // an old "disc.flac#2" file name
    if (!k) errors[i] = 'listjson.err.noFile';
    else if (idx && !/^0*[1-9]\d{0,8}$/.test(idx)) errors[i] = 'listjson.err.index';
    else if (st && parseTime(st) < 0) errors[i] = 'listjson.err.start';
    else if (seen.has(rk)) errors[i] = 'listjson.err.duplicate';
    else if (known.size && !known.has(k) && !known.has(legacyBase)) errors[i] = 'listjson.err.unknownFile';
    else if (FIELDS.some(f => r[f].length > MAX_FIELD)) errors[i] = 'listjson.err.tooLong';
    seen.add(rk);
  });
  return errors;
}

/**
 * The PUT body. Rows without any metadata are left out (an entry that says nothing is useless; the server drops
 * them too), and rows that are still empty and unnamed too. `index` / `start` are sent only when set.
 */
export function toBody(rows, version) {
  const entries = rows
    .filter(r => r.filename.trim() && FIELDS.some(f => r[f].trim()))
    .map(r => {
      const e = { filename: r.filename.trim() };
      if (String(r.index).trim()) e.index = Number(String(r.index).trim());
      if (String(r.start).trim()) e.start = formatTime(parseTime(r.start));
      FIELDS.forEach(f => { e[f] = r[f].trim(); });
      return e;
    });
  return { version: version, entries: entries };
}

// ---- per-file sections list (<file>.json next to the audio file) ----

/** Name of the sidecar of an audio file: the full file name plus `.json`. */
export function sidecarName(fileName) {
  return fileName + '.json';
}

/** `{dir, name}` of a qualified path such as `main/Live/concert.flac`. */
export function splitFilePath(path) {
  const p = String(path || '');
  const i = p.lastIndexOf('/');
  return i < 0 ? { dir: '', name: p } : { dir: p.slice(0, i), name: p.slice(i + 1) };
}

/** The router location of the sections editor of the file at `path`. */
export function sectionsRoute(path) {
  return { path: '/sections', query: { p: path } };
}

/** Rows for the view of a file's sidecar: every row is about the one file, whatever the stored name says. */
export function rowsForFile(view) {
  return (view.entries || []).map(e => rowFromEntry(Object.assign({}, e, { filename: view.fileName })));
}

/** The number a new section should get: one more than the highest track number in use (1 when none). */
export function nextSectionIndex(rows) {
  let max = 0;
  rows.forEach(r => {
    const n = Number(String(r.index).trim());
    if (Number.isInteger(n) && n > max) max = n;
  });
  return max + 1;
}

/** Appends a section row for `fileName` with the next track number and returns it. */
export function addSection(rows, fileName) {
  const r = emptyRow(fileName);
  r.index = String(nextSectionIndex(rows));
  rows.push(r);
  return r;
}

function sameTarget(r, p) {
  const pIdx = p.index === null || p.index === undefined ? '' : String(p.index);
  if (key(r.filename) !== key(p.filename)) {
    // a legacy name "disc.flac#2" is the same as disc.flac with index 2
    return !!pIdx && !isSection(r) && key(r.filename) === key(p.filename) + '#' + pIdx;
  }
  if (!pIdx) return !isSection(r);
  const rIdx = String(r.index).trim();
  if (rIdx) return Number(rIdx) === Number(pIdx);
  const st = String(r.start).trim();
  if (st && p.start) return Math.abs(parseTime(st) - parseTime(p.start)) <= START_TOLERANCE_MS;
  return false;
}

/** Classifies the proposed entries against the rows without changing anything: {added, existing, withData}. */
export function previewGenerated(rows, proposed) {
  let added = 0, existing = 0, withData = 0;
  (proposed || []).forEach(p => {
    const r = rows.find(x => sameTarget(x, p));
    if (!r) added++;
    else { existing++; if (FIELDS.some(f => r[f].trim())) withData++; }
  });
  return { added: added, existing: existing, withData: withData };
}

/**
 * Merges proposed entries (from the generate endpoint) into the rows, in place. A proposal with no matching row
 * is appended. For a matching row, empty fields are filled; fields that already have a value are left alone
 * unless `overwrite` (then every non-empty proposed value replaces it). The locators (index, start) are filled in
 * when the row has none. Returns {added, filled, replaced, kept}; touched rows get `generated = true`.
 */
export function mergeGenerated(rows, proposed, overwrite) {
  const out = { added: 0, filled: 0, replaced: 0, kept: 0 };
  (proposed || []).forEach(p => {
    const r = rows.find(x => sameTarget(x, p));
    if (!r) {
      const nr = rowFromEntry(p);
      nr.generated = true;
      rows.push(nr);
      out.added++;
      return;
    }
    if (p.index) {
      if (!String(r.index).trim()) r.index = String(p.index);
      if (!String(r.start).trim() && p.start) r.start = p.start;
      r.filename = p.filename;   // a legacy "disc.flac#2" becomes file + index
    }
    let filled = 0, replaced = 0;
    FIELDS.forEach(f => {
      const v = p[f] || '';
      if (!v) return;
      if (!r[f].trim()) { r[f] = v; filled++; }
      else if (overwrite && r[f] !== v) { r[f] = v; replaced++; }
    });
    if (replaced) { out.replaced++; r.generated = true; }
    else if (filled) { out.filled++; r.generated = true; }
    else out.kept++;
  });
  return out;
}
