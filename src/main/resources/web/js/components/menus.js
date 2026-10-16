/* Action menu, track info, user menu, shortcuts dialog. */
import { Icon } from './icons.js';
import { ui } from '../state.js';
import { actionsFor } from '../actions.js';
import { session, Api } from '../api.js';
import { SHORTCUTS } from '../core/keys.js';
import { formatClock, formatSize, formatTime, formatSegmentRange } from '../core/format.js';
import { player } from '../player/index.js';
import { LyricsView } from './LyricsView.js';
import { beforeLogout } from '../personal/runtime.js';

export const ActionMenu = {
  name: 'ActionMenu',
  components: { Icon },
  setup() { return { ui: ui }; },
  data() { return { left: 0, top: 0 }; },
  computed: {
    entries() { return this.ui.menu ? actionsFor(this.ui.menu.subject) : []; },
    heading() { return this.ui.menu ? this.ui.menu.subject.title : ''; }
  },
  watch: {
    'ui.menu'(m) {
      if (!m) return;
      this.left = m.x; this.top = m.y;
      this.$nextTick(() => {
        const el = this.$refs.menu;
        if (!el) return;
        const r = el.getBoundingClientRect();
        this.left = Math.max(4, Math.min(m.x, window.innerWidth - r.width - 4));
        this.top = Math.max(4, Math.min(m.y, window.innerHeight - r.height - 4));
        const first = el.querySelector('button');
        if (first) first.focus();
      });
    }
  },
  mounted() {
    this.onDoc = e => { if (this.ui.menu && !(this.$refs.menu && this.$refs.menu.contains(e.target))) this.close(); };
    this.onKey = e => { if (this.ui.menu && e.key === 'Escape') { this.close(); e.stopPropagation(); } };
    document.addEventListener('mousedown', this.onDoc, true);
    document.addEventListener('keydown', this.onKey, true);
    window.addEventListener('scroll', this.close, true);
    window.addEventListener('resize', this.close);
  },
  beforeUnmount() {
    document.removeEventListener('mousedown', this.onDoc, true);
    document.removeEventListener('keydown', this.onKey, true);
    window.removeEventListener('scroll', this.close, true);
    window.removeEventListener('resize', this.close);
  },
  methods: {
    close() { this.ui.menu = null; },
    run(entry) { this.close(); entry.run(); },
    move(e, dir) {
      const buttons = Array.from(this.$refs.menu.querySelectorAll('button'));
      const i = buttons.indexOf(document.activeElement);
      const next = buttons[(i + dir + buttons.length) % buttons.length];
      if (next) { next.focus(); e.preventDefault(); }
    }
  },
  template: `
    <div v-if="ui.menu && entries.length" ref="menu" class="menu" role="menu" :aria-label="heading"
         :style="{ left: left + 'px', top: top + 'px' }" @keydown.down="move($event, 1)" @keydown.up="move($event, -1)">
      <div class="menu-title">{{ heading }}</div>
      <button v-for="e in entries" :key="e.id" type="button" role="menuitem" @click="run(e)">
        <icon v-if="e.icon" :name="e.icon"></icon><span v-else class="ic"></span>{{ e.label }}</button>
    </div>`
};

/** Definition list of a track's metadata. */
export const TrackInfo = {
  name: 'TrackInfo',
  props: { track: { type: Object, required: true } },
  computed: {
    rows() {
      const t = this.track;
      const disc = t.discNo ? (t.discTotal ? t.discNo + '/' + t.discTotal : String(t.discNo)) : '';
      const rows = [
        ['info.title', t.title || t.name], ['info.artist', t.artist], ['info.album', t.album],
        ['info.albumArtist', t.albumArtist], ['info.composer', t.composer], ['info.transcriber', t.transcriber], ['info.genre', t.genre],
        ['info.year', t.year || ''], ['info.trackNo', t.trackNo || ''], ['info.disc', disc],
        ['info.duration', t.durationMillis ? formatClock(t.durationMillis / 1000) : ''],
        ['info.format', (t.format || '').toUpperCase()],
        ['info.playback', t.playability === 'transcoded' ? this.$t('info.transcoded', { format: (t.streamFormat || 'mp3').toUpperCase() })
          : (t.playability ? this.$t('info.native') : '')],
        ['info.bitrate', t.bitrateKbps ? t.bitrateKbps + ' kbps' : ''],
        ['info.sampleRate', t.sampleRate ? t.sampleRate + ' Hz' : ''],
        ['info.channels', t.channels || ''],
        ['info.size', formatSize(t.size)],
        ['info.added', t.addedAt ? formatTime(new Date(t.addedAt).toISOString()) : ''],
        ['info.source', t.segment ? t.segment.source : ''],
        ['info.range', formatSegmentRange(t.segment)],
        ['info.path', t.path]
      ];
      return rows.filter(r => r[1] !== '' && r[1] !== null && r[1] !== undefined);
    }
  },
  template: `
    <dl class="kv info-list">
      <template v-for="r in rows" :key="r[0]"><dt>{{ $t(r[0]) }}</dt><dd>{{ r[1] }}</dd></template>
    </dl>`
};

