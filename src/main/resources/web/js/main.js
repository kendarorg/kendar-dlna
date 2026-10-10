/* Entry point: app shell for both roles. Vue and vue-router are vendored ES modules, no build step. */
import { createApp } from './vue.js';
import { session, Api } from './api.js';
import { i18n } from './i18n.js';
import { ui, nav } from './state.js';
import { player, installShortcuts } from './player/index.js';
import { Banner, MoveButtons, SearchPicker } from './components/common.js';
import { Icon } from './components/icons.js';
import { SearchBox } from './components/SearchBox.js';
import { Sidebar } from './components/Sidebar.js';
import { ActionMenu, InfoDialog, ShortcutsDialog, UserMenu } from './components/menus.js';
import { PlayerBar, QueuePanel, FullPlayer } from './components/PlayerBar.js';
import { install as installPersonal } from './extensions/personal.js';
import { install as installAdmin } from './extensions/admin.js';
import { createAppRouter } from './router.js';
import { AddToPlaylistDialog } from './components/AddToPlaylistDialog.js';
import { PersonalNotices } from './pages/personal.js';
import { installPersonalRuntime, beforeLogout } from './personal/runtime.js';

installPersonal();
installAdmin();

const App = {
  name: 'App',
  components: { Icon, SearchBox, Sidebar, UserMenu, PlayerBar, QueuePanel, FullPlayer, ActionMenu, InfoDialog, ShortcutsDialog, AddToPlaylistDialog, PersonalNotices },
  setup() { return { session: session, ui: ui, player: player }; },
  computed: {
    bare() { return !this.session.authenticated || this.session.mustChange; },
    viewKey() { return this.$route.meta && this.$route.meta.keyed ? this.$route.fullPath : this.$route.path; }
  },
  methods: {
    async logout() {
      await beforeLogout();
      this.player.clear();
      try { await Api.logout(); } catch (e) { /* the session is gone either way */ }
      this.$router.push('/login');
    }
  },
  template: `
    <div class="app" :class="{ bare: bare, 'queue-open': ui.queueOpen && !bare }">
      <header class="topbar">
        <button v-if="!bare" type="button" class="icon-btn only-narrow" :aria-label="$t('nav.menu')" @click="ui.sidebarOpen = !ui.sidebarOpen"><icon name="menu"></icon></button>
        <router-link to="/" class="brand">Kendar Music</router-link>
        <search-box v-if="!bare"></search-box>
        <span class="grow"></span>
        <user-menu v-if="!bare"></user-menu>
        <button v-else-if="session.authenticated" type="button" @click="logout">{{ $t('user.logout') }}</button>
      </header>
      <sidebar v-if="!bare"></sidebar>
      <div v-if="ui.sidebarOpen && !bare" class="scrim" @click="ui.sidebarOpen = false"></div>
      <main class="content" id="main" tabindex="-1"><personal-notices v-if="!bare"></personal-notices><router-view :key="viewKey"></router-view></main>
      <queue-panel v-if="!bare"></queue-panel>
      <player-bar v-if="!bare"></player-bar>
      <full-player v-if="!bare"></full-player>
      <action-menu></action-menu>
      <info-dialog></info-dialog>
      <shortcuts-dialog></shortcuts-dialog>
      <add-to-playlist-dialog v-if="!bare"></add-to-playlist-dialog>
      <div v-if="ui.toast" class="toast" role="status">{{ ui.toast }}</div>
    </div>`
};

const router = createAppRouter();
nav.router = router;

const app = createApp(App);
app.config.globalProperties.$t = i18n.t;
app.config.globalProperties.$tn = i18n.tn;
app.component('banner', Banner);
app.component('move-buttons', MoveButtons);
app.component('search-picker', SearchPicker);
app.use(router);
app.mount('#app');

installShortcuts(document);
installPersonalRuntime();

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('sw.js').catch(() => { /* optional: the app works without it */ });
  });
}
