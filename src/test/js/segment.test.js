import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatSegmentRange, segmentOrigin } from '../../main/resources/web/js/core/format.js';

test('formatSegmentRange: start and end of a cue / chapter track as clocks', () => {
  assert.equal(formatSegmentRange({ startMillis: 65000, endMillis: 272000 }), '1:05 - 4:32');
  assert.equal(formatSegmentRange({ startMillis: 0, endMillis: 3723000 }), '0:00 - 1:02:03');
});

test('formatSegmentRange: empty for normal tracks and broken input', () => {
  assert.equal(formatSegmentRange(null), '');
  assert.equal(formatSegmentRange(undefined), '');
  assert.equal(formatSegmentRange({ startMillis: 'x', endMillis: 5 }), '');
  assert.equal(formatSegmentRange({ startMillis: -5000, endMillis: 5000 }), '0:00 - 0:05');
});

test('segmentOrigin: the origin text or empty', () => {
  assert.equal(segmentOrigin({ origin: 'cue' }), 'cue');
  assert.equal(segmentOrigin({ origin: 'chapters' }), 'chapters');
  assert.equal(segmentOrigin(null), '');
  assert.equal(segmentOrigin({}), '');
});
