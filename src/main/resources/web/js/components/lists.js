/* Track list, tiles for albums/artists/genres/folders, infinite scroll. */
import { Icon } from './icons.js';
import { Cover } from './media.js';
import { FavButton } from './FavButton.js';
import { player } from '../player/index.js';
import { openMenu, trackSubject, groupSubject, folderSubject } from '../actions.js';
import { formatDuration, formatTotal } from '../core/format.js';
import { groupRoute, folderRoute } from '../core/urls.js';
import { toast } from '../state.js';

const COLUMNS = [
  { key: 'title', label: 'list.title', cls: 'c-title' },
  { key: 'album', label: 'list.album', cls: 'c-album' },
  { key: 'durationMillis', label: 'list.duration', cls: 'c-dur' }
];

export const TrackList = {
  name: 'TrackList',
  components: { Icon, Cover, FavButton },
  props: {
    tracks: { type: Array, required: true },
    sortable: Boolean,
    sortKey: { type: String, default: '' },
    sortDir: { type: String, default: 'asc' },
    showAlbum: { type: Boolean, default: true },
    showAdded: Boolean,
    numbered: Boolean
  },
  emits: ['play', 'sort'],
  setup() { return { player: player, formatDuration }; },
  computed: {
    columns() { return COLUMNS.filter(c => this.showAlbum || c.key !== 'album'); },
    playingPath() { const c = this.player.current; return c ? c.track.path : null; },
    isPlaying() { return this.player.state.status === 'playing' || this.player.state.status === 'loading'; }
  },
  methods: {
    menu(event, track) { openMenu(event, trackSubject(track)); },
    rowPlay(i, track) {
      if (track.path === this.playingPath) this.player.toggle(); else this.$emit('play', i);
    },
    arrow(key) { return this.sortKey === key ? (this.sortDir === 'desc' ? ' ▼' : ' ▲') : ''; },
    added(ms) { return ms ? new Date(ms).toLocaleDateString() : ''; }
  },
  template: `
    <div class="tracklist" role="table" :class="{ noalbum: !showAlbum }">
      <div v-if="sortable" class="tl-row tl-head" role="row">
        <span class="c-play"></span><span class="c-art"></span>
        <button v-for="c in columns" :key="c.key" type="button" class="linklike" :class="c.cls" @click="$emit('sort', c.key)">
          {{ $t(c.label) }}{{ arrow(c.key) }}</button>
        <span v-if="showAdded" class="c-added"></span>
        <span class="c-more"></span>
      </div>
      <div v-for="(tr, i) in tracks" :key="i + '|' + tr.path" class="tl-row" role="row"
           :class="{ current: tr.path === playingPath }" @dblclick="$emit('play', i)" @contextmenu.prevent="menu($event, tr)">
        <button type="button" class="icon-btn c-play" :aria-label="$t('player.play')" @click="rowPlay(i, tr)">
          <span v-if="numbered && tr.path !== playingPath" class="num">{{ tr.trackNo || i + 1 }}</span>
          <icon v-else :name="tr.path === playingPath && isPlaying ? 'pause' : 'play'"></icon></button>
        <cover class="c-art" :art="tr.art" size="small"></cover>
        <div class="c-title"><div class="t">{{ tr.title || tr.name }}</div><div class="s">{{ tr.artist }}</div></div>
        <div v-if="showAlbum" class="c-album">{{ tr.album }}</div>
        <div class="c-dur">{{ formatDuration(tr.durationMillis) }}</div>
        <div v-if="showAdded" class="c-added">{{ added(tr.addedAt) }}</div>
        <span class="c-more"><fav-button :track="tr"></fav-button>
          <button type="button" class="icon-btn" :aria-label="$t('action.more')" aria-haspopup="menu"
                  @click.stop="menu($event, tr)"><icon name="more"></icon></button></span>
      </div>
    </div>`
};

