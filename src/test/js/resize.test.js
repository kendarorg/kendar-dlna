import { test } from 'node:test';
import assert from 'node:assert/strict';
import { clampWidth, dragWidth, keyWidth, KEY_STEP } from '../../main/resources/web/js/core/resize.js';

test('widths are clamped and rounded', () => {
  assert.equal(clampWidth(50, 180, 420), 180);
  assert.equal(clampWidth(999, 180, 420), 420);
  assert.equal(clampWidth(200.6, 180, 420), 201);
  assert.equal(clampWidth('abc', 180, 420), 180);
});

test('dragging adds the pointer movement to the start width', () => {
  assert.equal(dragWidth(240, 100, 140, 180, 420), 280);
  assert.equal(dragWidth(240, 100, 0, 180, 420), 180);
  assert.equal(dragWidth(240, 100, 900, 180, 420), 420);
});

test('arrow keys step, Home resets, other keys are ignored', () => {
  assert.equal(keyWidth(240, 'ArrowRight', 180, 420, 240), 240 + KEY_STEP);
  assert.equal(keyWidth(240, 'ArrowLeft', 180, 420, 240), 240 - KEY_STEP);
  assert.equal(keyWidth(181, 'ArrowLeft', 180, 420, 240), 180);
  assert.equal(keyWidth(300, 'Home', 180, 420, 240), 240);
  assert.equal(keyWidth(300, 'a', 180, 420, 240), null);
});
