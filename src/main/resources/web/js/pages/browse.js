/* Player browsing pages: home, folders, albums/artists/genres, songs, recently added, search. */
import { reactive } from '../vue.js';
import { Api, session } from '../api.js';
import { player } from '../player/index.js';
import { registry } from '../extensions.js';
import { playList, groupSubject, openMenu } from '../actions.js';
import { TrackList, GroupGrid, InfiniteScroll } from '../components/lists.js';
import { Cover } from '../components/media.js';
import { Icon } from '../components/icons.js';
import { SwitchToggle } from '../components/SwitchToggle.js';
import { createPager } from '../core/pager.js';
import { sortItems } from '../core/list.js';
import { formatTotal } from '../core/format.js';
import { folderRoute, rangeRoute, searchRoute } from '../core/urls.js';
import { toast } from '../state.js';

const PAGE = 50;

function pagerFor(fetchPage, limit) {
  return createPager(fetchPage, { limit: limit || PAGE, reactive: reactive });
}

function storedLayout() {
  try { return localStorage.getItem('kd.folderLayout') === 'list' ? 'list' : 'tiles'; } catch (e) { return 'tiles'; }
}

/** How a folder is browsed: through its letter ranges (default) or as one list that loads while scrolling. */
function storedBrowse() {
  try { return localStorage.getItem('kd.folderBrowse') === 'scroll' ? 'scroll' : 'ranges'; } catch (e) { return 'ranges'; }
}

/** The Ranges / Infinite scrolling switch (one preference for folders and navigations). */
const BrowseSwitch = {
  name: 'BrowseSwitch',
  components: { SwitchToggle },
  props: { modelValue: { type: String, required: true } },
  emits: ['update:modelValue'],
  template: `
    <switch-toggle :model-value="modelValue === 'scroll'" :off-label="$t('folders.browse.ranges')"
                   :on-label="$t('folders.browse.scroll')" @update:model-value="v => $emit('update:modelValue', v ? 'scroll' : 'ranges')"></switch-toggle>`
};

function storeBrowse(b) {
  try { localStorage.setItem('kd.folderBrowse', b); } catch (e) { /* ignore */ }
}

const COMPONENTS = { TrackList, GroupGrid, InfiniteScroll, Cover, Icon, BrowseSwitch, SwitchToggle };

