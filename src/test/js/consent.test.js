import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CONSENT_PHRASE, consentMatches } from '../../main/resources/web/js/core/consent.js';

test('only the exact capitalised phrase is accepted', () => {
  assert.equal(CONSENT_PHRASE, 'I CONSENT');
  assert.equal(consentMatches('I CONSENT'), true);
  for (const bad of ['', 'i consent', 'I consent', 'I CONSENT ', ' I CONSENT', 'I  CONSENT', 'CONSENT']) {
    assert.equal(consentMatches(bad), false, JSON.stringify(bad));
  }
});
