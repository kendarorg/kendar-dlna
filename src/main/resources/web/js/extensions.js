/*
 * Extension registry: how later features (personal playlists/favorites/history, shared playlists, user admin)
 * plug into the shell without editing it. The modules in ./extensions/ call these functions at start-up.
 *
 *   registerNavItem({id, to, label (i18n key or text), icon, section: 'library'|'personal'|'admin', order, visible(session)})
 *   registerRoute({path, component, meta})                      extra router records ({meta: {admin: true}} = admins only)
 *   registerTrackAction({id, label, order, visible(subject), run(subject)})   entries of the track/album/folder menu
 *   registerHomeSection({id, order, component})                 blocks shown on the Home page
 *   registerQueueAction({id, label, run(snapshot, tracks)})     buttons of the Now playing panel (e.g. save as playlist)
 *
 * A subject is {kind: 'track'|'album'|'artist'|'genre'|'folder'|..., track?, item?, path?, tracks(): Promise<track[]>}.
 * Other hooks: player.events ('trackstart', 'trackend', 'queuechange', see player/controller.js) and
 * the `bus` / `ui` / `nav` objects of state.js.
 */
import { reactive, markRaw } from './vue.js';

export const registry = reactive({
  navItems: [],
  routes: [],
  trackActions: [],
  homeSections: [],
  queueActions: []
});

function add(list, entry) {
  const i = list.findIndex(e => e.id === entry.id);
  if (i >= 0) list.splice(i, 1, entry); else list.push(entry);
}

export function registerNavItem(item) { add(registry.navItems, Object.assign({ section: 'library', order: 100 }, item)); }
export function registerTrackAction(action) { add(registry.trackActions, Object.assign({ order: 100 }, action)); }
export function registerQueueAction(action) { add(registry.queueActions, action); }
export function registerHomeSection(section) {
  add(registry.homeSections, Object.assign({ order: 100 }, section, { component: markRaw(section.component) }));
}
export function registerRoute(route) {
  const i = registry.routes.findIndex(r => r.path === route.path);
  if (i >= 0) registry.routes.splice(i, 1, route); else registry.routes.push(route);
}
