import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  emptyRow, rowsFromView, snapshot, isDirty, missingFiles, fileChoices, addMissing, bulkSet, removeRow,
  validateRows, toBody, MAX_FIELD, parseTime, formatTime, rowsFromSnapshot, previewGenerated, mergeGenerated,
  sidecarName, splitFilePath, sectionsRoute, rowsForFile, nextSectionIndex, addSection
} from '../../main/resources/web/js/core/list-json.js';

const FILES = ['a.mid', 'B.mid', 'c.mid'];

function row(filename, o) { return Object.assign(emptyRow(filename), o || {}); }

test('rowsFromView turns nulls into empty strings and gives each row a unique id', () => {
  const rows = rowsFromView({ entries: [{ filename: 'a.mid', author: 'X', partOf: null, title: 'T', transcriber: null }, { filename: 'B.mid' }] });
  assert.equal(rows.length, 2);
  assert.equal(rows[0].partOf, '');
  assert.equal(rows[0].author, 'X');
  assert.equal(rows[1].title, '');
  assert.notEqual(rows[0].id, rows[1].id);
  assert.deepEqual(rowsFromView({}), []);
});

test('dirty tracking compares content, not ids', () => {
  const rows = rowsFromView({ entries: [{ filename: 'a.mid', title: 'T' }] });
  const original = snapshot(rows);
  assert.equal(isDirty(rows, original), false);
  assert.equal(isDirty(rowsFromView({ entries: [{ filename: 'a.mid', title: 'T' }] }), original), false);
  rows[0].title = 'U';
  assert.equal(isDirty(rows, original), true);
});

test('missingFiles and addMissing add one row per unlisted file, case-insensitively', () => {
  const rows = [row('A.MID', { title: 'x' })];
  assert.deepEqual(missingFiles(rows, FILES), ['B.mid', 'c.mid']);
  assert.equal(addMissing(rows, FILES), 2);
  assert.deepEqual(rows.map(r => r.filename), ['A.MID', 'B.mid', 'c.mid']);
  assert.equal(addMissing(rows, FILES), 0);
  assert.equal(rows.length, 3);
});

test('fileChoices offers the own file and the unused ones, keeps a vanished file visible', () => {
  const rows = [row('a.mid'), row('c.mid'), row('gone.mid'), row('')];
  assert.deepEqual(fileChoices(rows, FILES, 0), ['a.mid', 'B.mid']);
  assert.deepEqual(fileChoices(rows, FILES, 3), ['B.mid']);
  assert.deepEqual(fileChoices(rows, FILES, 2), ['gone.mid', 'B.mid']);
});

test('bulkSet sets a field on all rows or only the empty ones and counts the changes', () => {
  const rows = [row('a.mid', { author: 'Keep' }), row('B.mid'), row('c.mid', { author: '  ' })];
  assert.equal(bulkSet(rows, 'author', 'Bach', true), 2);
  assert.deepEqual(rows.map(r => r.author), ['Keep', 'Bach', 'Bach']);
  assert.equal(bulkSet(rows, 'author', 'Bach', false), 1);
  assert.equal(rows[0].author, 'Bach');
  assert.throws(() => bulkSet(rows, 'filename', 'x', false));
});

test('removeRow removes in place and ignores bad indexes', () => {
  const rows = [row('a.mid'), row('B.mid')];
  removeRow(rows, 5); removeRow(rows, -1);
  assert.equal(rows.length, 2);
  removeRow(rows, 0);
  assert.deepEqual(rows.map(r => r.filename), ['B.mid']);
});

test('validateRows reports a missing, duplicate, unknown or too long entry', () => {
  const rows = [
    row('a.mid', { title: 'ok' }), row(''), row('A.MID'), row('nope.mid'),
    row('c.mid', { title: 'x'.repeat(MAX_FIELD + 1) }), row('B.mid')
  ];
  assert.deepEqual(validateRows(rows, FILES), {
    1: 'listjson.err.noFile', 2: 'listjson.err.duplicate', 3: 'listjson.err.unknownFile', 4: 'listjson.err.tooLong'
  });
  assert.deepEqual(validateRows([row('a.mid')], FILES), {});
  assert.deepEqual(validateRows([row('whatever.mid')], []), {});   // no file list known: the server decides
});

test('toBody trims, drops rows with no metadata or no file and carries the version', () => {
  const rows = [
    row(' a.mid ', { author: ' X ', title: ' T ' }),
    row('B.mid'),
    row('', { title: 'orphan' }),
    row('c.mid', { transcriber: 'R' })
  ];
  assert.deepEqual(toBody(rows, 'v1'), {
    version: 'v1',
    entries: [
      { filename: 'a.mid', author: 'X', partOf: '', title: 'T', transcriber: '' },
      { filename: 'c.mid', author: '', partOf: '', title: '', transcriber: 'R' }
    ]
  });
  assert.deepEqual(toBody([], 'none'), { version: 'none', entries: [] });
});