export const HomePage = {
  name: 'HomePage',
  components: COMPONENTS,
  setup() { return { registry: registry, session: session }; },
  data() { return { random: [], recent: [], loading: true, error: '' }; },
  created() { this.load(); },
  methods: {
    async load() {
      this.loading = true; this.error = '';
      try {
        const [r, n] = await Promise.all([Api.random({ count: 20, limit: 20 }), Api.recent({ limit: 12 })]);
        this.random = r.items; this.recent = n.items;
      } catch (e) { this.error = e.message; } finally { this.loading = false; }
    },
    async reshuffle() {
      try { this.random = (await Api.random({ count: 20, limit: 20 })).items; } catch (e) { this.error = e.message; }
    },
    play(list, i) { playList(list, { start: i }); },
    mix() { playList(this.random, { start: 0 }); }
  },
  computed: {
    sections() { return this.registry.homeSections.filter(s => !s.top).sort((a, b) => a.order - b.order); },
    topSections() { return this.registry.homeSections.filter(s => s.top).sort((a, b) => a.order - b.order); }
  },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t('nav.home') }}</h1></header>
      <component v-for="s in topSections" :key="s.id" :is="s.component"></component>
      <p v-if="error" class="banner error">{{ error }}</p>
      <p v-else-if="!loading && !random.length && !recent.length" class="muted">{{ $t('home.empty') }}</p>
      <template v-if="random.length">
        <div class="section-head"><h2>{{ $t('home.random') }}</h2>
          <span class="row">
            <button type="button" class="primary" @click="mix"><icon name="play"></icon> {{ $t('home.playMix') }}</button>
            <button type="button" @click="reshuffle"><icon name="shuffle"></icon> {{ $t('home.another') }}</button>
          </span></div>
        <track-list :tracks="random" @play="i => play(random, i)"></track-list>
      </template>
      <template v-if="recent.length">
        <div class="section-head"><h2>{{ $t('nav.recent') }}</h2><router-link to="/recent">{{ $t('home.seeAll') }}</router-link></div>
        <track-list :tracks="recent" :show-added="true" @play="i => play(recent, i)"></track-list>
      </template>
      <component v-for="s in sections" :key="s.id" :is="s.component"></component>
    </section>`
};

export const FoldersPage = {
  name: 'FoldersPage',
  components: COMPONENTS,
  setup() { return { session: session }; },
  data() { return { roots: null, head: null, pager: null, error: '', layout: storedLayout(), browse: storedBrowse() }; },
  computed: {
    path() { return this.$route.query.p || ''; },
    rangeId() { return this.$route.query.r || ''; },
    ranges() { return this.pager ? this.pager.state.items.filter(c => c.type === 'range') : []; },
    folders() { return this.pager ? this.pager.state.items.filter(c => c.type === 'folder') : []; },
    tracks() { return this.pager ? this.pager.state.items.filter(c => c.type === 'track').map(c => c.track) : []; },
    crumbs() {
      const parts = this.path ? this.path.split('/') : [];
      const out = parts.map((name, i) => ({ key: 'p' + i, name: name, to: folderRoute(parts.slice(0, i + 1).join('/')) }));
      const trail = this.head && this.head.range ? this.head.range.trail : [];
      for (const r of trail) out.push({ key: r.id, name: r.label, to: rangeRoute(this.path, r.id) });
      return out;
    }
  },
  watch: { path: { immediate: true, handler() { this.load(); } }, rangeId() { this.load(); } },
  methods: {
    async load() {
      this.error = ''; this.head = null; this.pager = null; this.roots = null;
      if (!this.path) {
        try {
          const r = await Api.roots();
          if (r.roots.length === 1) { this.$router.replace(folderRoute(r.roots[0].path)); return; }
          this.roots = r.roots;
        } catch (e) { this.error = e.message; }
        return;
      }
      const path = this.path;
      const rangeId = this.rangeId;
      const ranged = this.browse === 'ranges';
      this.pager = pagerFor(async p => {
        const r = await Api.folderPage(path, ranged ? Object.assign({ layout: 'ranges', range: rangeId }, p) : p);
        if (path === this.path && rangeId === this.rangeId) this.head = r;
        return { items: r.children, total: r.total };
      }, 100);
    },
    setLayout(l) {
      this.layout = l;
      try { localStorage.setItem('kd.folderLayout', l); } catch (e) { /* ignore */ }
    },
    /** Switching goes back to the start of the folder: the first range level, or the whole list. */
    setBrowse(b) {
      if (b === this.browse) return;
      this.browse = b;
      storeBrowse(b);
      if (this.rangeId) this.$router.replace(folderRoute(this.path)); else this.load();
    },
    rangeRoute(r) { return rangeRoute(this.path, r.id); },
    rangeSub(r) {
      const parts = [];
      if (r.folderCount) parts.push(this.$tn('count.folders', r.folderCount));
      if (r.trackCount) parts.push(this.$tn('count.tracks', r.trackCount));
      return parts.join(' · ');
    },
    /** Every song directly in the folder, whatever range is open. */
    async all() {
      if (this.browse === 'ranges') return Api.folderTracks(this.path);
      if (this.pager) await this.pager.loadAll(100);
      return this.tracks;
    },
    async playAt(i) {
      const clicked = this.tracks[i];
      const tracks = await this.all();
      const at = clicked ? tracks.findIndex(t => t.path === clicked.path) : -1;
      playList(tracks, { start: at < 0 ? 0 : at });
    },
    async playAll(shuffle) {
      const tracks = await this.all();
      if (!tracks.length) { toast(this.$t('toast.nothing')); return; }
      playList(tracks, { shuffle: shuffle });
    },
    async playRandom() {
      try {
        const r = await Api.random({ path: this.path, limit: 200 });
        playList(r.items, { start: 0 });
      } catch (e) { toast(e.message); }
    }
  },
  template: `
    <section>
      <p v-if="error" class="banner error">{{ error }}</p>
      <template v-if="!path">
        <header class="page-head"><h1>{{ $t('nav.folders') }}</h1></header>
        <group-grid v-if="roots && roots.length" :items="roots" mode="folder" :layout="layout"></group-grid>
        <p v-else-if="roots" class="muted">{{ $t('folders.noRoots') }}</p>
      </template>
      <template v-else>
        <nav class="crumbs" :aria-label="$t('folders.breadcrumb')">
          <router-link :to="{ path: '/folders' }">{{ $t('nav.folders') }}</router-link>
          <template v-for="c in crumbs" :key="c.key"><span class="sep">/</span><router-link :to="c.to">{{ c.name }}</router-link></template>
        </nav>
        <header class="page-head with-art" v-if="head">
          <cover class="head-art" :art="head.art" size="medium" icon="folder"></cover>
          <div class="grow">
            <h1>{{ head.displayName }}</h1>
            <p class="muted">{{ $tn('count.tracks', head.trackCount) }} · {{ $tn('count.folders', head.folderCount) }}</p>
            <div class="row">
              <button type="button" class="primary" :disabled="!head.trackCount" @click="playAll(false)"><icon name="play"></icon> {{ $t('action.play') }}</button>
              <button type="button" :disabled="!head.trackCount" @click="playAll(true)"><icon name="shuffle"></icon> {{ $t('player.shuffle') }}</button>
              <button type="button" @click="playRandom"><icon name="shuffle"></icon> {{ $t('folders.random') }}</button>
              <router-link v-if="session.role === 'admin'" class="button" :to="{ path: '/library', query: { p: path } }">{{ $t('folders.manage') }}</router-link>
            </div>
          </div>
        </header>
        <browse-switch :model-value="browse" @update:model-value="setBrowse"></browse-switch>
        <switch-toggle v-if="folders.length || ranges.length" :model-value="layout === 'list'" off-label="Tiles" on-label="List"
                       @update:model-value="v => setLayout(v ? 'list' : 'tiles')"></switch-toggle>
        <ul v-if="ranges.length && layout === 'list'" class="group-list plain">
          <li v-for="r in ranges" :key="r.id" class="group-row">
            <router-link :to="rangeRoute(r)" class="grow">{{ r.label }}</router-link>
            <span class="muted small">{{ rangeSub(r) }}</span>
          </li>
        </ul>
        <div v-else-if="ranges.length" class="tiles">
          <div v-for="r in ranges" :key="r.id" class="tile">
            <div class="tile-art"><router-link :to="rangeRoute(r)" :aria-label="r.label" tabindex="-1"><cover :art="r.art" size="medium" icon="folder"></cover></router-link></div>
            <router-link :to="rangeRoute(r)" class="tile-title">{{ r.label }}</router-link>
            <div class="tile-sub">{{ rangeSub(r) }}</div>
          </div>
        </div>
        <group-grid v-if="folders.length" :items="folders" mode="folder" :layout="layout"></group-grid>
        <track-list v-if="tracks.length" :tracks="tracks" :numbered="true" :show-album="true" @play="playAt"></track-list>
        <infinite-scroll v-if="pager" :key="path + '|' + rangeId + '|' + browse" :pager="pager" :empty-text="$t('folders.empty')"></infinite-scroll>
      </template>
    </section>`
};

export const GroupsPage = {
  name: 'GroupsPage',
  components: COMPONENTS,
  props: { view: { type: String, required: true } },
  data() { return { pager: null }; },
  created() {
    this.pager = pagerFor(async p => {
      const r = await Api.groups(this.view, p);
      return { items: r.items, total: r.total };
    }, 60);
  },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t('nav.' + view) }}</h1>
        <span v-if="pager.state.total !== null" class="muted">{{ $tn('count.items', pager.state.total) }}</span></header>
      <group-grid :items="pager.state.items"></group-grid>
      <infinite-scroll :pager="pager" :empty-text="$t('list.empty')"></infinite-scroll>
    </section>`
};

