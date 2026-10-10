/* Library browser (admin): directory tree on the left, the player's folder look (cover header, tiles, track list) on the right. */
import { Api } from '../api.js';
import { ui } from '../state.js';
import { Icon } from '../components/icons.js';
import { Cover } from '../components/media.js';
import { DirTree } from '../components/DirTree.js';
import { RowMenu } from '../components/RowMenu.js';
import { formatDuration, formatTotal, formatSize } from '../core/format.js';
import { sectionsRoute } from '../core/list-json.js';
import { folderRoute } from '../core/urls.js';
import { createPrefs } from '../core/prefs.js';
import {
  defaultExpanded, withAncestors, toggleExpanded, pruneExpanded, crumbsFor, nameIndex, trackBadges, folderBadges,
  trackNumber, totalDuration
} from '../core/library.js';

function storage() { try { return window.localStorage; } catch (e) { return null; } }
const prefs = createPrefs(storage(), 'kd.');

export const LibraryPage = {
  name: 'LibraryPage',
  components: { Icon, Cover, DirTree, RowMenu },
  data() {
    return {
      tree: null, folder: null, forceTags: false, error: '', info: '', loading: false, treeOpen: false,
      artVersion: Date.now(), expanded: prefs.get('lib.expanded', null)
    };
  },
  computed: {
    path() { return this.$route.query.p || null; },
    tracks() { return this.folder ? this.folder.children.filter(c => c.type === 'track') : []; },
    subfolders() { return this.folder ? this.folder.children.filter(c => c.type === 'folder') : []; },
    crumbs() { return this.folder ? crumbsFor(this.folder.path, this.tree ? nameIndex(this.tree.roots) : {}) : []; },
    badges() { return folderBadges(this.folder).filter(b => b.id !== 'list'); },
    total() { return this.folder ? totalDuration(this.folder.children) : 0; },
    frontArt() { return this.folder && this.folder.hasFront ? this.artUrl(this.folder.path, 'front') : null; },
    rearArt() { return this.folder && this.folder.hasRear ? this.artUrl(this.folder.path, 'rear') : null; },
    expandedPaths() { return this.expanded || (this.tree ? defaultExpanded(this.tree.roots) : []); }
  },
  watch: {
    path() { this.loadFolder(); }
  },
  async created() {
    await this.loadTree();
    if (!this.path && this.tree && this.tree.roots.length) {
      this.select(this.tree.roots[0].path);
    } else {
      await this.loadFolder();
    }
  },
  mounted() {
    this.onKey = e => { if (this.treeOpen && e.key === 'Escape') this.treeOpen = false; };
    document.addEventListener('keydown', this.onKey);
  },
  beforeUnmount() { document.removeEventListener('keydown', this.onKey); },
  methods: {
    formatDuration, formatSize, formatTotal, trackBadges, folderBadges, trackNumber, sectionsRoute,
    artUrl(path, kind) { return Api.artUrl(path, kind, this.artVersion); },
    setExpanded(list) { this.expanded = list; prefs.set('lib.expanded', list); },
    async loadTree() {
      try {
        this.tree = await Api.tree();
        if (this.expanded) this.setExpanded(pruneExpanded(this.expanded, this.tree.roots));
      } catch (e) { this.error = e.message; }
    },
    async loadFolder() {
      if (!this.path) { this.folder = null; return; }
      this.loading = true; this.error = ''; this.info = '';
      this.setExpanded(withAncestors(this.expandedPaths, this.path));
      try {
        this.folder = await Api.folder(this.path);
        this.artVersion = Date.now();
      } catch (e) { this.folder = null; this.error = e.message; } finally { this.loading = false; }
    },
    toggle(path) { this.setExpanded(toggleExpanded(this.expandedPaths, path)); },
    select(path) {
      this.treeOpen = false;
      this.$router.push({ path: '/library', query: { p: path } });
    },
    async rescan(path, force) {
      this.error = ''; this.info = '';
      try {
        await Api.rescan(path, force);
        this.info = this.$t(force ? 'lib.rescan.startedForce' : 'lib.rescan.started', { path: path });
      } catch (e) { this.error = e.message; }
    },
    async refresh() { await this.loadTree(); await this.loadFolder(); },
    go(to) { this.$router.push(to); },
    openInfo(c) { ui.info = Object.assign({}, c.track, { title: c.track.displayTitle || c.track.title }); },
    trackMenu(c) {
      const items = [];
      if (c.track.editable) items.push({ id: 'edit', icon: 'edit', label: this.$t('lib.editTags'), run: () => this.go({ path: '/metadata', query: { p: this.folder.path, t: c.path } }) });
      items.push({ id: 'sections', icon: 'playlist', label: this.$t('listjson.file.open'), run: () => this.go(sectionsRoute(c.path)) });
      items.push({ id: 'info', icon: 'info', label: this.$t('action.info'), run: () => this.openInfo(c) });
      return items;
    },
    folderMenu(f) {
      const items = [
        { id: 'open', icon: 'folder', label: this.$t('action.open'), run: () => this.select(f.path) },
        { id: 'settings', icon: 'settings', label: this.$t('lib.settings'), run: () => this.go({ path: '/settings', query: { p: f.path } }) }
      ];
      if (f.trackCount) items.push({ id: 'tags', icon: 'edit', label: this.$t('lib.editTags'), run: () => this.go({ path: '/metadata', query: { p: f.path } }) });
      if (f.trackCount || f.hasList) items.push({ id: 'list', icon: 'playlist', label: this.$t('listjson.open'), run: () => this.go({ path: '/list-json', query: { p: f.path } }) });
      if (f.virtual) items.push({ id: 'sections', icon: 'playlist', label: this.$t('listjson.file.open'), run: () => this.go(sectionsRoute(f.path)) });
      items.push({ id: 'rescan', icon: 'refresh', label: this.$t('lib.rescan'), run: () => this.rescan(f.path, false) });
      items.push({ id: 'player', icon: 'play', label: this.$t('lib.openInPlayer'), run: () => this.go(folderRoute(f.path)) });
      return items;
    },
    playerRoute(path) { return folderRoute(path); }
  },
  template: `
    <section class="library">
      <div v-if="treeOpen" class="lib-scrim" @click="treeOpen = false"></div>
      <aside class="lib-tree" :class="{ open: treeOpen }" :aria-label="$t('lib.tree')">
        <div class="lib-tree-head"><strong>{{ $t('lib.directories') }}</strong>
          <button type="button" class="icon-btn only-narrow" :aria-label="$t('common.close')" @click="treeOpen = false"><icon name="close"></icon></button></div>
        <dir-tree v-if="tree && tree.roots.length" :roots="tree.roots" :expanded="expandedPaths" :selected="path"
                  @select="select" @toggle="toggle"></dir-tree>
        <p v-if="tree && !tree.roots.length" class="muted pad">{{ $t('lib.noRoots') }}</p>
      </aside>
      <div class="lib-main">
        <button type="button" class="only-narrow lib-toggle" :aria-expanded="String(treeOpen)" @click="treeOpen = !treeOpen">
          <icon name="folder"></icon> {{ $t('lib.directories') }}</button>
        <banner :message="error"></banner>
        <banner :message="info" kind="ok"></banner>
        <p v-if="loading && !folder" class="muted">{{ $t('list.loading') }}</p>
        <template v-if="folder">
          <nav class="crumbs" :aria-label="$t('folders.breadcrumb')">
            <template v-for="(c, i) in crumbs" :key="c.path">
              <span v-if="i" class="sep">/</span>
              <a href="#" @click.prevent="select(c.path)">{{ c.name }}</a>
            </template>
          </nav>
          <header class="page-head with-art">
            <div class="lib-arts">
              <cover class="head-art" :art="frontArt" size="medium" icon="folder"></cover>
              <cover v-if="rearArt" class="head-art rear" :art="rearArt" size="medium" icon="album"></cover>
            </div>
            <div class="grow">
              <h1>{{ folder.displayName }}</h1>
              <p class="muted">{{ $tn('count.tracks', folder.trackCount) }} · {{ $tn('count.folders', folder.folderCount) }}<span v-if="total"> · {{ formatTotal(total) }}</span>
                · {{ $t('lib.sort') }}: {{ folder.settings.sort }}</p>
              <p class="lib-badges">
                <span v-if="folder.settings.random" class="badge">{{ $t('lib.random', { n: folder.settings.random }) }}</span>
                <span v-if="folder.settings.recent" class="badge">{{ $t('lib.recent', { n: folder.settings.recent }) }}</span>
                <span v-for="v in folder.settings.views" :key="v" class="badge">{{ v }}</span>
                <span v-for="b in badges" :key="b.id" class="badge" :class="b.kind" :title="$t(b.helpKey)">{{ $t(b.labelKey) }}</span>
              </p>
              <p v-if="folder.settingsError" class="banner error">{{ $t('lib.settingsInvalid', { error: folder.settingsError }) }}</p>
              <p v-if="folder.listError" class="banner error">{{ $t('lib.listInvalid', { error: folder.listError }) }}</p>
              <div class="row lib-actions" role="toolbar" :aria-label="$t('lib.actions')">
                <router-link class="button" :to="{ path: '/settings', query: { p: folder.path } }"><icon name="settings"></icon> {{ $t('lib.settings') }}</router-link>
                <router-link class="button" :to="{ path: '/metadata', query: { p: folder.path } }" v-if="folder.trackCount"><icon name="edit"></icon> {{ $t('lib.editTags') }}</router-link>
                <router-link class="button" :to="{ path: '/list-json', query: { p: folder.path } }" v-if="folder.trackCount || folder.hasList"><icon name="playlist"></icon> {{ $t('listjson.open') }}<span v-if="folder.hasList" class="badge">list.json</span></router-link>
                <router-link class="button" v-if="folder.virtual" :to="sectionsRoute(folder.path)" :title="$t('listjson.file.open.help')"><icon name="playlist"></icon> {{ $t('listjson.file.open') }}</router-link>
                <button type="button" class="primary" @click="rescan(folder.path, forceTags)"><icon name="refresh"></icon> {{ $t('lib.rescan') }}</button>
                <label class="inline" :title="$t('lib.forceTags.help')"><input type="checkbox" v-model="forceTags"> {{ $t('lib.forceTags') }}</label>
                <button type="button" class="icon-btn" :title="$t('lib.reload')" :aria-label="$t('lib.reload')" @click="refresh"><icon name="refresh"></icon></button>
                <router-link class="button" :to="playerRoute(folder.path)"><icon name="play"></icon> {{ $t('lib.openInPlayer') }}</router-link>
              </div>
            </div>
          </header>

          <div class="tiles" v-if="subfolders.length">
            <div v-for="f in subfolders" :key="f.path" class="tile" @contextmenu.prevent="select(f.path)">
              <div class="tile-art">
                <a href="#" :aria-label="f.displayName" tabindex="-1" @click.prevent="select(f.path)"><cover :art="f.hasFront ? artUrl(f.path, 'front') : null" size="medium" :icon="f.virtual ? 'album' : 'folder'"></cover></a>
                <row-menu cls="tile-more" :label="f.displayName" :items="folderMenu(f)"></row-menu>
              </div>
              <a href="#" class="tile-title" @click.prevent="select(f.path)">{{ f.displayName }}<span v-if="f.virtual" class="badge" :title="$t('badge.virtual')">cue</span></a>
              <div class="tile-sub">{{ f.trackCount ? $tn('count.tracks', f.trackCount) : '' }}
                <span v-for="b in folderBadges(f).filter(b => b.kind)" :key="b.id" class="badge" :class="b.kind" :title="$t(b.helpKey)">{{ $t(b.labelKey) }}</span></div>
            </div>
          </div>

          <div class="tracklist lib-tracks" role="table" v-if="tracks.length">
            <div v-for="(c, i) in tracks" :key="c.path" class="lib-row" role="row" @dblclick="trackMenu(c)[0].run()">
              <span class="num c-num">{{ trackNumber(c.track, i) }}</span>
              <cover class="c-art" :art="frontArt" size="small"></cover>
              <div class="c-title"><div class="t">{{ c.track.displayTitle || c.name }}</div>
                <div class="s">{{ c.track.artist }}<span v-if="c.track.artist && c.name"> · </span><span class="fname">{{ c.name }}</span></div></div>
              <div class="c-album">{{ c.track.album }}<span v-if="c.track.year" class="muted"> · {{ c.track.year }}</span></div>
              <div class="c-fmt"><span class="fmt">{{ (c.track.format || '').toUpperCase() }}</span>
                <span v-if="c.track.bitrateKbps" class="muted small"> {{ c.track.bitrateKbps }} kbps</span>
                <span v-for="b in trackBadges(c.track)" :key="b.id" class="badge" :class="b.kind" :title="$t(b.helpKey)">{{ $t(b.labelKey) }}</span></div>
              <div class="c-dur">{{ formatDuration(c.track.durationMillis) }}</div>
              <span class="c-more">
                <router-link v-if="c.track.editable" class="icon-btn" :to="{ path: '/metadata', query: { p: folder.path, t: c.path } }"
                             :title="$t('lib.editTags')" :aria-label="$t('lib.editTags')"><icon name="edit"></icon></router-link>
                <row-menu :label="c.track.displayTitle || c.name" :items="trackMenu(c)"></row-menu>
              </span>
            </div>
          </div>
          <p v-else-if="!subfolders.length" class="muted">{{ $t('folders.empty') }}</p>
        </template>
        <p v-else-if="!loading && !error" class="muted">{{ $t('lib.select') }}</p>
      </div>
    </section>`
};
