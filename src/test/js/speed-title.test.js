import { test } from 'node:test';
import assert from 'node:assert/strict';
import { clampStep, rateForStep, formatRate } from '../../main/resources/web/js/core/speed.js';
import { pageTitle, APP_TITLE, scrollFrame } from '../../main/resources/web/js/core/title.js';
import { createController } from '../../main/resources/web/js/player/controller.js';
import { createPrefs } from '../../main/resources/web/js/core/prefs.js';
import { createEmitter } from '../../main/resources/web/js/core/events.js';

test('speed steps map to 0.5x..2x and are clamped', () => {
  assert.deepEqual([-2, -1, 0, 1, 2].map(rateForStep), [0.5, 0.75, 1, 1.5, 2]);
  assert.equal(clampStep(9), 2);
  assert.equal(clampStep(-9), -2);
  assert.equal(clampStep('x'), 0);
  assert.equal(rateForStep(undefined), 1);
  assert.equal(formatRate(0.75), '0.75x');
});

test('tab title is "artist - title" with sensible fallbacks', () => {
  assert.equal(pageTitle({ artist: 'A', title: 'T' }), 'A - T');
  assert.equal(pageTitle({ title: 'T' }), 'T');
  assert.equal(pageTitle({ name: 'file.mp3', artist: ' ' }), 'file.mp3');
  assert.equal(pageTitle({ artist: 'A' }), 'A');
  assert.equal(pageTitle({}), APP_TITLE);
  assert.equal(pageTitle(null), APP_TITLE);
});

test('the controller applies, persists and restores the playback speed', () => {
  const rates = [];
  const engine = {
    on: createEmitter().on, play() {}, preload() {}, resume() {}, pause() {}, stop() {}, seek() {},
    setVolume() {}, setMuted() {}, setRate(r) { rates.push(r); }
  };
  const store = new Map();
  const prefs = createPrefs({ getItem: k => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, v) });
  const player = createController({ engine, prefs, streamUrl: p => p });
  assert.equal(player.rate, 1);
  player.setSpeed(2);
  assert.equal(player.rate, 2);
  player.setSpeed(-7);
  assert.equal(player.state.speed, -2);
  assert.deepEqual(rates, [1, 2, 0.5]);
  const again = createController({ engine, prefs, streamUrl: p => p });
  assert.equal(again.rate, 0.5);
});

test('a long tab title scrolls as a loop, a short one stays still', () => {
  assert.equal(scrollFrame('A - T', 5), 'A - T');
  const long = 'A very long artist name - An even longer song title';
  assert.equal(scrollFrame(long, 0, 10), 'A very lon');
  assert.equal(scrollFrame(long, 2, 10), 'very long ');
  const n = Array.from(long).length + 7;
  assert.equal(scrollFrame(long, n, 10), scrollFrame(long, 0, 10));
  assert.equal(scrollFrame(long, n - 3, 10), '   A very ');
});
