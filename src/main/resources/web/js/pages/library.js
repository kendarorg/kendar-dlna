/* Library browser (admin): directory tree on the left, the player's folder look (cover header, tiles, track list) on the right. */
import { Api } from '../api.js';
import { ui, askConsent } from '../state.js';
import { CONSENT_PHRASE } from '../core/consent.js';
import { Icon } from '../components/icons.js';
import { Cover } from '../components/media.js';
import { DirTree } from '../components/DirTree.js';
import { RowMenu } from '../components/RowMenu.js';
import { PaneResizer } from '../components/PaneResizer.js';
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
  components: { Icon, Cover, DirTree, RowMenu, PaneResizer },
  data() {
    return {
      tree: null, folder: null, busy: false, forceTags: false, error: '', info: '', loading: false, treeOpen: false,
      artVersion: Date.now(), expanded: prefs.get('lib.expanded', null),
      folderView: prefs.get('lib.folderView', 'boxes') === 'list' ? 'list' : 'boxes'
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
    /** The rescan started by a file or directory change runs in the background: look again a moment later. */
    refreshSoon() { this.refresh(); setTimeout(() => { if (this.folder) this.refresh(); }, 2500); },
    async uploadFiles(event) {
      const picked = Array.from(event.target.files || []);
      event.target.value = '';
      if (!picked.length) return;
      this.error = ''; this.info = ''; this.busy = true;
      const failed = [];
      let done = 0;
      for (const f of picked) {
        try { await Api.uploadAudio(this.folder.path, f); done++; } catch (e) { failed.push(f.name + ': ' + e.message); }
      }
      this.busy = false;
      this.info = 'Uploaded ' + done + ' of ' + picked.length + ' file(s); the directory is being rescanned.';
      if (failed.length) this.error = failed.join('; ');
      this.refreshSoon();
    },
    async newFolder() {
      const name = window.prompt('Name of the new directory');
      if (!name || !name.trim()) return;
      this.error = ''; this.info = '';
      try { await Api.createDir(this.folder.path, name.trim()); this.info = 'Directory created.'; this.refreshSoon(); }
      catch (e) { this.error = e.message; }
    },
    async removeFolder(path, name) {
      this.error = ''; this.info = '';
      try {
        const p = await Api.deletePreview(path);
        const lines = [p.tracks ? this.$tn('lib.deleteDir.songs', p.tracks) : this.$t('lib.deleteDir.none')];
        if (p.otherFiles) lines.push(this.$tn('lib.deleteDir.others', p.otherFiles));
        if (p.folders) lines.push(this.$tn('lib.deleteDir.folders', p.folders));
        lines.push(this.$t('lib.deleteDir.trash'));
        const ok = await askConsent({
          title: this.$t('lib.deleteDir.title', { name: name }), lines: lines, confirmLabel: this.$t('lib.deleteDir.confirm')
        });
        if (!ok) return;
        await Api.deleteDir(path, true, CONSENT_PHRASE);
        this.info = this.$t('lib.deleteDir.done');
        const parent = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : path;
        if (this.path === path || (this.path || '').startsWith(path + '/')) this.select(parent);
        else this.refreshSoon();
      } catch (e) { this.error = e.message; }
    },
    async removeTrack(c) {
      if (!window.confirm('Delete the file "' + c.name + '" from disk? This cannot be undone.')) return;
      this.error = ''; this.info = '';
      try { await Api.deleteTrack(c.path); this.info = 'File deleted.'; await this.refresh(); }
      catch (e) { this.error = e.message; }
    },
    go(to) { this.$router.push(to); },
    openInfo(c) { ui.info = Object.assign({}, c.track, { title: c.track.displayTitle || c.track.title }); },
    trackMenu(c) {
      const items = [];
      if (c.track.editable) items.push({ id: 'edit', icon: 'edit', label: this.$t('lib.editTags'), run: () => this.go({ path: '/metadata', query: { p: this.folder.path, t: c.path } }) });
      items.push({ id: 'lyrics', icon: 'note', label: this.$t('lyrics.find'), run: () => this.go({ path: '/lyrics', query: { p: this.folder.path, t: c.path } }) });
      items.push({ id: 'sections', icon: 'playlist', label: this.$t('listjson.file.open'), run: () => this.go(sectionsRoute(c.path)) });
      items.push({ id: 'info', icon: 'info', label: this.$t('action.info'), run: () => this.openInfo(c) });
      if (!c.track.segment) items.push({ id: 'delete', icon: 'close', label: 'Delete file', run: () => this.removeTrack(c) });
      return items;
    },
    folderMenu(f) {
      const items = [
        { id: 'open', icon: 'folder', label: this.$t('action.open'), run: () => this.select(f.path) },
        { id: 'settings', icon: 'settings', label: this.$t('lib.settings'), run: () => this.go({ path: '/settings', query: { p: f.path } }) }
      ];
      if (f.trackCount) items.push({ id: 'tags', icon: 'edit', label: this.$t('lib.editTags'), run: () => this.go({ path: '/metadata', query: { p: f.path } }) });
      if (f.trackCount) items.push({ id: 'lyrics', icon: 'note', label: this.$t('lyrics.findFolder'), run: () => this.go({ path: '/lyrics', query: { p: f.path } }) });
      if (f.trackCount || f.hasList) items.push({ id: 'list', icon: 'playlist', label: this.$t('listjson.open'), run: () => this.go({ path: '/list-json', query: { p: f.path } }) });
      if (f.virtual) items.push({ id: 'sections', icon: 'playlist', label: this.$t('listjson.file.open'), run: () => this.go(sectionsRoute(f.path)) });
      items.push({ id: 'rescan', icon: 'refresh', label: this.$t('lib.rescan'), run: () => this.rescan(f.path, false) });
      if (!f.virtual) items.push({ id: 'delete', icon: 'close', label: 'Delete directory', run: () => this.removeFolder(f.path, f.displayName) });
      items.push({ id: 'player', icon: 'play', label: this.$t('lib.openInPlayer'), run: () => this.go(folderRoute(f.path)) });
      return items;
    },
    setFolderView(v) { this.folderView = v === 'list' ? 'list' : 'boxes'; prefs.set('lib.folderView', this.folderView); },
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
      <pane-resizer css-var="--tree-w" pane=".lib-tree" pref-key="pane.tree" :min="200" :max="560" :fallback="272" :label="$t('pane.resizeTree')"></pane-resizer>
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
                <router-link class="button" :to="{ path: '/lyrics', query: { p: folder.path } }" v-if="folder.trackCount"><icon name="note"></icon> {{ $t('lyrics.findFolder') }}</router-link>
                <router-link class="button" :to="{ path: '/list-json', query: { p: folder.path } }" v-if="folder.trackCount || folder.hasList"><icon name="playlist"></icon> {{ $t('listjson.open') }}<span v-if="folder.hasList" class="badge">list.json</span></router-link>
                <router-link class="button" v-if="folder.virtual" :to="sectionsRoute(folder.path)" :title="$t('listjson.file.open.help')"><icon name="playlist"></icon> {{ $t('listjson.file.open') }}</router-link>
                <button type="button" class="primary" @click="rescan(folder.path, forceTags)"><icon name="refresh"></icon> {{ $t('lib.rescan') }}</button>
                <label class="inline" :title="$t('lib.forceTags.help')"><input type="checkbox" v-model="forceTags"> {{ $t('lib.forceTags') }}</label>
                <button type="button" class="icon-btn" :title="$t('lib.reload')" :aria-label="$t('lib.reload')" @click="refresh"><icon name="refresh"></icon></button>
                <router-link class="button" :to="playerRoute(folder.path)"><icon name="play"></icon> {{ $t('lib.openInPlayer') }}</router-link>
                <template v-if="!folder.virtual">
                  <label class="button" title="Upload audio files into this directory"><input type="file" multiple hidden accept="audio/*,.mid,.midi,.flac,.opus,.m4a,.aac,.wma,.ape" :disabled="busy" @change="uploadFiles"> Upload files&hellip;</label>
                  <button type="button" :disabled="busy" @click="newFolder"><icon name="folder"></icon> New directory</button>
                  <button v-if="!folder.isRoot" type="button" class="danger" :disabled="busy" @click="removeFolder(folder.path, folder.displayName)">Delete directory</button>
                </template>
              </div>
            </div>
          </header>

          <div v-if="subfolders.length" class="row lib-viewswitch" role="group" :aria-label="$t('lib.view')">
            <button type="button" :class="{ on: folderView === 'boxes' }" :aria-pressed="String(folderView === 'boxes')" @click="setFolderView('boxes')">{{ $t('lib.view.boxes') }}</button>
            <button type="button" :class="{ on: folderView === 'list' }" :aria-pressed="String(folderView === 'list')" @click="setFolderView('list')">{{ $t('lib.view.list') }}</button>
          </div>
          <ul class="group-list lib-folderlist" v-if="subfolders.length && folderView === 'list'">
            <li v-for="f in subfolders" :key="f.path" class="group-row" @contextmenu.prevent="select(f.path)">
              <cover :art="f.hasFront ? artUrl(f.path, 'front') : null" size="small" :icon="f.virtual ? 'album' : 'folder'"></cover>
              <a href="#" class="grow" @click.prevent="select(f.path)">{{ f.displayName }}<span v-if="f.virtual" class="badge" :title="$t('badge.virtual')">cue</span></a>
              <span class="muted small">{{ f.trackCount ? $tn('count.tracks', f.trackCount) : '' }}</span>
              <span v-for="b in folderBadges(f).filter(b => b.kind)" :key="b.id" class="badge" :class="b.kind" :title="$t(b.helpKey)">{{ $t(b.labelKey) }}</span>
              <row-menu :label="f.displayName" :items="folderMenu(f)"></row-menu>
            </li>
          </ul>
          <div class="tiles" v-if="subfolders.length && folderView === 'boxes'">
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