// ---- sections (index / start) and generated proposals ----

test('parseTime and formatTime round trip mm:ss.fff and tolerate the usual spellings', () => {
  assert.equal(parseTime('03:27.500'), 207500);
  assert.equal(parseTime('1:02:03.25'), 3723250);
  assert.equal(parseTime('12.5'), 12500);
  assert.equal(parseTime('75:03,120'), 4503120);
  for (const bad of ['', 'x', '1:2:3:4', '00:61x', '-1', '1.2.3']) assert.equal(parseTime(bad), -1, bad);
  assert.equal(formatTime(207500), '03:27.500');
  assert.equal(formatTime(4503120), '75:03.120');
  assert.equal(formatTime(parseTime('5')), '00:05.000');
});

test('rows carry index and start through the view, the snapshot and the body', () => {
  const rows = rowsFromView({ entries: [{ filename: 'd.flac', index: 2, start: '03:27.500', title: 'Two' }, { filename: 'd.flac', title: 'Album' }] });
  assert.equal(rows[0].index, '2');
  assert.equal(rows[1].index, '');
  const snap = snapshot(rows);
  assert.deepEqual(rowsFromSnapshot(snap).map(r => [r.filename, r.index, r.start, r.title]),
    [['d.flac', '2', '03:27.500', 'Two'], ['d.flac', '', '', 'Album']]);
  assert.deepEqual(toBody(rows, 'v').entries, [
    { filename: 'd.flac', index: 2, start: '03:27.500', author: '', partOf: '', title: 'Two', transcriber: '' },
    { filename: 'd.flac', author: '', partOf: '', title: 'Album', transcriber: '' }
  ]);
  rows[0].index = '3';
  assert.equal(isDirty(rows, snap), true);
});

test('several section rows may share a file; a whole-file row still excludes the file for others', () => {
  const rows = [row('d.flac', { index: '1', title: 'a' }), row('d.flac', { index: '2', title: 'b' }), row('d.flac', { title: 'album' }), row('x.mid')];
  assert.deepEqual(validateRows(rows, ['d.flac', 'x.mid']), {});
  rows.push(row('d.flac', { index: '02' }));
  assert.deepEqual(validateRows(rows, ['d.flac', 'x.mid']), { 4: 'listjson.err.duplicate' });
  assert.deepEqual(fileChoices([row('d.flac', { index: '1' }), row('d.flac', { index: '2' })], ['d.flac', 'x.mid'], 1), ['d.flac', 'x.mid']);
  assert.deepEqual(fileChoices([row('d.flac'), row('')], ['d.flac', 'x.mid'], 1), ['x.mid']);
});

test('validateRows checks index and start and accepts a legacy file#n name', () => {
  const files = ['d.flac'];
  assert.deepEqual(validateRows([row('d.flac', { index: '0' })], files), { 0: 'listjson.err.index' });
  assert.deepEqual(validateRows([row('d.flac', { index: 'x' })], files), { 0: 'listjson.err.index' });
  assert.deepEqual(validateRows([row('d.flac', { start: '7:xx' })], files), { 0: 'listjson.err.start' });
  assert.deepEqual(validateRows([row('d.flac', { start: '01:02.5' })], files), {});
  assert.deepEqual(validateRows([row('D.flac#2')], files), {});
});

const PROPOSAL = [
  { filename: 'd.flac', index: 1, start: '00:00.000', author: 'Band', partOf: 'Live', title: 'Opening', transcriber: null },
  { filename: 'd.flac', index: 2, start: '00:03.493', author: 'Guest', partOf: 'Live', title: 'Middle', transcriber: null },
  { filename: 'd.flac', index: 3, start: '00:06.000', author: 'Band', partOf: 'Live', title: 'Finale', transcriber: null }
];

test('mergeGenerated appends new sections and fills only empty fields of existing rows', () => {
  const rows = [row('d.flac', { index: '2', title: 'My title' }), row('x.mid', { title: 'keep' })];
  assert.deepEqual(previewGenerated(rows, PROPOSAL), { added: 2, existing: 1, withData: 1 });
  const r = mergeGenerated(rows, PROPOSAL, false);
  assert.deepEqual(r, { added: 2, filled: 1, replaced: 0, kept: 0 });
  assert.equal(rows.length, 4);
  assert.equal(rows[0].title, 'My title');   // user-edited value kept
  assert.equal(rows[0].author, 'Guest');     // empty field filled
  assert.equal(rows[0].start, '00:03.493');  // locator completed
  assert.deepEqual(rows.slice(2).map(x => [x.index, x.title, x.generated]), [['1', 'Opening', true], ['3', 'Finale', true]]);
  assert.equal(rows[1].generated, undefined);
});

