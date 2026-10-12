/* REST client and shared session state (ES module). */
import { reactive } from './vue.js';
import { qs } from './core/urls.js';
import { formatsParam } from './core/formats.js';
import { createPager } from './core/pager.js';
import { decorateEntries } from './core/track.js';

export class ApiError extends Error {
  constructor(status, body) {
    super((body && body.message) || ('HTTP ' + status));
    this.status = status;
    this.code = (body && body.code) || 'error';
    this.details = body ? body.details : null;
  }
}

export const session = reactive({
  loaded: false,
  authenticated: false,
  id: null,
  username: null,
  displayName: null,
  role: null,
  mustChange: false,
  csrfToken: null,
  allowedRoots: [],
  downloadAllowed: false,
  minPasswordLength: 10
});

export function applySession(s) {
  session.loaded = true;
  session.authenticated = !!(s && s.authenticated);
  session.id = s && s.id || null;
  session.username = s && s.username || null;
  session.displayName = s && (s.displayName || s.username) || null;
  session.role = s && s.role || null;
  session.mustChange = !!(s && s.mustChange);
  session.csrfToken = s && s.csrfToken || null;
  session.allowedRoots = s && s.allowedRoots || [];
  session.downloadAllowed = !!(s && s.downloadAllowed);
  if (s && s.minPasswordLength) session.minPasswordLength = s.minPasswordLength;
}

