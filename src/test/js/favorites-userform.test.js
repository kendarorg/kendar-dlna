import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFavorites } from '../../main/resources/web/js/core/favorites.js';
import {
  emptyUserForm, formFromUser, toggleRoot, validateUserForm, toCreateBody, toUpdateBody, inviteLink, inviteDaysLeft, userStatus
} from '../../main/resources/web/js/core/user-form.js';

function fakeServer(initial) {
  const stored = initial.slice();
  const calls = [];
  return {
    stored, calls,
    list: async ({ offset, limit }) => {
      calls.push(['list', offset, limit]);
      return { total: stored.length, entries: stored.slice(offset, offset + limit).map((p, i) => ({ index: offset + i, path: p })) };
    },
    add: async paths => { calls.push(['add', paths]); for (const p of paths) if (!stored.includes(p)) stored.push(p); },
    remove: async paths => { calls.push(['remove', paths]); for (const p of paths) { const i = stored.indexOf(p); if (i >= 0) stored.splice(i, 1); } }
  };
}

test('favorites: load, has, toggle updates the cache at once and the server', async () => {
  const srv = fakeServer(['M/a', 'M/b']);
  const fav = createFavorites(srv);
  await fav.load();
  assert.equal(fav.state.loaded, true);
  assert.equal(fav.state.count, 2);
  assert.equal(fav.has('M/a'), true);
  assert.equal(await fav.toggle('M/c'), true);
  assert.equal(fav.has('M/c'), true);
  assert.equal(await fav.toggle('M/a'), false);
  assert.equal(fav.has('M/a'), false);
  assert.deepEqual(srv.stored, ['M/b', 'M/c']);
  assert.equal(fav.state.count, 2);
});

test('favorites: addAll skips existing ones and reports how many were added', async () => {
  const srv = fakeServer(['M/a']);
  const fav = createFavorites(srv);
  await fav.load();
  assert.equal(await fav.addAll(['M/a', 'M/b', 'M/b', 'M/c']), 2);
  assert.deepEqual(srv.calls.filter(c => c[0] === 'add'), [['add', ['M/b', 'M/c']]]);
  assert.equal(await fav.addAll(['M/a']), 0);
  assert.equal(srv.calls.filter(c => c[0] === 'add').length, 1, 'no request when nothing changes');
});

test('favorites: a refused change is rolled back and reported', async () => {
  const srv = fakeServer(['M/a']);
  srv.add = async () => { throw new Error('forbidden'); };
  const fav = createFavorites(srv);
  await fav.load();
  await assert.rejects(fav.toggle('M/x'), /forbidden/);
  assert.equal(fav.has('M/x'), false);
  assert.equal(fav.state.count, 1);
  assert.equal(fav.state.error, 'forbidden');
});

test('favorites: load reads every page and notifies subscribers; reset empties', async () => {
  const many = Array.from({ length: 2500 }, (_, i) => 'M/' + i);
  const srv = fakeServer(many);
  const fav = createFavorites(srv);
  let notified = 0;
  fav.onChange(() => notified++);
  await fav.load();
  assert.equal(fav.state.count, 2500);
  assert.deepEqual(srv.calls.map(c => c[1]), [0, 1000, 2000]);
  assert.ok(notified >= 1);
  fav.reset();
  assert.equal(fav.state.count, 0);
  assert.equal(fav.has('M/1'), false);
});

const ROOTS = ['Music', 'Podcasts', 'Kids'];

test('user form: the main root is preselected', () => {
  const f = emptyUserForm('Music');
  assert.deepEqual(f.allowedRoots, ['Music']);
  assert.equal(f.role, 'user');
  assert.equal(f.downloadAllowed, false);
  assert.equal(f.credential, 'invite');
  assert.deepEqual(emptyUserForm('').allowedRoots, []);
});

test('toggleRoot keeps the order of the server list', () => {
  assert.deepEqual(toggleRoot(['Kids'], 'Music', true, ROOTS), ['Music', 'Kids']);
  assert.deepEqual(toggleRoot(['Music', 'Kids'], 'Music', false, ROOTS), ['Kids']);
  assert.deepEqual(toggleRoot(['Gone'], 'Kids', true, ROOTS), ['Kids', 'Gone'], 'roots removed from the config stay visible');
});