test('mergeGenerated with overwrite replaces edited values, otherwise reports the rows it left alone', () => {
  const mk = () => [row('d.flac', { index: '1', title: 'Opening', author: 'Band', partOf: 'Live' }), row('d.flac', { index: '2', title: 'Mine' })];
  let rows = mk();
  assert.deepEqual(mergeGenerated(rows, PROPOSAL.slice(0, 2), false), { added: 0, filled: 1, replaced: 0, kept: 1 });
  rows = mk();
  assert.deepEqual(mergeGenerated(rows, PROPOSAL.slice(0, 2), true), { added: 0, filled: 0, replaced: 1, kept: 1 });
  assert.equal(rows[1].title, 'Middle');
});

test('mergeGenerated matches by start (within the tolerance), by legacy name, and leaves whole-file rows alone', () => {
  const rows = [row('d.flac', { start: '00:03.800', title: 'by start' }), row('d.flac#3', { title: 'legacy' }), row('d.flac', { title: 'album' })];
  const r = mergeGenerated(rows, PROPOSAL, false);
  assert.equal(r.added, 1);   // only track 1 is new
  assert.equal(rows[0].index, '2');
  assert.equal(rows[1].filename, 'd.flac');
  assert.equal(rows[1].index, '3');
  assert.equal(rows[1].title, 'legacy');
  assert.equal(rows[2].index, '');
  assert.equal(rows[2].author, '');
  const far = [row('d.flac', { start: '00:05.000', title: 'x' })];
  assert.equal(mergeGenerated(far, [PROPOSAL[1]], false).added, 1);
});

// ---- per-file sections list ----

test('sidecarName is the full file name plus .json; splitFilePath and sectionsRoute address the file', () => {
  assert.equal(sidecarName('concert.flac'), 'concert.flac.json');
  assert.deepEqual(splitFilePath('main/Live/concert.flac'), { dir: 'main/Live', name: 'concert.flac' });
  assert.deepEqual(splitFilePath('main'), { dir: '', name: 'main' });
  assert.deepEqual(splitFilePath(undefined), { dir: '', name: '' });
  assert.deepEqual(sectionsRoute('main/Live/concert.flac'), { path: '/sections', query: { p: 'main/Live/concert.flac' } });
});

test('rowsForFile makes every row about the one file, whatever the entry says', () => {
  const rows = rowsForFile({ fileName: 'Concert.FLAC', entries: [{ filename: 'concert.flac', index: 2, start: '00:03.000', title: 'Two' }, { author: 'Band', partOf: null }] });
  assert.deepEqual(rows.map(r => r.filename), ['Concert.FLAC', 'Concert.FLAC']);
  assert.equal(rows[0].index, '2');
  assert.equal(rows[1].author, 'Band');
  assert.deepEqual(rowsForFile({ fileName: 'a.flac' }), []);
});

test('nextSectionIndex / addSection number new sections after the highest one', () => {
  assert.equal(nextSectionIndex([]), 1);
  const rows = [row('a.flac', { index: '1' }), row('a.flac', { index: '4' }), row('a.flac', { title: 'album' }), row('a.flac', { index: 'x' })];
  assert.equal(nextSectionIndex(rows), 5);
  const added = addSection(rows, 'a.flac');
  assert.equal(added.index, '5');
  assert.equal(added.filename, 'a.flac');
  assert.equal(rows.length, 5);
});

test('a file editor round trip: rows from the view validate against the one file and produce the PUT body', () => {
  const view = { fileName: 'a.flac', version: 'v1', files: ['a.flac'], entries: [{ filename: 'a.flac', index: 1, start: '0:0', title: 'One' }] };
  const rows = rowsForFile(view);
  assert.deepEqual(validateRows(rows, view.files), {});
  addSection(rows, view.fileName).title = 'Two';
  assert.deepEqual(validateRows(rows, view.files), {});
  const body = toBody(rows, view.version);
  assert.equal(body.version, 'v1');
  assert.deepEqual(body.entries.map(e => [e.filename, e.index, e.title]), [['a.flac', 1, 'One'], ['a.flac', 2, 'Two']]);
  rows[1].index = '1';
  assert.equal(validateRows(rows, view.files)[1], 'listjson.err.duplicate');
});