export const GroupDetailPage = {
  name: 'GroupDetailPage',
  components: COMPONENTS,
  data() { return { head: null, pager: null, mode: 'tracks' }; },
  computed: {
    view() { return this.$route.params.view; },
    id() { return this.$route.params.id; },
    item() { return this.head ? this.head.item : null; },
    tracks() { return this.mode === 'tracks' && this.pager ? this.pager.state.items : []; },
    albums() { return this.mode === 'albums' && this.pager ? this.pager.state.items : []; },
    isAlbum() { return this.item && this.item.type === 'album'; }
  },
  watch: { '$route.fullPath': { immediate: true, handler() { this.load(); } } },
  methods: {
    load() {
      const view = this.view, id = this.id;
      this.head = null;
      this.pager = pagerFor(async p => {
        const r = await Api.group(view, id, p);
        if (view === this.view && id === this.id) {
          this.head = r;
          this.mode = r.tracks ? 'tracks' : 'albums';
        }
        return { items: r.tracks || r.albums || [], total: r.total };
      }, 100);
    },
    async everything() {
      if (this.mode === 'tracks') { await this.pager.loadAll(100); return this.tracks; }
      return groupSubject(this.item).tracks();
    },
    async playAt(i) { const t = await this.everything(); playList(t, { start: i }); },
    async playRandom() {
      try {
        const t = await Api.navRandom(this.item, this.item.random);
        if (!t.length) { toast(this.$t('toast.nothing')); return; }
        playList(t, { start: 0 });
      } catch (e) { toast(e.message); }
    },
    async playAll(shuffle) {
      try {
        const t = await this.everything();
        if (!t.length) { toast(this.$t('toast.nothing')); return; }
        playList(t, { shuffle: shuffle });
      } catch (e) { toast(e.message); }
    },
    menu(e) { if (this.item) openMenu(e, groupSubject(this.item)); },
    formatTotal
  },
  template: `
    <section>
      <nav class="crumbs"><router-link :to="'/' + view">{{ $t('nav.' + view) }}</router-link></nav>
      <header class="page-head with-art" v-if="item">
        <cover class="head-art" :art="item.art" size="medium" :icon="item.type === 'artist' ? 'artist' : (item.type === 'genre' ? 'genre' : 'album')"></cover>
        <div class="grow">
          <h1>{{ item.title }}</h1>
          <p class="muted">{{ [item.artist, item.year || null].filter(Boolean).join(' · ') }}</p>
          <p class="muted">{{ $tn('count.tracks', item.trackCount) }}<span v-if="item.albumCount !== undefined"> · {{ $tn('count.albums', item.albumCount) }}</span>
            <span v-if="item.durationMillis"> · {{ formatTotal(item.durationMillis) }}</span></p>
          <div class="row">
            <button type="button" class="primary" @click="playAll(false)"><icon name="play"></icon> {{ $t('action.play') }}</button>
            <button type="button" @click="playAll(true)"><icon name="shuffle"></icon> {{ $t('player.shuffle') }}</button>
            <router-link v-if="head.allTracks" class="button" :to="'/' + view + '/' + encodeURIComponent(head.allTracks.id)">{{ $t('group.allTracks') }}</router-link>
            <button type="button" class="icon-btn" :aria-label="$t('action.more')" aria-haspopup="menu" @click.stop="menu($event)"><icon name="more"></icon></button>
          </div>
        </div>
      </header>
      <group-grid v-if="albums.length" :items="albums"></group-grid>
      <track-list v-if="tracks.length" :tracks="tracks" :numbered="isAlbum" :show-album="!isAlbum" @play="playAt"></track-list>
      <infinite-scroll v-if="pager" :key="view + id" :pager="pager" :empty-text="$t('list.empty')"></infinite-scroll>
    </section>`
};