test('validateUserForm: user name rules, roots, duplicates', () => {
  const ok = Object.assign(emptyUserForm('Music'), { username: 'lena' });
  assert.deepEqual(validateUserForm(ok, { roots: ROOTS, usernames: [] }), {});
  assert.equal(validateUserForm(Object.assign({}, ok, { username: '' }), { roots: ROOTS }).username, 'users.err.usernameRequired');
  assert.equal(validateUserForm(Object.assign({}, ok, { username: 'a' }), { roots: ROOTS }).username, 'users.err.usernameInvalid');
  assert.equal(validateUserForm(Object.assign({}, ok, { username: 'a b' }), { roots: ROOTS }).username, 'users.err.usernameInvalid');
  assert.equal(validateUserForm(Object.assign({}, ok, { username: 'x'.repeat(65) }), { roots: ROOTS }).username, 'users.err.usernameInvalid');
  assert.deepEqual(validateUserForm(Object.assign({}, ok, { username: 'a.b_c-d@e' }), { roots: ROOTS }), {});
  assert.equal(validateUserForm(Object.assign({}, ok, { username: 'Lena' }), { roots: ROOTS, usernames: ['lena'] }).username, 'users.err.usernameTaken');
  assert.equal(validateUserForm(Object.assign({}, ok, { allowedRoots: [] }), { roots: ROOTS }).allowedRoots, 'users.err.rootsRequired');
  assert.equal(validateUserForm(Object.assign({}, ok, { allowedRoots: ['Nope'] }), { roots: ROOTS }).allowedRoots, 'users.err.rootUnknown');
  assert.deepEqual(validateUserForm(Object.assign({}, ok, { role: 'admin', allowedRoots: [] }), { roots: ROOTS }), {}, 'admins need no roots');
  assert.equal(validateUserForm(Object.assign({}, ok, { role: 'boss' }), { roots: ROOTS }).role, 'users.err.role');
  assert.equal(validateUserForm(Object.assign({}, ok, { credential: 'magic' }), { roots: ROOTS }).credential, 'users.err.credential');
  assert.equal(validateUserForm(Object.assign({}, ok, { displayName: 'x'.repeat(101) }), { roots: ROOTS }).displayName, 'users.err.displayNameLong');
  // editing does not check the user name
  assert.deepEqual(validateUserForm({ username: '', displayName: '', role: 'user', allowedRoots: ['Music'] }, { roots: ROOTS, creating: false }), {});
});

test('toCreateBody trims, drops empty display names and ignores roots for admins', () => {
  const f = Object.assign(emptyUserForm('Music'), { username: ' lena ', displayName: '  ', downloadAllowed: true });
  assert.deepEqual(toCreateBody(f), { username: 'lena', role: 'user', allowedRoots: ['Music'], downloadAllowed: true, credential: 'invite' });
  const a = Object.assign(emptyUserForm('Music'), { username: 'root', displayName: 'Root', role: 'admin', credential: 'temporaryPassword' });
  assert.deepEqual(toCreateBody(a), { username: 'root', displayName: 'Root', role: 'admin', allowedRoots: [], downloadAllowed: false, credential: 'temporaryPassword' });
});

test('toUpdateBody sends only the differences', () => {
  const user = { id: 'u', username: 'lena', displayName: 'Lena', role: 'user', enabled: true, allowedRoots: ['Music', 'Kids'], downloadAllowed: false };
  assert.deepEqual(toUpdateBody(user, formFromUser(user)), {});
  const f = formFromUser(user);
  f.displayName = ' Lena L ';
  f.allowedRoots = ['Kids'];
  f.downloadAllowed = true;
  assert.deepEqual(toUpdateBody(user, f), { displayName: 'Lena L', allowedRoots: ['Kids'], downloadAllowed: true });
  const same = formFromUser(user);
  same.allowedRoots = ['Kids', 'Music'];   // same set, other order
  assert.deepEqual(toUpdateBody(user, same), {});
  const off = formFromUser(user);
  off.enabled = false;
  assert.deepEqual(toUpdateBody(user, off), { enabled: false });
});

test('invite links and expiry', () => {
  const invite = { token: 'abc', expiresAt: '2026-10-16T00:00:00Z', path: '/app/#/invite/abc' };
  assert.equal(inviteLink('http://nas:8200/', invite), 'http://nas:8200/app/#/invite/abc');
  assert.equal(inviteLink('http://nas:8200', { token: 'zz' }), 'http://nas:8200/app/#/invite/zz');
  assert.equal(inviteLink('http://nas', null), '');
  assert.equal(inviteDaysLeft(invite, Date.parse('2026-10-09T00:00:00Z')), 7);
  assert.equal(inviteDaysLeft(invite, Date.parse('2026-10-15T23:00:00Z')), 1);
  assert.equal(inviteDaysLeft(invite, Date.parse('2026-10-17T00:00:00Z')), 0);
  assert.equal(inviteDaysLeft(null), 0);
});

test('userStatus', () => {
  assert.equal(userStatus({ enabled: false }), 'disabled');
  assert.equal(userStatus({ enabled: true, pendingInvite: true }), 'invited');
  assert.equal(userStatus({ enabled: true, mustChange: true }), 'mustChange');
  assert.equal(userStatus({ enabled: true }), 'active');
});
