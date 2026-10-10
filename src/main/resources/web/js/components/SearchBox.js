/* Top-bar search with typeahead. */
import { Icon } from './icons.js';
import { Cover } from './media.js';
import { Api } from '../api.js';
import { player } from '../player/index.js';
import { suggestionRoute, searchRoute } from '../core/urls.js';
import { toast } from '../state.js';

export const SearchBox = {
  name: 'SearchBox',
  components: { Icon, Cover },
  data() { return { q: '', items: [], open: false, active: -1, seq: 0, timer: null }; },
  watch: {
    '$route.query.q': {
      immediate: true,
      handler(v) { if (this.$route.path === '/search') this.q = v || ''; }
    }
  },
  beforeUnmount() { clearTimeout(this.timer); },
  methods: {
    onInput() {
      clearTimeout(this.timer);
      this.active = -1;
      if (!this.q.trim()) { this.items = []; return; }
      this.timer = setTimeout(() => this.fetch(), 200);
    },
    async fetch() {
      const seq = ++this.seq;
      try {
        const r = await Api.suggest(this.q.trim(), 8);
        if (seq === this.seq) { this.items = r.suggestions; this.open = true; }
      } catch (e) { if (seq === this.seq) this.items = []; }
    },
    typeLabel(s) { return this.$t('search.type.' + s.type); },
    submit() {
      if (this.active >= 0 && this.items[this.active]) { this.pick(this.items[this.active]); return; }
      const q = this.q.trim();
      if (!q) return;
      this.close();
      this.$router.push(searchRoute(q));
    },
    async pick(s) {
      this.close();
      if (s.type === 'track') {
        try { player.playNow((await Api.library('/tracks', { path: s.path })).track); } catch (e) { toast(e.message); }
        return;
      }
      const to = suggestionRoute(s);
      if (to) this.$router.push(to);
    },
    close() { this.open = false; this.items = []; this.active = -1; this.seq++; clearTimeout(this.timer); },
    onKey(e) {
      if (e.key === 'ArrowDown' && this.items.length) { this.active = (this.active + 1) % this.items.length; e.preventDefault(); }
      else if (e.key === 'ArrowUp' && this.items.length) { this.active = (this.active - 1 + this.items.length) % this.items.length; e.preventDefault(); }
      else if (e.key === 'Escape') { this.close(); }
    },
    onBlur() { setTimeout(() => { this.open = false; }, 150); }
  },
  template: `
    <form class="search" role="search" @submit.prevent="submit">
      <icon name="search"></icon>
      <input type="search" v-model="q" :placeholder="$t('search.placeholder')" :aria-label="$t('search.label')"
             role="combobox" aria-autocomplete="list" :aria-expanded="open && items.length > 0" autocomplete="off"
             @input="onInput" @keydown="onKey" @focus="open = items.length > 0" @blur="onBlur">
      <ul v-if="open && items.length" class="suggest" role="listbox">
        <li v-for="(s, i) in items" :key="s.type + (s.id || s.path)" role="option" :aria-selected="i === active"
            :class="{ active: i === active }" @mousedown.prevent="pick(s)" @mousemove="active = i">
          <cover :art="s.art" size="small" :icon="s.type === 'artist' ? 'artist' : (s.type === 'folder' ? 'folder' : (s.type === 'track' ? 'note' : 'album'))"></cover>
          <span class="lbl">{{ s.label }}<small v-if="s.detail" class="muted"> {{ s.detail }}</small></span>
          <small class="kind">{{ typeLabel(s) }}</small>
        </li>
      </ul>
    </form>`
};
