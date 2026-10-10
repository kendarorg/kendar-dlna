/*
 * Track/album/artist/folder actions (the "..." menu, plan section 8). A subject describes what the menu is about:
 *   {kind, title, track?, item?, path?, tracks(): Promise<track[]>}
 * Extensions add entries through registerTrackAction (extensions.js).
 */
import { Api, session } from './api.js';
import { player } from './player/index.js';
import { registry } from './extensions.js';
import { ui, nav, toast } from './state.js';
import { t } from './i18n.js';
import { downloadUrl, groupRoute, folderRoute } from './core/urls.js';
import { parentPath } from './core/list.js';

export function trackSubject(track) {
  return { kind: 'track', title: track.title || track.name, track: track, tracks: async () => [track] };
}

export function groupSubject(item) {
  return { kind: item.type === 'all_tracks' ? 'album' : item.type, title: item.title, item: item, tracks: () => Api.groupTracks(item) };
}

export function folderSubject(folder) {
  return { kind: 'folder', title: folder.displayName || folder.name, path: folder.path, tracks: () => Api.folderTracks(folder.path) };
}

/** A playlist (own, shared or system); loadTracks() resolves to its playable tracks. */
export function playlistSubject(title, loadTracks, extra) {
  return Object.assign({ kind: 'playlist', title: title, tracks: loadTracks }, extra || {});
}

function go(location) { if (nav.router) nav.router.push(location); }

async function withTracks(subject, fn) {
  try {
    const tracks = await subject.tracks();
    if (!tracks.length) { toast(t('toast.nothing')); return; }
    fn(tracks);
  } catch (e) { toast(e.message); }
}

/** Finds the library group (album/artist) of a track by name: track objects carry no group id. */
async function findGroup(kind, text, artist) {
  const r = await Api.search(text, kind, { limit: 20 });
  const items = (kind === 'album' ? r.albums : r.artists).items;
  const norm = s => (s || '').trim().toLowerCase();
  return items.find(g => norm(g.title) === norm(text) && (!artist || norm(g.artist) === norm(artist)))
    || items.find(g => norm(g.title) === norm(text)) || items[0] || null;
}

async function goToGroup(kind, text, artist) {
  try {
    const g = await findGroup(kind, text, artist);
    if (g) go(groupRoute(g)); else toast(t('toast.nothing'));
  } catch (e) { toast(e.message); }
}

const builtin = [
  { id: 'playNow', order: 10, icon: 'play', label: 'action.playNow', kinds: ['track'],
    run: s => player.playNow(s.track) },
  { id: 'play', order: 10, icon: 'play', label: 'action.play', kinds: ['album', 'artist', 'genre', 'folder', 'playlist'],
    run: s => withTracks(s, tracks => player.playTracks(tracks, 0)) },
  { id: 'playNext', order: 20, icon: 'add', label: 'action.playNext', kinds: ['track', 'album', 'artist', 'genre', 'folder', 'playlist'],
    run: s => withTracks(s, tracks => { player.playNext(tracks); toast(t('toast.addedNext')); }) },
  { id: 'enqueue', order: 30, icon: 'queue', label: 'action.addToQueue', kinds: ['track', 'album', 'artist', 'genre', 'folder', 'playlist'],
    run: s => withTracks(s, tracks => { player.enqueue(tracks); toast(t('toast.addedQueue')); }) },
  { id: 'open', order: 40, icon: 'folder', label: 'action.open', kinds: ['album', 'artist', 'genre'],
    run: s => go(groupRoute(s.item)) },
  { id: 'openFolder', order: 40, icon: 'folder', label: 'action.open', kinds: ['folder'],
    run: s => go(folderRoute(s.path)) },
  { id: 'goAlbum', order: 50, icon: 'album', label: 'action.goAlbum', kinds: ['track'],
    visible: s => !!s.track.album, run: s => goToGroup('album', s.track.album, s.track.albumArtist || s.track.artist) },
  { id: 'goArtist', order: 51, icon: 'artist', label: 'action.goArtist', kinds: ['track'],
    visible: s => !!(s.track.albumArtist || s.track.artist), run: s => goToGroup('artist', s.track.albumArtist || s.track.artist) },
  { id: 'goFolder', order: 52, icon: 'folder', label: 'action.goFolder', kinds: ['track'],
    run: s => go(folderRoute(parentPath(s.track.path))) },
  { id: 'info', order: 60, icon: 'info', label: 'action.info', kinds: ['track'],
    run: s => { ui.info = s.track; } },
  { id: 'download', order: 70, icon: 'download', label: 'action.download', kinds: ['track'],
    visible: () => session.downloadAllowed || session.role === 'admin',
    run: s => {
      const a = document.createElement('a');
      a.href = downloadUrl(s.track.path);
      a.rel = 'noopener';
      document.body.appendChild(a);
      a.click();
      a.remove();
    } }
];

/** Menu entries for a subject: {id, icon, label (already translated), run()} sorted by order. */
export function actionsFor(subject) {
  const out = [];
  for (const a of builtin) {
    if (a.kinds.includes(subject.kind) && (!a.visible || a.visible(subject))) out.push(a);
  }
  for (const a of registry.trackActions) {
    if (!a.visible || a.visible(subject)) out.push(a);
  }
  return out.sort((x, y) => x.order - y.order).map(a => ({
    id: a.id, icon: typeof a.icon === 'function' ? a.icon(subject) : a.icon,
    label: t(typeof a.label === 'function' ? a.label(subject) : a.label), run: () => a.run(subject)
  }));
}

export function openMenu(event, subject) {
  const rect = event.currentTarget && event.currentTarget.getBoundingClientRect ? event.currentTarget.getBoundingClientRect() : null;
  const x = event.clientX && !(event.type === 'click' && rect) ? event.clientX : (rect ? rect.right : 0);
  const y = event.clientY && !(event.type === 'click' && rect) ? event.clientY : (rect ? rect.bottom : 0);
  ui.menu = { x: x, y: y, subject: subject };
}

/** Starts a list: optionally shuffled (random start, shuffle mode on) or in order from `start`. */
export function playList(tracks, options) {
  if (!tracks.length) return;
  const o = options || {};
  if (o.shuffle !== undefined && o.shuffle !== player.state.queue.shuffle) player.toggleShuffle();
  const start = o.shuffle ? Math.floor(Math.random() * tracks.length) : (o.start || 0);
  player.playTracks(tracks, start);
}
