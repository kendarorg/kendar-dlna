/* Personal pages: playlists (own, shared, system), playlist detail/editor, favorites, recently/most played, Home blocks. */
import { reactive } from '../vue.js';
import { Api } from '../api.js';
import { player } from '../player/index.js';
import { Icon } from '../components/icons.js';
import { EntryList } from '../components/EntryList.js';
import { TrackList, InfiniteScroll } from '../components/lists.js';
import { playList, openMenu } from '../actions.js';
import { toast } from '../state.js';
import { createPager } from '../core/pager.js';
import {
  commitOps, entryPaths, fetchAllEntries, isConflict, playableTracks, playlistChoices, playlistRoute,
  pickRandom, statusCounts, validName
} from '../core/playlist-ops.js';
import { formatTotal } from '../core/format.js';
import { formatPosition } from '../core/queue-sync.js';
import { loadFavoriteTracks, loadPlaylistTracks, subjectFor } from '../personal/loaders.js';
import { favorites } from '../personal/favorites.js';
import { personal, continueFrom, dismissResume } from '../personal/runtime.js';

const TABS = [
  { scope: 'mine', to: '/me/playlists', label: 'nav.myPlaylists' },
  { scope: 'shared', to: '/shared', label: 'nav.sharedPlaylists' },
  { scope: 'system', to: '/system', label: 'nav.systemPlaylists' }
];

