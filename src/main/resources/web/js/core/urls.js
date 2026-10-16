/* URL building for the REST API and the router. Pure functions. */

export const API_BASE = '/api/v1';

/** Query string without the leading '?'; skips undefined, null and empty values. */
export function qs(params) {
  return Object.keys(params || {})
    .filter(k => params[k] !== undefined && params[k] !== null && params[k] !== '')
    .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(params[k])).join('&');
}

/** Appends a query to a URL or path that may already have one. */
export function withQuery(url, params) {
  const q = qs(params);
  if (!q) return url;
  return url + (url.indexOf('?') < 0 ? '?' : '&') + q;
}

export function apiUrl(path, params) {
  return withQuery(API_BASE + path, params);
}

/** Artwork: the API hands out ready-made art URLs, to which a size is appended. */
export function artUrl(art, size) {
  if (!art) return null;
  return withQuery(art, { size: size });
}

/** Audio stream of a track (root-qualified path). */
export function streamUrl(path) {
  return apiUrl('/stream', { path: path });
}

export function downloadUrl(path) {
  return apiUrl('/stream/download', { path: path });
}

/** Route location of a group item (album/artist/genre) as returned by the library API. */
export function groupRoute(item) {
  return '/' + (item.view || 'albums') + '/' + encodeURIComponent(item.id);
}

export function folderRoute(path) {
  return { path: '/folders', query: path ? { p: path } : {} };
}

/** A letter range of a folder (the folder listing with layout=ranges). */
export function rangeRoute(path, rangeId) {
  return { path: '/folders', query: rangeId ? { p: path, r: rangeId } : { p: path } };
}

export function searchRoute(q, kind) {
  return { path: '/search', query: kind ? { q: q, kind: kind } : { q: q } };
}

/** Route location for a typeahead suggestion; null for tracks (which are played instead). */
export function suggestionRoute(s) {
  if (!s) return null;
  if (s.type === 'artist' || s.type === 'album') return '/' + s.view + '/' + encodeURIComponent(s.id);
  if (s.type === 'folder') return folderRoute(s.path);
  return null;
}
