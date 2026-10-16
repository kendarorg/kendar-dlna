import { test } from 'node:test';
import assert from 'node:assert/strict';
import { activeLine, isSynced } from '../../main/resources/web/js/core/lyrics.js';

const lines = [{ t: 1000, text: 'a' }, { t: 5000, text: 'b' }, { t: 9000, text: 'c' }];

test('the active line is the last one that has started', () => {
  assert.equal(activeLine(lines, 0), -1);
  assert.equal(activeLine(lines, 1), 0);
  assert.equal(activeLine(lines, 4.99), 0);
  assert.equal(activeLine(lines, 5), 1);
  assert.equal(activeLine(lines, 100), 2);
  assert.equal(activeLine([], 3), -1);
});

test('only non-empty synced lyrics are synced', () => {
  assert.equal(isSynced({ synced: true, lines }), true);
  assert.equal(isSynced({ synced: false, lines }), false);
  assert.equal(isSynced({ synced: true, lines: [] }), false);
  assert.equal(isSynced(null), false);
});