/** Mine / Shared / System lists of playlists. */
export const PlaylistListPage = {
  name: 'PlaylistListPage',
  components: { Icon },
  data() { return { lists: [], loading: true, error: '', name: '', busy: false, tabs: TABS }; },
  computed: {
    scope() { return this.$route.meta.scope; },
    mine() { return this.scope === 'mine'; }
  },
  created() { this.load(); },
  methods: {
    async load() {
      this.loading = true; this.error = '';
      try {
        const r = this.scope === 'system' ? await Api.systemPlaylists()
          : this.scope === 'shared' ? await Api.sharedPlaylists() : await Api.myPlaylists();
        this.lists = r.playlists;
      } catch (e) { this.error = e.message; } finally { this.loading = false; }
    },
    route(p) { return playlistRoute(this.scope, p); },
    async create() {
      if (!validName(this.name)) return;
      this.busy = true; this.error = '';
      try {
        const p = await Api.createMyPlaylist(this.name.trim());
        this.name = '';
        this.$router.push(playlistRoute('mine', p));
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async play(p, shuffle) {
      try {
        const tracks = await loadPlaylistTracks(this.scope, p);
        if (!tracks.length) { toast(this.$t('toast.nothing')); return; }
        playList(tracks, { shuffle: shuffle });
      } catch (e) { toast(e.message); }
    },
    menu(event, p) { openMenu(event, subjectFor(this.scope, p)); }
  },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t('nav.playlists') }}</h1></header>
      <nav class="tabs" :aria-label="$t('nav.playlists')">
        <router-link v-for="t in tabs" :key="t.scope" :to="t.to" class="tab" :class="{ on: t.scope === scope }"
                     :aria-current="t.scope === scope ? 'page' : null">{{ $t(t.label) }}</router-link>
      </nav>
      <p v-if="error" class="banner error">{{ error }}</p>
      <form v-if="mine" class="row create" @submit.prevent="create">
        <input v-model="name" maxlength="200" :placeholder="$t('playlist.newName')" :aria-label="$t('playlist.newName')">
        <button type="submit" class="primary" :disabled="busy || !name.trim()"><icon name="add"></icon> {{ $t('playlist.create') }}</button>
      </form>
      <p v-if="loading" class="muted">{{ $t('list.loading') }}</p>
      <p v-else-if="!lists.length" class="muted">{{ $t('playlist.empty.' + scope) }}</p>
      <ul class="plain pl-list">
        <li v-for="p in lists" :key="p.id || p.key" class="pl-row">
          <button type="button" class="icon-btn" :aria-label="$t('action.play')" :disabled="!p.count" @click="play(p, false)"><icon name="play"></icon></button>
          <div class="grow">
            <router-link :to="route(p)" class="pl-name">{{ p.name }}</router-link>
            <div class="s muted">
              {{ $tn('count.tracks', p.count) }}<template v-if="p.unavailable"> · {{ $tn('playlist.unavailable', p.unavailable) }}</template>
              <template v-if="scope === 'shared' && p.owner"> · {{ $t('playlist.by', { name: p.owner.displayName }) }}</template>
              <span v-if="p.shared && scope === 'mine'" class="badge ok">{{ $t('playlist.sharedBadge') }}</span>
            </div>
          </div>
          <button type="button" class="icon-btn" :aria-label="$t('player.shuffle')" :disabled="!p.count" @click="play(p, true)"><icon name="shuffle"></icon></button>
          <button type="button" class="icon-btn" :aria-label="$t('action.more')" aria-haspopup="menu" @click.stop="menu($event, p)"><icon name="more"></icon></button>
        </li>
      </ul>
    </section>`
};

/** One playlist: own (editor), shared or system (read-only, copy). */
export const PlaylistDetailPage = {
  name: 'PlaylistDetailPage',
  components: { Icon, EntryList },
  data() {
    return {
      head: null, entries: [], loading: true, error: '', info: '', busy: false,
      renaming: false, nameDraft: '', confirmDelete: false, fixing: null
    };
  },
  computed: {
    scope() { return this.$route.meta.scope; },
    mine() { return this.scope === 'mine'; },
    counts() { return statusCounts(this.entries); },
    tracks() { return playableTracks(this.entries); },
    playableIndex() {
      let n = 0;
      return this.entries.map(e => (e.status === 'ok' && e.track ? n++ : -1));
    },
    unavailable() { return this.head ? this.head.unavailable : 0; },
    total() { return formatTotal(this.tracks.reduce((s, t) => s + (t.durationMillis || 0), 0)); }
  },
  created() { this.load(); },
  methods: {
    page(q) {
      const p = this.$route.params;
      if (this.scope === 'system') return Api.systemPlaylist(p.key, q);
      if (this.scope === 'shared') return Api.sharedPlaylist(p.owner, p.id, q);
      return Api.myPlaylist(p.id, q);
    },
    async load(quiet) {
      if (!quiet) this.loading = true;
      this.error = '';
      try {
        const r = await fetchAllEntries(q => this.page(q), 1000);
        this.head = r.head;
        this.entries = r.entries;
        this.nameDraft = r.head.name;
      } catch (e) { this.error = e.message; } finally { this.loading = false; }
    },
    state() {
      return { name: this.head.name, description: this.head.description || '', paths: entryPaths(this.entries), version: this.head.version };
    },
    /** Applies edit operations; on a version conflict the playlist is reloaded and the same edits are re-applied. */
    async edit(ops) {
      this.busy = true; this.error = ''; this.info = '';
      try {
        const id = this.head.id;
        const r = await commitOps({
          base: this.state(), ops: ops,
          load: async () => { await this.load(true); return this.state(); },
          save: (body, version) => Api.updateMyPlaylist(id, Object.assign({ version: version }, body))
        });
        if (r.reapplied) this.info = this.$t('playlist.reapplied');
        await this.load(true);
      } catch (e) {
        this.error = e.message;
        await this.load(true);
      } finally { this.busy = false; }
    },
    /** Runs a versioned call; after a conflict the playlist is reloaded and the call repeated once. */
    async withConflict(fn) {
      try { return await fn(); } catch (e) {
        if (!isConflict(e)) throw e;
        await this.load(true);
        this.info = this.$t('playlist.reapplied');
        return fn();
      }
    },
    move(ev) { this.edit([{ type: 'move', index: ev.entry.index, path: ev.entry.path, direction: ev.direction }]); },
    remove(entry) { this.edit([{ type: 'remove', index: entry.index, path: entry.path }]); },
    async rename() {
      const name = this.nameDraft.trim();
      this.renaming = false;
      if (!validName(name) || name === this.head.name) { this.nameDraft = this.head.name; return; }
      await this.edit([{ type: 'rename', name: name }]);
    },
    async share(on) {
      this.busy = true; this.error = '';
      try {
        await this.withConflict(() => Api.shareMyPlaylist(this.head.id, on, this.head.version));
        await this.load(true);
        toast(this.$t(on ? 'playlist.shared' : 'playlist.unshared'));
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async del() {
      this.busy = true; this.error = '';
      try {
        await Api.deleteMyPlaylist(this.head.id);
        toast(this.$t('playlist.deleted', { name: this.head.name }));
        this.$router.replace('/me/playlists');
      } catch (e) { this.error = e.message; this.confirmDelete = false; } finally { this.busy = false; }
    },
    async copy() {
      this.busy = true; this.error = '';
      try {
        const p = this.$route.params;
        const source = this.scope === 'system' ? { source: 'system', key: p.key } : { source: 'shared', owner: p.owner, id: p.id };
        const created = await Api.copyPlaylistFrom(source);
        toast(this.$t('playlist.copied', { name: created.name }));
        this.$router.push(playlistRoute('mine', created));
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    play(shuffle) {
      if (!this.tracks.length) { toast(this.$t('toast.nothing')); return; }
      playList(this.tracks, { shuffle: shuffle });
    },
    playAt(i) {
      const k = this.playableIndex[i];
      if (k >= 0) playList(this.tracks, { start: k });
    },
    menu(event) {
      openMenu(event, subjectFor(this.scope, Object.assign({}, this.head, { name: this.head.name })));
    },
    // ---- fix path ----
    startFix(entry) { this.fixing = { entry: entry, q: '', path: '', hits: [], searched: false, busy: false, error: '' }; },
    async fixSearch() {
      const f = this.fixing;
      if (!f.q.trim()) return;
      f.busy = true; f.error = '';
      try { f.hits = (await Api.search(f.q, 'track', { limit: 10 })).tracks.items; f.searched = true; }
      catch (e) { f.error = e.message; } finally { f.busy = false; }
    },
    async fixWith(path) {
      const f = this.fixing;
      if (!path) return;
      f.busy = true; f.error = '';
      try {
        const entry = f.entry;
        await this.withConflict(() => {
          const live = this.entries.find(e => e.path === entry.path) || entry;
          return Api.fixMyPlaylistPath(this.head.id, live.index, path, this.head.version);
        });
        this.fixing = null;
        await this.load(true);
        toast(this.$t('entry.fixed'));
      } catch (e) { f.error = e.message; f.busy = false; }
    }
  },
  template: `
    <section>
      <nav class="crumbs"><router-link :to="$route.meta.scope === 'mine' ? '/me/playlists' : ($route.meta.scope === 'shared' ? '/shared' : '/system')">{{ $t('nav.playlists') }}</router-link></nav>
      <p v-if="loading" class="muted">{{ $t('list.loading') }}</p>
      <p v-if="error" class="banner error">{{ error }}</p>
      <p v-if="info" class="banner info" role="status">{{ info }}</p>
      <template v-if="head">
        <header class="page-head pl-head">
          <div class="grow">
            <form v-if="mine && renaming" class="row" @submit.prevent="rename">
              <input v-model="nameDraft" maxlength="200" :aria-label="$t('playlist.rename')" @keydown.esc="renaming = false; nameDraft = head.name">
              <button type="submit" class="primary" :disabled="!nameDraft.trim()">{{ $t('common.save') }}</button>
              <button type="button" @click="renaming = false; nameDraft = head.name">{{ $t('common.cancel') }}</button>
            </form>
            <h1 v-else>{{ head.name }}
              <button v-if="mine" type="button" class="icon-btn" :aria-label="$t('playlist.rename')" :title="$t('playlist.rename')" @click="renaming = true"><icon name="edit"></icon></button></h1>
            <p v-if="head.description" class="muted">{{ head.description }}</p>
            <p class="muted">
              {{ $tn('count.tracks', tracks.length) }}<span v-if="total"> · {{ total }}</span>
              <template v-if="scope === 'shared' && head.owner"> · {{ $t('playlist.by', { name: head.owner.displayName }) }}</template>
              <template v-if="scope === 'system'"> · {{ $t('nav.systemPlaylists') }}</template>
              <span v-if="mine && head.shared" class="badge ok">{{ $t('playlist.sharedBadge') }}</span>
              <span v-if="unavailable" class="badge warn">{{ $tn('playlist.unavailable', unavailable) }}</span>
              <span v-if="mine && counts.missing" class="badge bad">{{ $tn('playlist.missing', counts.missing) }}</span>
            </p>
            <div class="row">
              <button type="button" class="primary" :disabled="!tracks.length" @click="play(false)"><icon name="play"></icon> {{ $t('action.play') }}</button>
              <button type="button" :disabled="!tracks.length" @click="play(true)"><icon name="shuffle"></icon> {{ $t('player.shuffle') }}</button>
              <button v-if="!mine" type="button" :disabled="busy || !tracks.length" @click="copy"><icon name="copy"></icon> {{ $t('playlist.copyToMine') }}</button>
              <template v-if="mine">
                <button v-if="!head.shared" type="button" :disabled="busy" @click="share(true)"><icon name="share"></icon> {{ $t('playlist.share') }}</button>
                <button v-else type="button" :disabled="busy" @click="share(false)"><icon name="share"></icon> {{ $t('playlist.unshare') }}</button>
                <template v-if="confirmDelete">
                  <span>{{ $t('playlist.confirmDelete', { name: head.name }) }}</span>
                  <button type="button" class="danger" :disabled="busy" @click="del">{{ $t('common.delete') }}</button>
                  <button type="button" @click="confirmDelete = false">{{ $t('common.cancel') }}</button>
                </template>
                <button v-else type="button" @click="confirmDelete = true"><icon name="trash"></icon> {{ $t('common.delete') }}</button>
              </template>
              <button type="button" class="icon-btn" :aria-label="$t('action.more')" aria-haspopup="menu" @click.stop="menu($event)"><icon name="more"></icon></button>
            </div>
          </div>
        </header>

        <div v-if="fixing" class="card fixbox" role="dialog" :aria-label="$t('entry.fix')">
          <h3>{{ $t('entry.fixTitle') }}</h3>
          <p class="muted path">{{ fixing.entry.path }}</p>
          <p v-if="fixing.error" class="banner error">{{ fixing.error }}</p>
          <form class="row" @submit.prevent="fixSearch">
            <input v-model="fixing.q" type="search" :placeholder="$t('search.placeholder')" :aria-label="$t('search.label')">
            <button type="submit" :disabled="fixing.busy">{{ $t('search.label') }}</button>
          </form>
          <ul class="plain hits">
            <li v-for="h in fixing.hits" :key="h.path" class="row">
              <span class="grow">{{ h.title || h.name }} <span class="muted">{{ h.artist }} · {{ h.path }}</span></span>
              <button type="button" :disabled="fixing.busy" @click="fixWith(h.path)">{{ $t('entry.useThis') }}</button>
            </li>
          </ul>
          <p v-if="fixing.searched && !fixing.hits.length" class="muted">{{ $t('search.none') }}</p>
          <form class="row" @submit.prevent="fixWith(fixing.path.trim())">
            <input v-model="fixing.path" :placeholder="$t('entry.pathPlaceholder')" :aria-label="$t('entry.pathPlaceholder')">
            <button type="submit" :disabled="fixing.busy || !fixing.path.trim()">{{ $t('entry.usePath') }}</button>
            <button type="button" @click="fixing = null">{{ $t('common.cancel') }}</button>
          </form>
        </div>

        <p v-if="!entries.length" class="muted">{{ $t(mine ? 'playlist.emptyMine' : 'list.empty') }}</p>
        <entry-list :entries="entries" :editable="mine && !busy" :removable="false" :can-fix="mine" :numbered="true"
                    @play="playAt" @move="move" @remove="remove" @fix="startFix"></entry-list>
      </template>
    </section>`
};

/** Favorites: the special, non-deletable list. */
export const FavoritesPage = {
  name: 'FavoritesPage',
  components: { Icon, EntryList, InfiniteScroll },
  setup() { return { favorites: favorites }; },
  data() { return { pager: null, busy: false }; },
  created() {
    this.pager = createPager(async p => {
      const r = await Api.favorites(p);
      return { items: r.entries, total: r.total };
    }, { limit: 200, reactive: reactive });
  },
  computed: {
    entries() { return this.pager.state.items; }
  },
  methods: {
    async all() { await this.pager.loadAll(100); return playableTracks(this.entries); },
    async play(shuffle) {
      try {
        const tracks = await this.all();
        if (!tracks.length) { toast(this.$t('toast.nothing')); return; }
        playList(tracks, { shuffle: shuffle });
      } catch (e) { toast(e.message); }
    },
    async playAt(i) {
      const tracks = await this.all();
      const path = this.entries[i].path;
      playList(tracks, { start: Math.max(0, tracks.findIndex(t => t.path === path)) });
    },
    async remove(entry) {
      try {
        if (favorites.has(entry.path)) await favorites.remove([entry.path]); else await Api.removeFavorites([entry.path]);
        const items = this.pager.state.items;
        const i = items.indexOf(entry);
        if (i >= 0) items.splice(i, 1);
      } catch (e) { toast(e.message); }
    }
  },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t('nav.favorites') }}</h1>
        <span v-if="pager.state.total !== null" class="muted">{{ $tn('count.tracks', pager.state.total) }}</span></header>
      <div class="row">
        <button type="button" class="primary" :disabled="!entries.length" @click="play(false)"><icon name="play"></icon> {{ $t('action.play') }}</button>
        <button type="button" :disabled="!entries.length" @click="play(true)"><icon name="shuffle"></icon> {{ $t('player.shuffle') }}</button>
      </div>
      <p class="muted" v-if="!entries.length && pager.state.done">{{ $t('favorites.empty') }}</p>
      <entry-list :entries="entries" :removable="true" @play="playAt" @remove="remove"></entry-list>
      <infinite-scroll :pager="pager" :empty-text="''"></infinite-scroll>
    </section>`
};

