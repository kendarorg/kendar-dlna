/* Routes, extension routes and the auth guard. Hash history: the same bundle is served at /app/ and /admin/. */
import { toRaw } from './vue.js';
import { createRouter, createWebHashHistory } from './vendor/vue-router.esm-browser.prod.js';
import { Api, session, applySession } from './api.js';
import { registry } from './extensions.js';
import { negotiate } from './player/index.js';
import { LoginPage, PasswordPage, InvitePage, NoAccessPage } from './pages/login.js';
import { LibraryPage } from './pages/library.js';
import { SettingsPage } from './pages/settings.js';
import { MetadataPage } from './pages/metadata.js';
import { ListJsonPage } from './pages/list-json.js';
import { PlaylistsPage, PlaylistPage } from './pages/playlists.js';
import { NavigationsPage } from './pages/navigations.js';
import { LyricsPage } from './pages/lyrics.js';
import { AdminPage } from './pages/admin.js';
import { HomePage, FoldersPage, GroupsPage, GroupDetailPage, NavigationPage, SongsPage, RecentPage, SearchPage } from './pages/browse.js';

const keyed = { keyed: true };   // remount the page when the query changes (App uses meta.keyed)

export const routes = [
  // player
  { path: '/', component: HomePage },
  { path: '/folders', component: FoldersPage, meta: keyed },
  { path: '/albums', component: GroupsPage, props: { view: 'albums' } },
  { path: '/artists', component: GroupsPage, props: { view: 'artists' } },
  { path: '/genres', component: GroupsPage, props: { view: 'genres' } },
  { path: '/:view(albums|artists|genres)/:id', component: GroupDetailPage, meta: keyed },
  { path: '/nav/:name/:id?', component: NavigationPage, meta: keyed },
  { path: '/songs', component: SongsPage },
  { path: '/recent', component: RecentPage },
  { path: '/search', component: SearchPage, meta: keyed },
  // account
  { path: '/login', component: LoginPage, meta: { public: true } },
  { path: '/password', component: PasswordPage },
  { path: '/invite/:token', component: InvitePage, meta: { public: true } },
  { path: '/noaccess', component: NoAccessPage },
  // admin pages (phase 1), same routes as before
  { path: '/library', component: LibraryPage, meta: { admin: true } },
  { path: '/settings', component: SettingsPage, meta: { admin: true } },
  { path: '/metadata', component: MetadataPage, meta: { admin: true } },
  { path: '/list-json', component: ListJsonPage, meta: { admin: true } },
  { path: '/sections', component: ListJsonPage, meta: { admin: true } },
  { path: '/playlists', component: PlaylistsPage, meta: { admin: true } },
  { path: '/navigations', component: NavigationsPage, meta: { admin: true } },
  { path: '/lyrics', component: LyricsPage, meta: { admin: true, keyed: true } },
  { path: '/playlists/:key', component: PlaylistPage, meta: { admin: true } },
  { path: '/admin', component: AdminPage, meta: { admin: true } }
];

export function createAppRouter() {
  const router = createRouter({ history: createWebHashHistory(), routes: routes });
  for (const r of registry.routes) router.addRoute(toRaw(r));
  router.addRoute({ path: '/:rest(.*)*', redirect: '/' });

  let negotiatedFor = null;

  router.beforeEach(async (to) => {
    if (!session.loaded) {
      try { await Api.loadSession(); } catch (e) { applySession({ authenticated: false }); }
    }
    if (!session.authenticated) {
      negotiatedFor = null;
      return to.meta.public ? true : { path: '/login' };
    }
    if (session.mustChange && to.path !== '/password') return { path: '/password' };
    if (to.path === '/login') return { path: '/' };
    if (to.meta.admin && session.role !== 'admin') return { path: '/' };
    if (!session.mustChange && negotiatedFor !== session.csrfToken) {
      negotiatedFor = session.csrfToken;
      await negotiate(session.csrfToken);
    }
    return true;
  });

  Api.onAuthLost = function (err) {
    if (err.code === 'unauthenticated') applySession({ authenticated: false });
    else if (err.code === 'password_change_required') session.mustChange = true;
    router.push(session.authenticated ? '/password' : '/login');
  };

  return router;
}