export const Api = {
  /** Hook set by main.js: called when the server says the session is gone or the password must change. */
  onAuthLost: null,
  /** Formats the browser plays (set after negotiation); sent with library calls so `playability` matches. */
  formats: [],

  async request(method, path, body, options) {
    const opts = options || {};
    const headers = { 'X-Requested-With': 'kendar' };
    if (session.csrfToken) headers['X-CSRF-Token'] = session.csrfToken;
    if (opts.ifNoneMatch) headers['If-None-Match'] = opts.ifNoneMatch;
    let payload;
    if (opts.raw) {
      payload = body;
    } else if (body !== undefined && body !== null) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const response = await fetch('/api/v1' + path, {
      method: method, headers: headers, body: payload, credentials: 'same-origin', cache: 'no-store',
      keepalive: !!opts.keepalive
    });
    if (opts.meta) { opts.meta.etag = response.headers.get('ETag'); opts.meta.status = response.status; }
    if (response.status === 304 || response.status === 204) return null;
    let data = null;
    const text = await response.text();
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = { message: text.slice(0, 200) }; }
    }
    if (!response.ok) {
      const err = new ApiError(response.status, data);
      if (this.onAuthLost && (err.code === 'unauthenticated' || err.code === 'password_change_required')) {
        this.onAuthLost(err);
      }
      throw err;
    }
    return data;
  },

  get(path) { return this.request('GET', path); },
  post(path, body) { return this.request('POST', path, body === undefined ? {} : body); },
  put(path, body) { return this.request('PUT', path, body); },
  del(path) { return this.request('DELETE', path); },
  upload(path, file) { return this.request('PUT', path, file, { raw: true }); },

  q: qs,

  // session
  async loadSession() {
    let s = await this.get('/auth/session');
    if (!s.authenticated) {
      // a remember-me cookie (if any) signs the user in again; without one the call just answers 401
      try { s = await this.request('POST', '/auth/refresh', {}); } catch (e) { /* not remembered */ }
    }
    applySession(s);
    return session;
  },
  async login(username, password, remember) {
    applySession(await this.request('POST', '/auth/login',
      { username: username, password: password, remember: !!remember }));
  },
  async logout() {
    try { await this.request('POST', '/auth/logout', {}); } finally { applySession({ authenticated: false }); }
  },
  async changePassword(currentPassword, newPassword) {
    applySession(await this.post('/me/password', { currentPassword: currentPassword, newPassword: newPassword }));
  },
  invite(token) { return this.get('/auth/invite/' + encodeURIComponent(token)); },
  async acceptInvite(token, password, remember) {
    applySession(await this.request('POST', '/auth/invite/accept',
      { token: token, password: password, remember: !!remember }));
  },

  // ---- library (player, any signed-in user) ----
  library(path, params) {
    const query = qs(Object.assign({}, params, { formats: formatsParam(this.formats) }));
    return this.get('/library' + path + (query ? '?' + query : ''));
  },
  roots() { return this.library('/roots'); },
  folderPage(path, params) { return this.library('/folders', Object.assign({ path: path }, params)); },
  groups(view, params) { return this.library('/' + view, params); },
  group(view, id, params) { return this.library('/' + view + '/' + encodeURIComponent(id), params); },
  songs(params) { return this.library('/songs', params); },
  recent(params) { return this.library('/recent', params); },
  random(params) { return this.library('/random', params); },
  search(q, kind, params) { return this.library('/search', Object.assign({ q: q, kind: kind }, params)); },
  suggest(q, limit) { return this.library('/search/suggest', { q: q, limit: limit }); },

  /** Every track of a group item (album, artist, genre or an album below an artist). */
  async groupTracks(item) {
    const pager = createPager(async p => {
      const r = await this.library('/' + item.view + '/' + encodeURIComponent(item.id) + '/tracks', p);
      return { items: r.tracks, total: r.total };
    }, { limit: 200 });
    return pager.loadAll(100);
  },
  /** The tracks directly inside a folder. */
  async folderTracks(path) {
    const out = [];
    for (let offset = 0; offset < 100000;) {
      const r = await this.folderPage(path, { offset: offset, limit: 200 });
      for (const c of r.children) if (c.type === 'track') out.push(c.track);
      offset += r.children.length;
      if (!r.children.length || offset >= r.total) break;
    }
    return out;
  },

  // ---- stream ----
  postFormats(formats) { return this.post('/stream/formats', { formats: formats }); },
  prefetch(path) { return this.post('/stream/prefetch', { path: path }); },

  // ---- personal data (/me, /shared) ----
  /** Decorates the track of every entry with the playability the browser derives from its formats. */
  _decorate(r) {
    if (r && r.entries) r.entries = decorateEntries(r.entries, this.formats);
    if (r && r.items && r.items.length && r.items[0].path !== undefined && 'status' in r.items[0]) r.items = decorateEntries(r.items, this.formats);
    return r;
  },
  myPlaylists() { return this.get('/me/playlists'); },
  async myPlaylist(id, params) { return this._decorate(await this.get('/me/playlists/' + encodeURIComponent(id) + (params ? '?' + qs(params) : ''))); },
  createMyPlaylist(name, tracks, description) {
    const body = { name: name };
    if (description) body.description = description;
    if (tracks) body.tracks = tracks;
    return this.post('/me/playlists', body);
  },
  /** body: {name?, description?, tracks?, version?}. A stale version answers 409 conflict. */
  updateMyPlaylist(id, body) { return this.put('/me/playlists/' + encodeURIComponent(id), body); },
  deleteMyPlaylist(id) { return this.del('/me/playlists/' + encodeURIComponent(id)); },
  addToMyPlaylist(id, paths, position) {
    const body = { paths: paths };
    if (position !== undefined && position !== null) body.position = position;
    return this.post('/me/playlists/' + encodeURIComponent(id) + '/tracks', body);
  },
  shareMyPlaylist(id, on, version) {
    return this.post('/me/playlists/' + encodeURIComponent(id) + (on ? '/share' : '/unshare'), version === undefined ? {} : { version: version });
  },
  fixMyPlaylistPath(id, index, path, version) {
    const body = { index: index, path: path };
    if (version !== undefined) body.version = version;
    return this.post('/me/playlists/' + encodeURIComponent(id) + '/fix-path', body);
  },
  copyPlaylistFrom(source, name) { return this.post('/me/playlists/copy', Object.assign({}, source, name ? { name: name } : {})); },
  sharedPlaylists() { return this.get('/shared/playlists'); },
  async sharedPlaylist(owner, id, params) {
    return this._decorate(await this.get('/shared/playlists/' + encodeURIComponent(owner) + '/' + encodeURIComponent(id) + (params ? '?' + qs(params) : '')));
  },
  systemPlaylists() { return this.get('/shared/system'); },
  async systemPlaylist(key, params) { return this._decorate(await this.get('/shared/system/' + encodeURIComponent(key) + (params ? '?' + qs(params) : ''))); },
  async favorites(params) { return this._decorate(await this.get('/me/favorites' + (params ? '?' + qs(params) : ''))); },
  addFavorites(paths) { return this.post('/me/favorites', { paths: paths }); },
  removeFavorites(paths) { return this.request('DELETE', '/me/favorites', { paths: paths }); },
  /** Stored queue; with an etag the answer is {notModified: true} when nothing changed. */
  async queue(etag) {
    const meta = {};
    const data = await this.request('GET', '/me/queue', undefined, { ifNoneMatch: etag, meta: meta });
    if (meta.status === 304) return { notModified: true, etag: meta.etag || etag };
    return { notModified: false, etag: meta.etag, data: this._decorate(data) };
  },
  putQueue(payload, options) { return this.request('PUT', '/me/queue', payload, options); },
  reportPlay(report) { return this.post('/me/history', report); },
  forgetRecent(path) { return this.del('/me/history/recent?' + qs({ path: path })); },
  forgetMostPlayed(path) { return this.del('/me/history/most-played?' + qs({ path: path })); },
  async recentlyPlayed(params) { return this._decorate(await this.get('/me/history/recent' + (params ? '?' + qs(params) : ''))); },
  async mostPlayed(params) { return this._decorate(await this.get('/me/history/most-played' + (params ? '?' + qs(params) : ''))); },

  // ---- admin: users and moderation ----
  adminUsers() { return this.get('/admin/users'); },
  adminCreateUser(body) { return this.post('/admin/users', body); },
  adminUpdateUser(id, body) { return this.put('/admin/users/' + encodeURIComponent(id), body); },
  adminDeleteUser(id) { return this.del('/admin/users/' + encodeURIComponent(id)); },
  adminInvite(id) { return this.post('/admin/users/' + encodeURIComponent(id) + '/invite', {}); },
  adminTemporaryPassword(id) { return this.post('/admin/users/' + encodeURIComponent(id) + '/temporary-password', {}); },
  adminSharedPlaylists() { return this.get('/admin/shared-playlists'); },
  adminUnshare(owner, id) { return this.post('/admin/shared-playlists/' + encodeURIComponent(owner) + '/' + encodeURIComponent(id) + '/unshare', {}); },
  adminDeleteShared(owner, id) { return this.del('/admin/shared-playlists/' + encodeURIComponent(owner) + '/' + encodeURIComponent(id)); },

  // ---- admin: library ----
  tree() { return this.get('/admin/folders/tree'); },
  folder(path) { return this.get('/admin/folders/contents?' + this.q({ path: path })); },
  saveSettings(path, settings) { return this.put('/admin/folders/settings?' + this.q({ path: path }), settings); },
  /** Writes the album fields into exactly the given file names (the server refuses an empty or missing list). */
  syncToTags(path, files) { return this.post('/admin/folders/sync-to-tags?' + this.q({ path: path }), { files: files }); },
  /** preview=true: only computes the album from the tags (nothing is written); the caller saves explicitly. */
  syncFromTags(path, preview) {
    return this.post('/admin/folders/sync-from-tags?' + this.q(preview ? { path: path, preview: 'true' } : { path: path }));
  },
  /** The directory's list.json: {exists, version, entries, files, missing, unknown, warnings, error}. */
  listJson(path) { return this.get('/admin/folders/list?' + this.q({ path: path })); },
  /** Replaces the entries ({version, entries}); an empty list deletes the file; 409 when the version is stale. */
  saveListJson(path, body) { return this.put('/admin/folders/list?' + this.q({ path: path }), body); },
  /** Preview of the entries the directory's cue sheets / chapters give: {entries, sources, warnings}. Writes nothing. */
  generateListJson(path) { return this.post('/admin/folders/list/generate?' + this.q({ path: path })); },
  /** The sections list of one audio file (path = the file): {exists, version, fileName, sidecar, split, entries, files, warnings, error}. */
  fileList(path) { return this.get('/admin/folders/list/file?' + this.q({ path: path })); },
  /** Replaces the file's entries ({version, entries}); an empty list deletes `<file>.json`; 409 when the version is stale. */
  saveFileList(path, body) { return this.put('/admin/folders/list/file?' + this.q({ path: path }), body); },
  /** Preview of the entries the file's cue sheet / chapters give: {entries, sources, warnings}. Writes nothing. */
  generateFileList(path) { return this.post('/admin/folders/list/file/generate?' + this.q({ path: path })); },
  uploadArt(path, kind, file) { return this.upload('/admin/folders/art?' + this.q({ path: path, kind: kind }), file); },
  artUrl(path, kind, version) { return '/api/v1/admin/folders/art?' + this.q({ path: path, kind: kind, v: version }); },
  track(path) { return this.get('/admin/tracks?' + this.q({ path: path })); },
  saveTrack(path, edit) { return this.put('/admin/tracks?' + this.q({ path: path }), edit); },
  saveTracks(paths, edit) { return this.post('/admin/tracks/batch', { paths: paths, edit: edit }); },
  adminSearch(q, kind, scope) { return this.get('/admin/search?' + this.q({ q: q, kind: kind, scope: scope })); },

  // ---- admin: system playlists ----
  playlists() { return this.get('/admin/playlists'); },
  playlist(key) { return this.get('/admin/playlists/' + encodeURIComponent(key)); },
  createPlaylist(name) { return this.post('/admin/playlists', { name: name }); },
  renamePlaylist(key, name) { return this.put('/admin/playlists/' + encodeURIComponent(key), { name: name }); },
  setSongs(key, songs) { return this.put('/admin/playlists/' + encodeURIComponent(key), { songs: songs }); },
  deletePlaylist(key) { return this.del('/admin/playlists/' + encodeURIComponent(key)); },
  addSongs(key, paths) { return this.post('/admin/playlists/' + encodeURIComponent(key) + '/songs', { paths: paths }); },
  fixPath(key, index, path) {
    return this.post('/admin/playlists/' + encodeURIComponent(key) + '/fix-path', { index: index, path: path });
  },

  // ---- admin: server ----
  rescan(path, force) {
    const body = path ? { path: path } : {};
    if (force) body.force = true;
    return this.post('/admin/rescan', body);
  },
  scan() { return this.get('/admin/scan'); },
  unsupported() { return this.get('/admin/unsupported'); },
  invalidSettings() { return this.get('/admin/invalid-settings'); },
  streams() { return this.get('/admin/streams'); },
  snapshots() { return this.get('/admin/snapshots'); },
  version() { return this.get('/admin/version'); }
};