/** Tiles for group items (mode 'group') or folder entries (mode 'folder'). */
export const GroupGrid = {
  name: 'GroupGrid',
  components: { Icon, Cover },
  props: { items: { type: Array, required: true }, mode: { type: String, default: 'group' } },
  setup() { return { formatTotal }; },
  methods: {
    title(g) { return this.mode === 'folder' ? (g.displayName || g.name) : g.title; },
    route(g) { return this.mode === 'folder' ? folderRoute(g.path) : groupRoute(g); },
    subject(g) { return this.mode === 'folder' ? folderSubject(g) : groupSubject(g); },
    sub(g) {
      if (this.mode === 'folder') return '';
      if (g.type === 'artist' || g.type === 'genre') {
        const parts = [];
        if (g.albumCount !== undefined) parts.push(this.$tn('count.albums', g.albumCount));
        parts.push(this.$tn('count.tracks', g.trackCount));
        return parts.join(' · ');
      }
      return [g.artist, g.year || null].filter(Boolean).join(' · ') || this.$tn('count.tracks', g.trackCount);
    },
    async play(g) {
      try {
        const tracks = await this.subject(g).tracks();
        if (tracks.length) player.playTracks(tracks, 0); else toast(this.$t('toast.nothing'));
      } catch (e) { toast(e.message); }
    },
    menu(event, g) { openMenu(event, this.subject(g)); }
  },
  template: `
    <div class="tiles">
      <div v-for="g in items" :key="mode + '|' + (g.id || g.path)" class="tile" @contextmenu.prevent="menu($event, g)">
        <div class="tile-art">
          <router-link :to="route(g)" :aria-label="title(g)" tabindex="-1"><cover :art="g.art" size="medium"
            :icon="mode === 'folder' ? 'folder' : (g.type === 'artist' ? 'artist' : (g.type === 'genre' ? 'genre' : 'album'))"></cover></router-link>
          <button type="button" class="tile-play icon-btn" :aria-label="$t('action.play')" @click="play(g)"><icon name="play"></icon></button>
          <button type="button" class="tile-more icon-btn" :aria-label="$t('action.more')" aria-haspopup="menu"
                  @click.stop="menu($event, g)"><icon name="more"></icon></button>
        </div>
        <router-link :to="route(g)" class="tile-title">{{ title(g) }}<span v-if="g.virtual" class="badge" :title="$t('badge.virtual')">cue</span></router-link>
        <div class="tile-sub">{{ sub(g) }}</div>
      </div>
    </div>`
};

/** Calls pager.loadMore() whenever the end of the list scrolls into view. */
export const InfiniteScroll = {
  name: 'InfiniteScroll',
  props: { pager: { type: Object, required: true }, emptyText: { type: String, default: '' } },
  data() { return { observer: null, supported: typeof IntersectionObserver !== 'undefined' }; },
  computed: { st() { return this.pager.state; } },
  watch: {
    'st.generation'() { this.$nextTick(() => this.recheck()); },
    'st.loading'(now) { if (!now) this.$nextTick(() => this.recheck()); }
  },
  mounted() {
    if (this.supported) {
      this.observer = new IntersectionObserver(entries => {
        if (entries.some(e => e.isIntersecting)) this.pager.loadMore();
      }, { rootMargin: '400px' });
      this.observer.observe(this.$refs.sentinel);
    } else {
      this.pager.loadMore();
    }
  },
  beforeUnmount() { if (this.observer) this.observer.disconnect(); },
  methods: {
    recheck() {
      if (!this.observer || this.st.done || this.st.loading) return;
      this.observer.unobserve(this.$refs.sentinel);   // observing again reports the current intersection
      this.observer.observe(this.$refs.sentinel);
    }
  },
  template: `
    <div class="more">
      <p v-if="st.error" class="banner error">{{ st.error }}</p>
      <p v-else-if="st.done && !st.items.length && emptyText" class="muted">{{ emptyText }}</p>
      <p v-else-if="st.loading" class="muted">{{ $t('list.loading') }}</p>
      <button v-if="!supported && !st.done && !st.loading" type="button" @click="pager.loadMore()">{{ $t('list.loadMore') }}</button>
      <div ref="sentinel" class="sentinel"></div>
    </div>`
};