export const SongsPage = {
  name: 'SongsPage',
  components: COMPONENTS,
  data() { return { pager: null, sortKey: 'title', sortDir: 'asc', sorting: false }; },
  created() {
    this.pager = pagerFor(async p => {
      const r = await Api.songs(p);
      return { items: r.items, total: r.total };
    }, 200);
  },
  computed: {
    sorted() {
      const items = this.pager.state.items;
      if (this.sortKey === 'title' && this.sortDir === 'asc') return items;   // the server order
      const get = this.sortKey === 'title' ? (t => t.title || t.name) : this.sortKey;
      return sortItems(items, get, this.sortDir);
    }
  },
  methods: {
    async sort(key) {
      if (this.sortKey === key) this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
      else { this.sortKey = key; this.sortDir = 'asc'; }
      if (!(this.sortKey === 'title' && this.sortDir === 'asc')) {   // sorting needs every song
        this.sorting = true;
        try { await this.pager.loadAll(200); } finally { this.sorting = false; }
      }
    },
    play(i) { playList(this.sorted, { start: i }); }
  },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t('nav.songs') }}</h1>
        <span v-if="pager.state.total !== null" class="muted">{{ $tn('count.tracks', pager.state.total) }}</span>
        <span v-if="sorting" class="muted">{{ $t('list.loading') }}</span></header>
      <track-list :tracks="sorted" :sortable="true" :sort-key="sortKey" :sort-dir="sortDir" @sort="sort" @play="play"></track-list>
      <infinite-scroll :pager="pager" :empty-text="$t('list.empty')"></infinite-scroll>
    </section>`
};

export const RecentPage = {
  name: 'RecentPage',
  components: COMPONENTS,
  data() { return { pager: null }; },
  created() {
    this.pager = pagerFor(async p => {
      const r = await Api.recent(p);
      return { items: r.items, total: r.total };
    }, 100);
  },
  methods: { play(i) { playList(this.pager.state.items, { start: i }); } },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t('nav.recent') }}</h1></header>
      <track-list :tracks="pager.state.items" :show-added="true" @play="play"></track-list>
      <infinite-scroll :pager="pager" :empty-text="$t('list.empty')"></infinite-scroll>
    </section>`
};

