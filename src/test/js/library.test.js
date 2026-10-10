import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as L from '../../main/resources/web/js/core/library.js';

const roots = [
  { path: 'm', displayName: 'Music', children: [
    { path: 'm/a', displayName: 'A', children: [{ path: 'm/a/x', displayName: 'X', children: [] }] },
    { path: 'm/b', displayName: 'B', children: [] }] },
  { path: 'n', displayName: 'N', children: [] }
];

test('ancestors, expanded set handling and pruning', () => {
  assert.deepEqual(L.ancestorPaths('m/a/x'), ['m', 'm/a']);
  assert.deepEqual(L.ancestorPaths('m'), []);
  assert.deepEqual(L.ancestorPaths(null), []);
  assert.deepEqual(L.defaultExpanded(roots), ['m', 'n']);
  const base = ['n'];
  assert.deepEqual(L.withAncestors(base, 'm/a/x').sort(), ['m', 'm/a', 'n']);
  assert.deepEqual(base, ['n']);
  assert.deepEqual(L.toggleExpanded(['a'], 'a'), []);
  assert.deepEqual(L.toggleExpanded(['a'], 'b'), ['a', 'b']);
  assert.deepEqual(L.pruneExpanded(['m', 'gone', 'm/a'], roots), ['m', 'm/a']);
});

test('visibleRows follow the expanded set and carry level and parent', () => {
  const rows = L.visibleRows(roots, ['m']);
  assert.deepEqual(rows.map(r => r.path), ['m', 'm/a', 'm/b', 'n']);
  assert.deepEqual(rows.map(r => r.level), [1, 2, 2, 1]);
  assert.equal(rows[1].parent, 'm');
  assert.equal(rows[1].hasChildren, true);
  assert.equal(rows[1].open, false);
  assert.equal(L.visibleRows(roots, []).length, 2);
  assert.equal(L.visibleRows(roots, ['m', 'm/a']).length, 5);
});

test('treeKey implements the ARIA tree keys', () => {
  const rows = L.visibleRows(roots, ['m']);
  assert.deepEqual(L.treeKey('ArrowDown', rows, 'm'), { focus: 'm/a' });
  assert.deepEqual(L.treeKey('ArrowDown', rows, 'n'), { focus: 'n' });
  assert.deepEqual(L.treeKey('ArrowUp', rows, 'm'), { focus: 'm' });
  assert.deepEqual(L.treeKey('ArrowUp', rows, 'm/a'), { focus: 'm' });
  assert.deepEqual(L.treeKey('Home', rows, 'n'), { focus: 'm' });
  assert.deepEqual(L.treeKey('End', rows, 'm'), { focus: 'n' });
  assert.deepEqual(L.treeKey('ArrowRight', rows, 'm/a'), { toggle: 'm/a' });
  assert.deepEqual(L.treeKey('ArrowRight', rows, 'm'), { focus: 'm/a' });
  assert.equal(L.treeKey('ArrowRight', rows, 'm/b'), null);
  assert.deepEqual(L.treeKey('ArrowLeft', rows, 'm'), { toggle: 'm' });
  assert.deepEqual(L.treeKey('ArrowLeft', rows, 'm/a'), { focus: 'm' });
  assert.equal(L.treeKey('ArrowLeft', rows, 'n'), null);
  assert.deepEqual(L.treeKey('Enter', rows, 'm/b'), { select: 'm/b' });
  assert.equal(L.treeKey('x', rows, 'm'), null);
  assert.equal(L.treeKey('ArrowDown', [], 'm'), null);
});

test('crumbs use display names and totals add up', () => {
  const names = L.nameIndex(roots);
  assert.deepEqual(L.crumbsFor('m/a/x', names), [
    { name: 'Music', path: 'm' }, { name: 'A', path: 'm/a' }, { name: 'X', path: 'm/a/x' }]);
  assert.deepEqual(L.crumbsFor('q/r', {}), [{ name: 'q', path: 'q' }, { name: 'r', path: 'q/r' }]);
  assert.deepEqual(L.crumbsFor('', names), []);
  assert.equal(L.totalDuration([{ type: 'track', track: { durationMillis: 1000 } }, { type: 'folder' },
    { type: 'track', track: { durationMillis: 2500 } }, { type: 'track', track: {} }]), 3500);
});

test('badges of tracks and folders', () => {
  assert.deepEqual(L.trackBadges({ editable: false, editableFields: [] }).map(b => b.id), ['readonly']);
  assert.deepEqual(L.trackBadges({ editable: true, editableFields: ['title'] }).map(b => b.id), ['limited']);
  assert.deepEqual(L.trackBadges({ editable: true, editableFields: new Array(10).fill('x') }), []);
  assert.deepEqual(L.trackBadges({ editable: true, editableFields: new Array(10).fill('x'), outOfSync: true, segment: {} })
    .map(b => b.id + ':' + b.kind), ['cue:', 'sync:warn']);
  assert.deepEqual(L.trackBadges(null), []);
  assert.deepEqual(L.folderBadges({ virtual: true, hasList: true, outOfSync: true, settingsError: 'x', listError: 'y' })
    .map(b => b.id), ['cue', 'list', 'sync', 'settingsError', 'listError']);
  assert.deepEqual(L.folderBadges({}), []);
  assert.equal(L.folderBadges({ listError: 'e' })[0].kind, 'bad');
});

test('track number label', () => {
  assert.equal(L.trackNumber({ trackNo: 3 }, 0), '3');
  assert.equal(L.trackNumber({ discNo: 2, trackNo: 3 }, 0), '2.3');
  assert.equal(L.trackNumber({}, 4), '5');
  assert.equal(L.trackNumber({}), '');
});