/** Recently played (kind 'recent') and most played (kind 'most'). */
export const HistoryPage = {
  name: 'HistoryPage',
  components: { Icon, EntryList, InfiniteScroll },
  data() { return { pager: null }; },
  computed: {
    kind() { return this.$route.meta.kind; },
    entries() { return this.pager.state.items; }
  },
  created() {
    const most = this.kind === 'most';
    this.pager = createPager(async p => {
      const r = await (most ? Api.mostPlayed(p) : Api.recentlyPlayed(p));
      return { items: r.entries, total: r.total };
    }, { limit: 100, reactive: reactive });
  },
  methods: {
    extra(e) {
      if (this.kind === 'most') return this.$tn('history.plays', e.playCount || 0);
      return e.playedAt ? new Date(e.playedAt).toLocaleString() : '';
    },
    play(i) { playList(this.entries.map(e => e.track), { start: i }); },
    playAll() { if (this.entries.length) playList(this.entries.map(e => e.track), {}); },
    async forget(e) {
      try {
        await (this.kind === 'most' ? Api.forgetMostPlayed(e.path) : Api.forgetRecent(e.path));
        const items = this.pager.state.items;
        const i = items.indexOf(e);
        if (i >= 0) items.splice(i, 1);
      } catch (err) { toast(err.message); }
    }
  },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t(kind === 'most' ? 'nav.mostPlayed' : 'nav.history') }}</h1></header>
      <div class="row"><button type="button" class="primary" :disabled="!entries.length" @click="playAll"><icon name="play"></icon> {{ $t('action.play') }}</button></div>
      <p v-if="pager.state.done && !entries.length" class="muted">{{ $t('history.empty') }}</p>
      <entry-list :entries="entries" :extra="extra" :numbered="kind === 'most'" :removable="true" @play="play" @remove="forget"></entry-list>
      <infinite-scroll :pager="pager" :empty-text="''"></infinite-scroll>
    </section>`
};

// ---- Home blocks ----

/** Quick actions: continue listening, random playlist, favorites. */
export const HomeQuick = {
  name: 'HomeQuick',
  components: { Icon },
  setup() { return { personal: personal }; },
  data() { return { busy: false }; },
  computed: {
    resume() { return this.personal.resume; },
    resumeText() {
      const r = this.resume;
      return r ? (r.track.title || r.track.name) + ' · ' + formatPosition(r.positionSeconds) : '';
    }
  },
  methods: {
    cont() { if (this.resume) continueFrom(this.resume); },
    async randomPlaylist() {
      this.busy = true;
      try {
        const [mine, shared, system] = await Promise.all([Api.myPlaylists(), Api.sharedPlaylists(), Api.systemPlaylists()]);
        const choice = pickRandom(playlistChoices(mine.playlists, shared.playlists, system.playlists));
        if (!choice) { toast(this.$t('home.noPlaylists')); return; }
        const tracks = await loadPlaylistTracks(choice.scope, choice.playlist);
        if (!tracks.length) { toast(this.$t('toast.nothing')); return; }
        playList(tracks, { start: 0 });
        toast(this.$t('home.playingPlaylist', { name: choice.playlist.name }));
      } catch (e) { toast(e.message); } finally { this.busy = false; }
    },
    async playFavorites() {
      this.busy = true;
      try {
        const tracks = await loadFavoriteTracks();
        if (!tracks.length) { toast(this.$t('favorites.empty')); return; }
        playList(tracks, { shuffle: true });
      } catch (e) { toast(e.message); } finally { this.busy = false; }
    }
  },
  template: `
    <div class="quick">
      <button v-if="resume" type="button" class="primary quick-btn" @click="cont"><icon name="play"></icon>
        <span>{{ $t('resume.continue') }}<small>{{ resumeText }}</small></span></button>
      <button type="button" class="quick-btn" :disabled="busy" @click="randomPlaylist"><icon name="playlist"></icon>
        <span>{{ $t('home.randomPlaylist') }}</span></button>
      <button type="button" class="quick-btn" :disabled="busy" @click="playFavorites"><icon name="heart"></icon>
        <span>{{ $t('home.playFavorites') }}</span></button>
    </div>`
};

/** A short list of favorites or recent plays below the random mix. */
export function makeHomeList(kind) {
  return {
    name: kind === 'favorites' ? 'HomeFavorites' : 'HomeHistory',
    components: { TrackList },
    data() { return { tracks: [] }; },
    async created() {
      try {
        const r = kind === 'favorites' ? await Api.favorites({ limit: 8 }) : await Api.recentlyPlayed({ limit: 8 });
        this.tracks = playableTracks(r.entries);
      } catch (e) { /* the page still works without this block */ }
    },
    methods: { play(i) { playList(this.tracks, { start: i }); } },
    template: `
      <template v-if="tracks.length">
        <div class="section-head"><h2>{{ $t(kind === 'favorites' ? 'nav.favorites' : 'nav.history') }}</h2>
          <router-link :to="kind === 'favorites' ? '/me/favorites' : '/me/history'">{{ $t('home.seeAll') }}</router-link></div>
        <track-list :tracks="tracks" :show-album="true" @play="play"></track-list>
      </template>`,
    setup() { return { kind: kind }; }
  };
}

/** Banners for "continue listening" and "playing on another device". */
export const PersonalNotices = {
  name: 'PersonalNotices',
  components: { Icon },
  setup() { return { personal: personal }; },
  methods: {
    text(o) { return (o.track.title || o.track.name) + (o.track.artist ? ' – ' + o.track.artist : ''); },
    pos(o) { return formatPosition(o.positionSeconds); },
    go(o) { continueFrom(o); },
    closeResume() { dismissResume(); },
    closeRemote() { this.personal.remote = null; }
  },
  template: `
    <div class="notices" v-if="(personal.resume && $route.path !== '/') || personal.remote">
      <div v-if="personal.remote" class="notice remote" role="status">
        <icon name="people"></icon>
        <span class="grow">{{ $t('remote.playing', { title: text(personal.remote) }) }}</span>
        <button type="button" class="primary" @click="go(personal.remote)">{{ $t('remote.takeOver') }}</button>
        <button type="button" @click="closeRemote">{{ $t('common.dismiss') }}</button>
      </div>
      <div v-if="personal.resume && $route.path !== '/'" class="notice resume" role="status">
        <icon name="play"></icon>
        <span class="grow">{{ $t('resume.banner', { title: text(personal.resume), position: pos(personal.resume) }) }}</span>
        <button type="button" class="primary" @click="go(personal.resume)">{{ $t('resume.continue') }}</button>
        <button type="button" @click="closeResume">{{ $t('common.dismiss') }}</button>
      </div>
    </div>`
};
