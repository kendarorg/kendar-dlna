/*
 * Personal data UI (plan milestones 6 and 7): own, shared and system playlists, favorites, history, "add to playlist",
 * "save queue as playlist", Home quick actions, continue listening. Pages live in pages/personal.js, the browser glue
 * (play reporting, queue sync, resume) in personal/runtime.js, the logic in core/*.js.
 */
import { registerRoute, registerNavItem, registerTrackAction, registerQueueAction, registerHomeSection } from '../extensions.js';
import { PlaylistListPage, PlaylistDetailPage, FavoritesPage, HistoryPage, HomeQuick, makeHomeList } from '../pages/personal.js';
import { favorites } from '../personal/favorites.js';
import { ui, toast } from '../state.js';
import { t, tn } from '../i18n.js';
import { suggestName } from '../core/playlist-ops.js';

const GROUP_KINDS = ['album', 'artist', 'genre', 'folder', 'playlist'];

function isFav(subject) { return !!subject.track && favorites.has(subject.track.path); }

export function install() {
  // routes
  registerRoute({ path: '/me/playlists', component: PlaylistListPage, meta: { scope: 'mine' } });
  registerRoute({ path: '/me/playlists/:id', component: PlaylistDetailPage, meta: { scope: 'mine', keyed: true } });
  registerRoute({ path: '/shared', component: PlaylistListPage, meta: { scope: 'shared' } });
  registerRoute({ path: '/shared/:owner/:id', component: PlaylistDetailPage, meta: { scope: 'shared', keyed: true } });
  registerRoute({ path: '/system', component: PlaylistListPage, meta: { scope: 'system' } });
  registerRoute({ path: '/system/:key', component: PlaylistDetailPage, meta: { scope: 'system', keyed: true } });
  registerRoute({ path: '/me/favorites', component: FavoritesPage });
  registerRoute({ path: '/me/history', component: HistoryPage, meta: { kind: 'recent' } });
  registerRoute({ path: '/me/most-played', component: HistoryPage, meta: { kind: 'most' } });

  // sidebar
  registerNavItem({ id: 'favorites', to: '/me/favorites', label: 'nav.favorites', icon: 'heart', section: 'personal', order: 0 });
  registerNavItem({ id: 'history', to: '/me/history', label: 'nav.history', icon: 'recent', section: 'personal', order: 10 });
  registerNavItem({ id: 'mostPlayed', to: '/me/most-played', label: 'nav.mostPlayed', icon: 'trophy', section: 'personal', order: 20 });
  registerNavItem({ id: 'myPlaylists', to: '/me/playlists', label: 'nav.myPlaylists', icon: 'playlist', section: 'personal', order: 30 });
  registerNavItem({ id: 'sharedPlaylists', to: '/shared', label: 'nav.sharedPlaylists', icon: 'people', section: 'personal', order: 40 });
  registerNavItem({ id: 'systemPlaylists', to: '/system', label: 'nav.systemPlaylists', icon: 'queue', section: 'personal', order: 50 });

  // menus: heart, add to playlist (tracks, albums, artists, genres, folders, playlists)
  registerTrackAction({
    id: 'favorite', order: 44,
    icon: s => (isFav(s) ? 'heart' : 'heartOutline'),
    label: s => (isFav(s) ? 'action.unfavorite' : 'action.favorite'),
    visible: s => s.kind === 'track' && !!s.track,
    run: s => favorites.toggle(s.track.path)
      .then(on => toast(t(on ? 'toast.favAdded' : 'toast.favRemoved'))).catch(e => toast(e.message))
  });
  registerTrackAction({
    id: 'favoriteAll', order: 44, icon: 'heartOutline', label: 'action.favoriteAll',
    visible: s => GROUP_KINDS.includes(s.kind),
    run: s => s.tracks().then(tracks => {
      if (!tracks.length) { toast(t('toast.nothing')); return null; }
      return favorites.addAll(tracks.map(x => x.path)).then(n => toast(tn('toast.favAddedMany', n)));
    }).catch(e => toast(e.message))
  });
  registerTrackAction({
    id: 'addToPlaylist', order: 45, icon: 'playlist', label: 'action.addToPlaylist',
    visible: s => !!s.tracks,
    run: s => { ui.addTo = { title: s.title, tracks: s.tracks }; }
  });

  // the Now playing panel
  registerQueueAction({
    id: 'savePlaylist', label: 'queue.saveAsPlaylist',
    run: (snapshot, tracks) => { ui.addTo = { title: t('queue.title'), suggest: suggestName(t('queue.title')), tracks: async () => tracks }; }
  });

  // Home
  registerHomeSection({ id: 'quick', order: 0, top: true, component: HomeQuick });
  registerHomeSection({ id: 'favorites', order: 10, component: makeHomeList('favorites') });
  registerHomeSection({ id: 'history', order: 20, component: makeHomeList('history') });
}
