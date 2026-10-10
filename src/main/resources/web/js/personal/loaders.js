/* Loading whole personal/shared/system playlists and favorites for playing, copying and the "add to" dialog. */
import { Api } from '../api.js';
import { fetchAllEntries, playableTracks } from '../core/playlist-ops.js';
import { playlistSubject } from '../actions.js';

/** Every entry of a playlist summary; scope is 'mine' | 'shared' | 'system'. */
export function loadEntries(scope, p) {
  const page = scope === 'system' ? q => Api.systemPlaylist(p.key, q)
    : scope === 'shared' ? q => Api.sharedPlaylist(p.owner.id, p.id, q)
      : q => Api.myPlaylist(p.id, q);
  return fetchAllEntries(page, 1000);
}

export async function loadPlaylistTracks(scope, p) {
  return playableTracks((await loadEntries(scope, p)).entries);
}

export async function loadFavoriteTracks() {
  return playableTracks((await fetchAllEntries(q => Api.favorites(q), 1000)).entries);
}

/** Action-menu subject of a playlist summary. */
export function subjectFor(scope, p) {
  return playlistSubject(p.name, () => loadPlaylistTracks(scope, p), { scope: scope, playlist: p });
}
