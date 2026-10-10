import { test } from 'node:test';
import assert from 'node:assert/strict';
import { qs, withQuery, apiUrl, artUrl, streamUrl, downloadUrl, groupRoute, folderRoute, suggestionRoute, searchRoute }
  from '../../main/resources/web/js/core/urls.js';
import { detectFormats, formatsParam, sameFormats, negotiateFormats, CANDIDATES }
  from '../../main/resources/web/js/core/formats.js';

test('qs skips empty values and encodes the rest', () => {
  assert.equal(qs({ a: 'x y', b: '', c: null, d: undefined, e: 0, f: 'ä/&' }), 'a=x%20y&e=0&f=%C3%A4%2F%26');
  assert.equal(qs(), '');
});

test('withQuery picks ? or & and ignores empty params', () => {
  assert.equal(withQuery('/a', { x: 1 }), '/a?x=1');
  assert.equal(withQuery('/a?y=2', { x: 1 }), '/a?y=2&x=1');
  assert.equal(withQuery('/a', { x: '' }), '/a');
});

test('stream, download and api urls encode the root-qualified path', () => {
  assert.equal(streamUrl('Music/A B/01 x.flac'), '/api/v1/stream?path=Music%2FA%20B%2F01%20x.flac');
  assert.equal(downloadUrl('M/a#1.mp3'), '/api/v1/stream/download?path=M%2Fa%231.mp3');
  assert.equal(apiUrl('/library/songs', { offset: 50, limit: 50 }), '/api/v1/library/songs?offset=50&limit=50');
});

test('artUrl appends the size to the ready-made artwork url', () => {
  const art = '/api/v1/library/artwork?kind=track&path=M%2Fa.mp3';
  assert.equal(artUrl(art, 'small'), art + '&size=small');
  assert.equal(artUrl(null, 'small'), null);
  assert.equal(artUrl('/x', 'large'), '/x?size=large');
});

test('route builders', () => {
  assert.equal(groupRoute({ view: 'artists', id: 'a/b c' }), '/artists/a%2Fb%20c');
  assert.equal(groupRoute({ id: 'x' }), '/albums/x');
  assert.deepEqual(folderRoute('Music/A'), { path: '/folders', query: { p: 'Music/A' } });
  assert.deepEqual(folderRoute(null), { path: '/folders', query: {} });
  assert.deepEqual(searchRoute('abba', 'album'), { path: '/search', query: { q: 'abba', kind: 'album' } });
  assert.equal(suggestionRoute({ type: 'album', view: 'albums', id: 'z1' }), '/albums/z1');
  assert.equal(suggestionRoute({ type: 'artist', view: 'artists', id: 'z2' }), '/artists/z2');
  assert.deepEqual(suggestionRoute({ type: 'folder', path: 'M/x' }), { path: '/folders', query: { p: 'M/x' } });
  assert.equal(suggestionRoute({ type: 'track', path: 'M/x.mp3' }), null);
  assert.equal(suggestionRoute(null), null);
});

// canPlayType stand-ins
const chromeLike = mime => (/audio\/(mpeg|mp3|flac|wav|wave|x-wav|ogg; codecs="(vorbis|opus)"|opus)/.test(mime) ? 'probably' : '');
const safariLike = mime => (/audio\/(mpeg|mp3|wav|wave|x-wav)/.test(mime) ? 'maybe' : '');

test('detectFormats follows canPlayType, any candidate mime is enough', () => {
  assert.deepEqual(detectFormats(chromeLike), ['mp3', 'ogg', 'opus', 'flac', 'wav']);
  assert.deepEqual(detectFormats(safariLike), ['mp3', 'wav']);
  assert.deepEqual(detectFormats(() => ''), []);
  assert.deepEqual(detectFormats(() => { throw new Error('x'); }), []);
  assert.deepEqual(Object.keys(CANDIDATES), ['mp3', 'ogg', 'opus', 'flac', 'wav']);
});

test('formats helpers', () => {
  assert.equal(formatsParam(['mp3', 'flac']), 'mp3,flac');
  assert.equal(formatsParam(null), '');
  assert.equal(sameFormats(['a', 'b'], ['b', 'a']), true);
  assert.equal(sameFormats(['a'], ['a', 'b']), false);
});

function fakeStorage() {
  const m = new Map();
  return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, v) };
}

test('negotiateFormats posts once per session and signature', async () => {
  const storage = fakeStorage();
  const posted = [];
  const deps = { canPlay: chromeLike, post: async f => { posted.push(f); }, storage, sessionKey: 's1' };
  assert.deepEqual(await negotiateFormats(deps), ['mp3', 'ogg', 'opus', 'flac', 'wav']);
  await negotiateFormats(deps);
  assert.equal(posted.length, 1);
  await negotiateFormats({ ...deps, sessionKey: 's2' });          // a new login session posts again
  await negotiateFormats({ ...deps, canPlay: safariLike });      // a changed list posts again
  assert.equal(posted.length, 3);
  assert.deepEqual(posted[2], ['mp3', 'wav']);
});

test('negotiateFormats survives a failing post and a broken storage', async () => {
  const failing = await negotiateFormats({ canPlay: chromeLike, post: async () => { throw new Error('offline'); }, storage: fakeStorage() });
  assert.equal(failing.length, 5);
  const broken = { getItem() { throw new Error('x'); }, setItem() { throw new Error('x'); } };
  let n = 0;
  await negotiateFormats({ canPlay: chromeLike, post: async () => { n++; }, storage: broken });
  await negotiateFormats({ canPlay: chromeLike, post: async () => { n++; }, storage: broken });
  assert.equal(n, 2);
});

test('a failed post is retried on the next call', async () => {
  const storage = fakeStorage();
  let fail = true, n = 0;
  const deps = { canPlay: chromeLike, post: async () => { n++; if (fail) throw new Error('x'); }, storage };
  await negotiateFormats(deps);
  fail = false;
  await negotiateFormats(deps);
  await negotiateFormats(deps);
  assert.equal(n, 2);
});
