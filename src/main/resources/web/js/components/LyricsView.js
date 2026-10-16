/* The lyrics of the playing track: time-synced lines follow the playback position, plain lyrics just scroll. */
import { Api } from '../api.js';
import { player } from '../player/index.js';
import { activeLine, isSynced } from '../core/lyrics.js';

export const LyricsView = {
  name: 'LyricsView',
  props: { track: { type: Object, required: true }, follow: { type: Boolean, default: true } },
  setup() { return { player: player }; },
  data() { return { lyrics: null, loading: false, error: '', canLookUp: false }; },
  computed: {
    synced() { return isSynced(this.lyrics); },
    sourceLabel() {
      const k = 'lyrics.source.' + this.lyrics.source;
      const t = this.$t(k);
      return t === k ? this.lyrics.source : t;
    },
    active() { return this.follow && this.synced ? activeLine(this.lyrics.lines, this.player.state.position) : -1; }
  },
  watch: {
    'track.path': { immediate: true, handler() { this.load(); } },
    active(i) {
      if (i < 0) return;
      this.$nextTick(() => {
        const el = this.$el && this.$el.querySelector ? this.$el.querySelector('.ly-line.on') : null;
        if (el && el.scrollIntoView) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      });
    }
  },
  methods: {
    async load() {
      const path = this.track.path;
      this.loading = true; this.error = ''; this.lyrics = null;
      try {
        const r = await Api.lyrics(path);
        if (path !== this.track.path) return;
        this.lyrics = r.lyrics; this.canLookUp = !!r.canLookUp;
      } catch (e) { this.error = e.message; } finally { this.loading = false; }
    },
    seek(line) { if (this.synced && line.t >= 0) this.player.seek(line.t / 1000); }
  },
  template: `
    <div class="lyrics">
      <p v-if="loading" class="muted">{{ $t('list.loading') }}</p>
      <p v-else-if="error" class="banner error">{{ error }}</p>
      <p v-else-if="!lyrics" class="muted">{{ $t('lyrics.none') }}</p>
      <ol v-else class="ly-lines" :class="{ synced: synced }">
        <li v-for="(l, i) in lyrics.lines" :key="i" class="ly-line" :class="{ on: i === active, blank: !l.text }"
            @click="seek(l)">{{ l.text || ' ' }}</li>
      </ol>
      <p v-if="lyrics" class="muted small">{{ sourceLabel }}</p>
    </div>`
};
