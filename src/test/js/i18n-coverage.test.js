import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { en } from '../../main/resources/web/js/i18n/en.js';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '../../main/resources/web/js');

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(d => {
    const p = path.join(dir, d.name);
    if (d.isDirectory()) return d.name === 'vendor' ? [] : walk(p);
    return p.endsWith('.js') ? [p] : [];
  });
}

/** Files whose strings are all translated (the new personal/admin UI). Phase-1 admin pages are English only. */
const TRANSLATED = [
  'pages/personal.js', 'pages/admin-users.js', 'pages/browse.js', 'components/EntryList.js', 'components/FavButton.js',
  'components/AddToPlaylistDialog.js', 'components/PlayerBar.js', 'components/lists.js', 'components/menus.js',
  'components/Sidebar.js', 'pages/list-json.js', 'pages/settings.js', 'extensions/personal.js', 'extensions/admin.js', 'actions.js',
  'pages/library.js', 'components/DirTree.js', 'components/RowMenu.js'
];

function keysUsed(file) {
  const src = fs.readFileSync(path.join(root, file), 'utf8');
  const out = new Set();
  for (const m of src.matchAll(/(?:\$t|\$tn|\bt|\btn)\(\s*'([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9]+)+)'/g)) out.add({ key: m[1], plural: /tn$/.test(m[0].split('(')[0]) });
  for (const m of src.matchAll(/label:\s*'([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9]+)+)'/g)) out.add({ key: m[1], plural: false });
  for (const m of src.matchAll(/(?:label|titleKey):\s*s?\w*\s*=>\s*\(?[^'"]*'([a-z]+\.[A-Za-z]+)'/g)) out.add({ key: m[1], plural: false });
  return Array.from(out);
}

test('every literal i18n key used by the translated UI exists in the English table', () => {
  const missing = [];
  for (const f of TRANSLATED) {
    for (const k of keysUsed(f)) {
      const ok = k.plural ? (en[k.key + '.other'] !== undefined && en[k.key + '.one'] !== undefined) : en[k.key] !== undefined;
      if (!ok) missing.push(f + ': ' + k.key);
    }
  }
  assert.deepEqual(missing, []);
});

test('dynamic key families exist for every value', () => {
  for (const f of ['filename', 'index', 'start', 'author', 'partOf', 'title', 'transcriber']) assert.ok(en['listjson.col.' + f], 'listjson.col.' + f);
  for (const k of ['noFile', 'duplicate', 'unknownFile', 'tooLong', 'index', 'start']) assert.ok(en['listjson.err.' + k], 'listjson.err.' + k);
  for (const k of ['button', 'help', 'none', 'title', 'result', 'done', 'saveHint']) assert.ok(en['listjson.gen.' + k], 'listjson.gen.' + k);
  for (const k of ['sources', 'overwrite']) assert.ok(en['listjson.gen.' + k + '.one'] && en['listjson.gen.' + k + '.other'], 'listjson.gen.' + k);
  for (const k of ['split', 'keepWholeFiles', 'keepWholeFiles.help']) assert.ok(en['settings.' + k], 'settings.' + k);
  for (const s of ['active', 'invited', 'mustChange', 'disabled']) assert.ok(en['users.status.' + s], 'users.status.' + s);
  for (const s of ['mine', 'shared', 'system']) assert.ok(en['playlist.empty.' + s], 'playlist.empty.' + s);
  for (const r of ['admin', 'user']) assert.ok(en['role.' + r], 'role.' + r);
  for (const v of ['albums', 'artists', 'genres']) assert.ok(en['nav.' + v], 'nav.' + v);
  for (const r of ['off', 'all', 'one']) assert.ok(en['player.repeat.' + r]);
});

test('plural entries come in one/other pairs and no table value is empty', () => {
  for (const [k, v] of Object.entries(en)) {
    assert.ok(typeof v === 'string' && v.length > 0, k);
    if (k.endsWith('.one') && !k.startsWith('player.repeat.')) assert.ok(en[k.slice(0, -4) + '.other'] !== undefined, k + ' needs .other');
  }
});

test('no new page file leaves hard-coded English button text in templates', () => {
  // template text between > and < that looks like prose (two words or more, capitalized) must go through $t
  const offenders = [];
  for (const f of ['pages/personal.js', 'pages/admin-users.js', 'components/EntryList.js', 'components/AddToPlaylistDialog.js', 'components/FavButton.js', 'pages/list-json.js', 'pages/library.js', 'components/DirTree.js', 'components/RowMenu.js']) {
    const src = fs.readFileSync(path.join(root, f), 'utf8');
    for (const m of src.matchAll(/>\s*([A-Z][a-z]+(?: [A-Za-z]+)+)\s*</g)) offenders.push(f + ': ' + m[1]);
  }
  assert.deepEqual(offenders, []);
});

test('every module of the app imports by relative path only (no import map)', () => {
  for (const file of walk(root)) {
    const src = fs.readFileSync(file, 'utf8');
    for (const m of src.matchAll(/(?:import|export)\s[^'"();]*?from\s*['"]([^'"]+)['"]/g)) {
      assert.match(m[1], /^\.{1,2}\//, path.relative(root, file) + ' imports ' + m[1]);
    }
  }
});

test('the single-file sections editor has all its strings', () => {
  for (const k of ['open', 'open.help', 'title', 'help', 'dirList', 'addSection', 'empty', 'saved', 'deleteHint', 'invalid', 'conflict']) {
    assert.ok(en['listjson.file.' + k], 'listjson.file.' + k);
  }
  assert.ok(en['listjson.sidecars']);
  assert.match(en['listjson.file.help'], /\{file\}/);
  assert.match(en['listjson.file.deleteHint'], /\{sidecar\}/);
});

test('the library page has every string its badges and toolbar use', () => {
  const src = fs.readFileSync(path.join(root, 'core/library.js'), 'utf8');
  const keys = new Set();
  for (const m of src.matchAll(/(?:labelKey|helpKey): '([^']+)'/g)) keys.add(m[1]);
  assert.ok(keys.size >= 14);
  for (const k of keys) assert.ok(en[k], k);
  for (const k of ['tree', 'directories', 'collapse', 'expand', 'noRoots', 'select', 'settings', 'editTags', 'rescan', 'forceTags', 'reload', 'openInPlayer', 'actions']) {
    assert.ok(en['lib.' + k], 'lib.' + k);
  }
  assert.match(en['lib.rescan.started'], /{path}/);
  assert.match(en['lib.settingsInvalid'], /{error}/);
});
