/* Small shared components. */
import { Api } from '../api.js';

/** Message banner: <banner :message="text" kind="error|ok|info"> */
export const Banner = {
  name: 'Banner',
  props: { message: { type: String, default: '' }, kind: { type: String, default: 'error' } },
  template: '<div v-if="message" class="banner" :class="kind" role="status">{{ message }}</div>'
};

/** Reorder buttons for one list row; emits move with the direction. */
export const MoveButtons = {
  name: 'MoveButtons',
  props: { index: Number, count: Number },
  emits: ['move'],
  template: `
    <span class="moves">
      <button type="button" class="icon" title="Move to top" :disabled="index === 0" @click="$emit('move', 'top')">&#10514;</button>
      <button type="button" class="icon" title="Move up" :disabled="index === 0" @click="$emit('move', 'up')">&#8593;</button>
      <button type="button" class="icon" title="Move down" :disabled="index === count - 1" @click="$emit('move', 'down')">&#8595;</button>
      <button type="button" class="icon" title="Move to bottom" :disabled="index === count - 1" @click="$emit('move', 'bottom')">&#10515;</button>
    </span>`
};

/** Search box with results (tracks or folders); emits pick with the chosen hit. */
export const SearchPicker = {
  name: 'SearchPicker',
  props: { kind: { type: String, default: 'track' }, actionLabel: { type: String, default: 'Add' } },
  emits: ['pick'],
  data() { return { q: '', hits: [], total: 0, truncated: false, busy: false, error: '', searched: false }; },
  methods: {
    async run() {
      if (!this.q.trim()) return;
      this.busy = true; this.error = '';
      try {
        const r = await Api.adminSearch(this.q, this.kind);
        this.hits = r.hits; this.total = r.total; this.truncated = r.truncated; this.searched = true;
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    label(h) {
      if (h.type === 'folder') return h.displayName + '  (' + h.path + ')';
      const t = h.track;
      return (t.displayTitle || t.name) + (t.artist ? ' — ' + t.artist : '') + (t.album ? ' [' + t.album + ']' : '')
        + '  (' + h.path + ')';
    }
  },
  template: `
    <div class="search-picker">
      <form class="row" @submit.prevent="run">
        <input type="search" v-model="q" placeholder="Search title, artist, composer, album, file name..." aria-label="Search">
        <button type="submit" :disabled="busy">Search</button>
      </form>
      <banner :message="error"></banner>
      <p v-if="searched && !hits.length" class="muted">Nothing found.</p>
      <ul class="plain hits" v-if="hits.length">
        <li v-for="h in hits" :key="h.path" class="row">
          <span class="grow">{{ label(h) }}</span>
          <button type="button" @click="$emit('pick', h)">{{ actionLabel }}</button>
        </li>
      </ul>
      <p v-if="truncated" class="muted">{{ total }} matches, showing the first {{ hits.length }}. Refine the search.</p>
    </div>`
};