export const InfoDialog = {
  name: 'InfoDialog',
  components: { Icon, TrackInfo },
  setup() { return { ui: ui }; },
  mounted() {
    this.onKey = e => { if (this.ui.info && e.key === 'Escape') { this.ui.info = null; e.stopPropagation(); } };
    document.addEventListener('keydown', this.onKey, true);
  },
  beforeUnmount() { document.removeEventListener('keydown', this.onKey, true); },
  template: `
    <div v-if="ui.info" class="modal-backdrop" @click.self="ui.info = null">
      <section class="modal" role="dialog" aria-modal="true" :aria-label="$t('action.info')">
        <header class="modal-head"><h2>{{ ui.info.title || ui.info.name }}</h2>
          <button type="button" class="icon-btn" :aria-label="$t('common.close')" @click="ui.info = null"><icon name="close"></icon></button></header>
        <track-info :track="ui.info"></track-info>
      </section>
    </div>`
};

/** The lyrics of any track (the song menu's "Lyrics"); the lines follow the playback only for the playing track. */
export const LyricsDialog = {
  name: 'LyricsDialog',
  components: { Icon, LyricsView },
  setup() { return { ui: ui, player: player }; },
  computed: {
    follow() { const c = this.player.current; return !!(c && this.ui.lyrics && c.track.path === this.ui.lyrics.path); }
  },
  mounted() {
    this.onKey = e => { if (this.ui.lyrics && e.key === 'Escape') { this.ui.lyrics = null; e.stopPropagation(); } };
    document.addEventListener('keydown', this.onKey, true);
  },
  beforeUnmount() { document.removeEventListener('keydown', this.onKey, true); },
  template: `
    <div v-if="ui.lyrics" class="modal-backdrop" @click.self="ui.lyrics = null">
      <section class="modal" role="dialog" aria-modal="true" :aria-label="$t('lyrics.title')">
        <header class="modal-head"><h2>{{ ui.lyrics.title || ui.lyrics.name }}</h2>
          <button type="button" class="icon-btn" :aria-label="$t('common.close')" @click="ui.lyrics = null"><icon name="close"></icon></button></header>
        <lyrics-view :track="ui.lyrics" :follow="follow"></lyrics-view>
      </section>
    </div>`
};

export const ShortcutsDialog = {
  name: 'ShortcutsDialog',
  components: { Icon },
  setup() { return { ui: ui, shortcuts: SHORTCUTS }; },
  template: `
    <div v-if="ui.shortcutsOpen" class="modal-backdrop" @click.self="ui.shortcutsOpen = false">
      <section class="modal" role="dialog" aria-modal="true" :aria-label="$t('shortcuts.title')">
        <header class="modal-head"><h2>{{ $t('shortcuts.title') }}</h2>
          <button type="button" class="icon-btn" :aria-label="$t('common.close')" @click="ui.shortcutsOpen = false"><icon name="close"></icon></button></header>
        <dl class="kv"><template v-for="s in shortcuts" :key="s.action"><dt><kbd>{{ s.keys }}</kbd></dt><dd>{{ $t('shortcuts.' + s.action) }}</dd></template></dl>
      </section>
    </div>`
};

export const UserMenu = {
  name: 'UserMenu',
  components: { Icon },
  setup() { return { session: session, ui: ui }; },
  data() { return { open: false }; },
  computed: {
    initial() { return (this.session.displayName || this.session.username || '?').slice(0, 1).toUpperCase(); }
  },
  mounted() {
    this.onDoc = e => { if (this.open && !this.$el.contains(e.target)) this.open = false; };
    document.addEventListener('mousedown', this.onDoc);
  },
  beforeUnmount() { document.removeEventListener('mousedown', this.onDoc); },
  methods: {
    go(path) { this.open = false; this.$router.push(path); },
    shortcuts() { this.open = false; this.ui.shortcutsOpen = true; },
    async logout() {
      this.open = false;
      await beforeLogout();
      player.clear();
      try { await Api.logout(); } catch (e) { /* the session is gone either way */ }
      this.$router.push('/login');
    }
  },
  template: `
    <div class="usermenu">
      <button type="button" class="avatar" :aria-expanded="open" aria-haspopup="menu" :title="session.displayName"
              :aria-label="$t('user.menu')" @click="open = !open">{{ initial }}</button>
      <div v-if="open" class="menu dropdown" role="menu">
        <div class="menu-title">{{ session.displayName }}<small class="muted"> ({{ $t('role.' + session.role) }})</small></div>
        <button type="button" role="menuitem" @click="go('/password')">{{ $t('user.changePassword') }}</button>
        <button type="button" role="menuitem" @click="shortcuts()">{{ $t('shortcuts.title') }}</button>
        <button type="button" role="menuitem" @click="logout()">{{ $t('user.logout') }}</button>
      </div>
    </div>`
};
