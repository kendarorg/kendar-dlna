/*
 * Admin UI additions (plan milestone 8): user management and shared-playlist moderation. The phase-1 admin pages
 * (library, directory settings, tags, system playlists, server) stay in pages/ at their routes; the sidebar shows
 * the "Administration" section to admins only (components/Sidebar.js), and every route here has meta.admin so the
 * router guard keeps users out (the API enforces it as well).
 */
import { registerRoute, registerNavItem } from '../extensions.js';
import { UsersPage, SharedModerationPage } from '../pages/admin-users.js';

export function install() {
  registerRoute({ path: '/admin/users', component: UsersPage, meta: { admin: true, titleKey: 'nav.adminUsers' } });
  registerRoute({ path: '/admin/shared-playlists', component: SharedModerationPage, meta: { admin: true, titleKey: 'nav.adminShared' } });
  const admin = session => session.role === 'admin';
  registerNavItem({ id: 'adminUsers', to: '/admin/users', label: 'nav.adminUsers', icon: 'people', section: 'admin', order: 5, visible: admin });
  registerNavItem({ id: 'adminShared', to: '/admin/shared-playlists', label: 'nav.adminShared', icon: 'share', section: 'admin', order: 15, visible: admin });
}