export const SearchPage = {
  name: 'SearchPage',
  components: COMPONENTS,
  setup() { return { searchRoute: searchRoute }; },
  data() { return { result: null, pager: null, error: '', loading: false }; },
  computed: {
    q() { return this.$route.query.q || ''; },
    kind() { return this.$route.query.kind || 'all'; },
    plural() { return { track: 'tracks', album: 'albums', artist: 'artists', folder: 'folders' }[this.kind]; },
    pagedItems() { return this.pager ? this.pager.state.items : []; }
  },
  watch: { '$route.fullPath': { immediate: true, handler() { this.load(); } } },
  methods: {
    async load() {
      this.result = null; this.pager = null; this.error = '';
      if (!this.q.trim()) return;
      const q = this.q, kind = this.kind;
      if (kind === 'all') {
        this.loading = true;
        try { const r = await Api.search(q, 'all', { limit: 12 }); if (q === this.q) this.result = r; }
        catch (e) { this.error = e.message; } finally { this.loading = false; }
        return;
      }
      const key = this.plural;
      this.pager = pagerFor(async p => {
        const r = await Api.search(q, kind, p);
        return { items: r[key].items, total: r[key].total };
      }, 60);
    },
    more(kind) { return searchRoute(this.q, kind); },
    play(list, i) { playList(list, { start: i }); }
  },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t('search.results', { q: q }) }}</h1></header>
      <p v-if="!q.trim()" class="muted">{{ $t('search.hint') }}</p>
      <p v-if="error" class="banner error">{{ error }}</p>
      <template v-if="result">
        <p v-if="!result.tracks.total && !result.albums.total && !result.artists.total && !result.folders.total" class="muted">{{ $t('search.none') }}</p>
        <template v-if="result.artists.total">
          <div class="section-head"><h2>{{ $t('nav.artists') }}</h2>
            <router-link v-if="result.artists.total > result.artists.items.length" :to="more('artist')">{{ $t('search.showAll', { n: result.artists.total }) }}</router-link></div>
          <group-grid :items="result.artists.items"></group-grid></template>
        <template v-if="result.albums.total">
          <div class="section-head"><h2>{{ $t('nav.albums') }}</h2>
            <router-link v-if="result.albums.total > result.albums.items.length" :to="more('album')">{{ $t('search.showAll', { n: result.albums.total }) }}</router-link></div>
          <group-grid :items="result.albums.items"></group-grid></template>
        <template v-if="result.tracks.total">
          <div class="section-head"><h2>{{ $t('search.tracks') }}</h2>
            <router-link v-if="result.tracks.total > result.tracks.items.length" :to="more('track')">{{ $t('search.showAll', { n: result.tracks.total }) }}</router-link></div>
          <track-list :tracks="result.tracks.items" @play="i => play(result.tracks.items, i)"></track-list></template>
        <template v-if="result.folders.total">
          <div class="section-head"><h2>{{ $t('nav.folders') }}</h2>
            <router-link v-if="result.folders.total > result.folders.items.length" :to="more('folder')">{{ $t('search.showAll', { n: result.folders.total }) }}</router-link></div>
          <group-grid :items="result.folders.items" mode="folder"></group-grid></template>
      </template>
      <template v-if="pager">
        <router-link :to="searchRoute(q)" class="muted">{{ $t('search.back') }}</router-link>
        <track-list v-if="kind === 'track'" :tracks="pagedItems" @play="i => play(pagedItems, i)"></track-list>
        <group-grid v-else :items="pagedItems" :mode="kind === 'folder' ? 'folder' : 'group'"></group-grid>
        <infinite-scroll :key="$route.fullPath" :pager="pager" :empty-text="$t('search.none')"></infinite-scroll>
      </template>
    </section>`
};

/** Route target for features that come later (personal playlists, favorites, history, shared playlists). */
export const PlaceholderPage = {
  name: 'PlaceholderPage',
  template: `
    <section class="card narrow">
      <h1>{{ $t(($route.meta && $route.meta.titleKey) || 'placeholder.title') }}</h1>
      <p class="muted">{{ $t('placeholder.text') }}</p>
    </section>`
};

/** One level of a configured tag navigation: its child groups (tiles) or its tracks, with the ancestors as crumbs. */
export const NavigationPage = {
  name: 'NavigationPage',
  components: COMPONENTS,
  data() { return { head: null, pager: null, mode: 'groups', browse: storedBrowse() }; },
  computed: {
    name() { return this.$route.params.name; },
    navName() { return this.head ? this.head.navigation : ''; },
    id() { return this.$route.params.id || '~'; },
    item() { return this.head ? this.head.item : null; },
    crumbs() { return this.head ? this.head.path.concat([this.head.item]) : []; },
    groups() { return this.mode === 'groups' && this.pager ? this.pager.state.items : []; },
    tracks() { return this.mode === 'tracks' && this.pager ? this.pager.state.items : []; }
  },
  watch: { '$route.fullPath': { immediate: true, handler() { this.load(); } } },
  methods: {
    base() { return '/nav/' + encodeURIComponent(this.name); },
    crumbRoute(c) { return c.id === '~' ? this.base() : this.base() + '/' + encodeURIComponent(c.id); },
    load() {
      const name = this.name, id = this.id;
      const scroll = this.browse === 'scroll';
      this.head = null;
      this.pager = pagerFor(async p => {
        const r = await Api.library(this.base() + '/' + encodeURIComponent(id),
          scroll ? Object.assign({ layout: 'scroll' }, p) : p);
        if (name === this.name && id === this.id) {
          this.head = r;
          this.mode = r.tracks ? 'tracks' : 'groups';
        }
        return { items: r.tracks || r.groups || [], total: r.total };
      }, 100);
    },
    /** Switching goes back to the start of the level: out of a range container, or a reload of the same level. */
    setBrowse(b) {
      if (b === this.browse) return;
      this.browse = b;
      storeBrowse(b);
      if (b === 'scroll' && this.item && this.item.type === 'bucket' && this.head) {
        const level = this.head.path.slice().reverse().find(c => c.type !== 'bucket');
        if (level) { this.$router.replace(this.crumbRoute(level)); return; }
      }
      this.load();
    },
    async everything() {
      if (!this.item) return [];
      return Api.groupTracks(this.item);
    },
    async playAt(i) {
      if (this.mode === 'tracks') { await this.pager.loadAll(100); playList(this.tracks, { start: i }); }
    },
    async playRandom() {
      try {
        const t = await Api.navRandom(this.item, this.item.random);
        if (!t.length) { toast(this.$t('toast.nothing')); return; }
        playList(t, { start: 0 });
      } catch (e) { toast(e.message); }
    },
    async playAll(shuffle) {
      try {
        const t = await this.everything();
        if (!t.length) { toast(this.$t('toast.nothing')); return; }
        playList(t, { shuffle: shuffle });
      } catch (e) { toast(e.message); }
    },
    formatTotal
  },
  template: `
    <section>
      <nav class="crumbs" v-if="head">
        <template v-for="(c, i) in crumbs" :key="c.id">
          <span v-if="i" class="sep">/</span>
          <router-link v-if="i < crumbs.length - 1" :to="crumbRoute(c)">{{ i === 0 ? navName : c.title }}</router-link>
          <span v-else>{{ i === 0 ? navName : c.title }}</span>
        </template>
      </nav>
      <header class="page-head" v-if="item">
        <div class="grow">
          <h1>{{ item.id === '~' ? navName : item.title }}</h1>
          <p class="muted">{{ $tn('count.tracks', item.trackCount) }}</p>
          <div class="row">
            <button type="button" class="primary" :disabled="!item.trackCount" @click="playAll(false)"><icon name="play"></icon> {{ $t('action.play') }}</button>
            <button type="button" :disabled="!item.trackCount" @click="playAll(true)"><icon name="shuffle"></icon> {{ $t('player.shuffle') }}</button>
            <button v-if="item.random > 0" type="button" :disabled="!item.trackCount" @click="playRandom"><icon name="shuffle"></icon> {{ $t('nav.random', { n: item.random }) }}</button>
          </div>
        </div>
      </header>
      <browse-switch :model-value="browse" @update:model-value="setBrowse"></browse-switch>
      <group-grid v-if="groups.length" :items="groups"></group-grid>
      <track-list v-if="tracks.length" :tracks="tracks" :show-album="true" @play="playAt"></track-list>
      <infinite-scroll v-if="pager" :key="name + id + browse" :pager="pager" :empty-text="$t('list.empty')"></infinite-scroll>
    </section>`
};
