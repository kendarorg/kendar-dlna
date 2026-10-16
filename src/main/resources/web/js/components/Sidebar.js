/* Sidebar navigation: library entries, extension entries (personal data), admin entries. */
import { Icon } from './icons.js';
import { session } from '../api.js';
import { registry } from '../extensions.js';
import { ui, navs } from '../state.js';

const LIBRARY = [
  { id: 'home', to: '/', label: 'nav.home', icon: 'home', order: 0 },
  { id: 'folders', to: '/folders', label: 'nav.folders', icon: 'folder', order: 10 },
  { id: 'albums', to: '/albums', label: 'nav.albums', icon: 'album', order: 20 },
  { id: 'artists', to: '/artists', label: 'nav.artists', icon: 'artist', order: 30 },
  { id: 'genres', to: '/genres', label: 'nav.genres', icon: 'genre', order: 40 },
  { id: 'songs', to: '/songs', label: 'nav.songs', icon: 'note', order: 50 },
  { id: 'recent', to: '/recent', label: 'nav.recent', icon: 'recent', order: 60 }
];

const ADMIN = [
  { id: 'adminLibrary', to: '/library', label: 'nav.adminLibrary', icon: 'settings', order: 0 },
  { id: 'adminPlaylists', to: '/playlists', label: 'nav.adminPlaylists', icon: 'queue', order: 10 },
  { id: 'adminNavigations', to: '/navigations', label: 'nav.adminNavigations', icon: 'genre', order: 15 },
  { id: 'adminServer', to: '/admin', label: 'nav.adminServer', icon: 'info', order: 20 }
];

export const Sidebar = {
  name: 'Sidebar',
  components: { Icon },
  setup() { return { session: session, ui: ui, registry: registry, navs: navs }; },
  computed: {
    /** With configured navigations they replace the fixed Albums / Artists / Genres / Songs entries. */
    libraryItems() {
      if (!this.navs.items.length) return LIBRARY;
      const fixed = ['albums', 'artists', 'genres', 'songs'];
      const kept = LIBRARY.filter(i => !fixed.includes(i.id));
      const dynamic = this.navs.items.map((nv, k) => ({
        id: 'nav:' + nv.key, to: '/nav/' + nv.key, label: nv.name, raw: true, icon: 'album', order: 20 + k
      }));
      return kept.concat(dynamic);
    },
    groups() {
      const extra = section => this.registry.navItems
        .filter(i => i.section === section && (!i.visible || i.visible(this.session)));
      const sort = list => list.slice().sort((a, b) => a.order - b.order);
      const groups = [
        { id: 'library', title: null, items: sort(this.libraryItems.concat(extra('library'))) },
        { id: 'personal', title: 'nav.personal', items: sort(extra('personal')) }
      ];
      if (this.session.role === 'admin') {
        groups.push({ id: 'admin', title: 'nav.admin', items: sort(ADMIN.concat(extra('admin'))) });
      }
      return groups.filter(g => g.items.length);
    }
  },
  watch: { '$route.path'() { this.ui.sidebarOpen = false; } },
  methods: {
    active(item) {
      const p = this.$route.path;
      if (item.to === '/') return p === '/';
      return p === item.to || p.startsWith(item.to + '/');
    }
  },
  template: `
    <nav class="sidebar" :class="{ open: ui.sidebarOpen }" :aria-label="$t('nav.label')">
      <div v-for="g in groups" :key="g.id" class="nav-group">
        <h2 v-if="g.title" class="nav-title">{{ $t(g.title) }}</h2>
        <router-link v-for="i in g.items" :key="i.id" :to="i.to" class="nav-item" :class="{ active: active(i) }"
                     :aria-current="active(i) ? 'page' : null"><icon :name="i.icon"></icon><span>{{ i.raw ? i.label : $t(i.label) }}</span></router-link>
      </div>
    </nav>`
};
