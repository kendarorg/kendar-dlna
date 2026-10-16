/* Bottom player bar, Now playing panel and full-screen player. */
import { Icon } from './icons.js';
import { Cover, PlayerControls, SeekBar } from './media.js';
import { TrackInfo } from './menus.js';
import { FavButton } from './FavButton.js';
import { player } from '../player/index.js';
import { registry } from '../extensions.js';
import { ui, toast } from '../state.js';
import { openMenu, trackSubject } from '../actions.js';
import { formatDuration, formatTotal } from '../core/format.js';
import { totalDurationMs, snapshot } from '../core/queue.js';
import { folderRoute } from '../core/urls.js';
import { parentPath } from '../core/list.js';
import { formatRate, rateForStep, SPEED_MAX, SPEED_MIN } from '../core/speed.js';
import { LyricsView } from './LyricsView.js';

export const SpeedControl = {
  name: 'SpeedControl',
  setup() { return { player: player, min: SPEED_MIN, max: SPEED_MAX }; },
  computed: {
    step() { return this.player.state.speed; },
    label() { return formatRate(rateForStep(this.step)); }
  },
  methods: {
    set(e) { this.player.setSpeed(Number(e.target.value)); },
    reset() { this.player.setSpeed(0); }
  },
  template: `
    <span class="speed">
      <input type="range" class="speed-range" :min="min" :max="max" step="1" :value="step" :aria-label="$t('player.speed')" :title="$t('player.speed')" @input="set">
      <button type="button" class="speed-label" :class="{ on: step !== 0 }" :title="$t('player.speedReset')" :aria-label="$t('player.speedReset')" @click="reset">{{ label }}</button>
    </span>`
};

export const PlayerBar = {
  name: 'PlayerBar',
  components: { Icon, Cover, PlayerControls, SeekBar, FavButton, SpeedControl },
  setup() { return { player: player, ui: ui }; },
  computed: {
    entry() { return this.player.current; },
    track() { return this.entry ? this.entry.track : null; },
    st() { return this.player.state; },
    volumeIcon() { return this.st.muted || this.st.volume === 0 ? 'mute' : 'volume'; },
    failed() { return this.st.error ? this.$t('player.error') : ''; }
  },
  methods: {
    setVolume(e) { this.player.setVolume(Number(e.target.value)); },
    menu(e) { if (this.track) openMenu(e, trackSubject(this.track)); }
  },
  template: `
    <footer class="playerbar" :class="{ empty: !track }" :aria-label="$t('player.label')">
      <div class="pb-track">
        <button type="button" class="pb-open" :disabled="!track" :aria-label="$t('player.fullscreen')" @click="ui.fullscreen = true">
          <cover :art="track && track.art" size="small"></cover>
        </button>
        <div class="pb-text" v-if="track">
          <div class="t">{{ track.title || track.name }}</div>
          <div class="s" :class="{ bad: failed }">{{ failed || track.artist }}</div>
        </div>
        <div class="pb-text" v-else><div class="s">{{ $t('player.nothing') }}</div></div>
        <fav-button v-if="track" :track="track"></fav-button>
        <button v-if="track" type="button" class="icon-btn pb-more" :aria-label="$t('action.more')" aria-haspopup="menu" @click.stop="menu($event)"><icon name="more"></icon></button>
      </div>
      <div class="pb-center">
        <player-controls></player-controls>
        <seek-bar></seek-bar>
      </div>
      <div class="pb-right">
        <speed-control></speed-control>
        <button type="button" class="icon-btn pb-mute" :aria-label="$t('player.mute')" :title="$t('player.mute')" @click="player.toggleMute()"><icon :name="volumeIcon"></icon></button>
        <input type="range" class="volume" min="0" max="1" step="0.02" :value="st.muted ? 0 : st.volume" :aria-label="$t('player.volume')" @input="setVolume">
        <button type="button" class="icon-btn" :class="{ on: ui.queueOpen }" :aria-pressed="ui.queueOpen" :aria-label="$t('queue.title')" :title="$t('queue.title')"
                @click="ui.queueOpen = !ui.queueOpen"><icon name="queue"></icon></button>
        <button type="button" class="icon-btn" :disabled="!track" :aria-label="$t('player.fullscreen')" :title="$t('player.fullscreen')"
                @click="ui.fullscreen = true"><icon name="fullscreen"></icon></button>
      </div>
    </footer>`
};

export const QueuePanel = {
  name: 'QueuePanel',
  components: { Icon, Cover, FavButton },
  setup() { return { player: player, ui: ui, registry: registry, formatDuration, formatTotal }; },
  computed: {
    q() { return this.player.state.queue; },
    total() { return formatTotal(totalDurationMs(this.q)); },
    actions() { return this.registry.queueActions; }
  },
  watch: {
    'ui.queueOpen'(open) { if (open) this.$nextTick(() => this.scrollToCurrent()); }
  },
  methods: {
    scrollToCurrent() {
      const el = this.$el && this.$el.querySelector ? this.$el.querySelector('.q-row.current') : null;
      if (el && el.scrollIntoView) el.scrollIntoView({ block: 'center' });
    },
    menu(event, entry) { openMenu(event, trackSubject(entry.track)); },
    runAction(a) { Promise.resolve(a.run(snapshot(this.q), this.q.items.map(e => e.track))).catch(e => toast(e.message)); }
  },
  template: `
    <aside v-if="ui.queueOpen" class="queue" role="complementary" :aria-label="$t('queue.title')">
      <header class="queue-head">
        <div class="grow"><h2>{{ $t('queue.title') }}</h2>
          <small class="muted">{{ $tn('count.tracks', q.items.length) }}<span v-if="total"> · {{ total }}</span></small></div>
        <button type="button" class="icon-btn" :aria-label="$t('common.close')" @click="ui.queueOpen = false"><icon name="close"></icon></button>
      </header>
      <div class="queue-actions">
        <button type="button" :disabled="!q.items.length" @click="player.clear()"><icon name="trash"></icon> {{ $t('queue.clear') }}</button>
        <button v-for="a in actions" :key="a.id" type="button" :disabled="!q.items.length" @click="runAction(a)">{{ $t(a.label) }}</button>
      </div>
      <p v-if="!q.items.length" class="muted pad">{{ $t('queue.empty') }}</p>
      <ol class="queue-list">
        <li v-for="(e, i) in q.items" :key="e.id" class="q-row" :class="{ current: i === q.index }" @contextmenu.prevent="menu($event, e)">
          <button type="button" class="q-main" @click="player.jump(i)">
            <cover :art="e.track.art" size="small"></cover>
            <span class="q-text"><span class="t">{{ e.track.title || e.track.name }}</span><span class="s">{{ e.track.artist }}</span></span>
            <span class="q-dur">{{ formatDuration(e.track.durationMillis) }}</span>
          </button>
          <span class="q-tools">
            <fav-button :track="e.track"></fav-button>
            <button type="button" class="icon-btn" :disabled="i === 0" :title="$t('queue.top')" :aria-label="$t('queue.top')" @click="player.move(i, 'top')"><icon name="top"></icon></button>
            <button type="button" class="icon-btn" :disabled="i === 0" :title="$t('queue.up')" :aria-label="$t('queue.up')" @click="player.move(i, 'up')"><icon name="up"></icon></button>
            <button type="button" class="icon-btn" :disabled="i === q.items.length - 1" :title="$t('queue.down')" :aria-label="$t('queue.down')" @click="player.move(i, 'down')"><icon name="down"></icon></button>
            <button type="button" class="icon-btn" :disabled="i === q.items.length - 1" :title="$t('queue.bottom')" :aria-label="$t('queue.bottom')" @click="player.move(i, 'bottom')"><icon name="bottom"></icon></button>
            <button type="button" class="icon-btn" :title="$t('queue.remove')" :aria-label="$t('queue.remove')" @click="player.removeAt(i)"><icon name="close"></icon></button>
          </span>
        </li>
      </ol>
    </aside>`
};

export const FullPlayer = {
  name: 'FullPlayer',
  components: { Icon, Cover, PlayerControls, SeekBar, TrackInfo, FavButton, SpeedControl, LyricsView },
  setup() { return { player: player, ui: ui }; },
  data() { return { tab: 'playing' }; },
  computed: {
    entry() { return this.player.current; },
    track() { return this.entry ? this.entry.track : null; },
    upcoming() {
      const q = this.player.state.queue;
      return q.items.slice(q.index + 1, q.index + 4);
    }
  },
  watch: {
    track(t) { if (!t) this.ui.fullscreen = false; },
    'ui.fullscreen'(open) { if (open) { this.tab = 'playing'; this.$nextTick(() => this.$refs.close && this.$refs.close.focus()); } }
  },
  mounted() {
    this.onKey = e => { if (this.ui.fullscreen && e.key === 'Escape') { this.ui.fullscreen = false; e.stopPropagation(); } };
    document.addEventListener('keydown', this.onKey, true);
  },
  beforeUnmount() { document.removeEventListener('keydown', this.onKey, true); },
  methods: {
    toFolder() {
      this.ui.fullscreen = false;
      this.$router.push(folderRoute(parentPath(this.track.path)));
    },
    menu(e) { openMenu(e, trackSubject(this.track)); }
  },
  template: `
    <div v-if="ui.fullscreen && track" class="fullplayer" role="dialog" aria-modal="true" :aria-label="$t('player.fullscreen')">
      <header class="fp-head">
        <button ref="close" type="button" class="icon-btn" :aria-label="$t('common.close')" @click="ui.fullscreen = false"><icon name="expand"></icon></button>
        <div class="fp-tabs" role="tablist">
          <button type="button" role="tab" :aria-selected="tab === 'playing'" :class="{ on: tab === 'playing' }" @click="tab = 'playing'">{{ $t('player.nowPlaying') }}</button>
          <button type="button" role="tab" :aria-selected="tab === 'info'" :class="{ on: tab === 'info' }" @click="tab = 'info'">{{ $t('action.info') }}</button>
          <button type="button" role="tab" :aria-selected="tab === 'lyrics'" :class="{ on: tab === 'lyrics' }" @click="tab = 'lyrics'">{{ $t('lyrics.title') }}</button>
        </div>
        <fav-button :track="track"></fav-button>
        <button type="button" class="icon-btn" :aria-label="$t('queue.title')" @click="ui.queueOpen = !ui.queueOpen; ui.fullscreen = false"><icon name="queue"></icon></button>
        <button type="button" class="icon-btn" :aria-label="$t('action.more')" aria-haspopup="menu" @click.stop="menu($event)"><icon name="more"></icon></button>
      </header>
      <div v-if="tab === 'playing'" class="fp-body">
        <cover class="fp-art" :art="track.art" size="large"></cover>
        <h1 class="fp-title">{{ track.title || track.name }}</h1>
        <p class="fp-sub">{{ track.artist }}<span v-if="track.album"> · {{ track.album }}</span></p>
        <seek-bar></seek-bar>
        <player-controls class="fp-controls"></player-controls>
        <speed-control class="fp-speed"></speed-control>
        <div v-if="upcoming.length" class="fp-next">
          <h2>{{ $t('player.upNext') }}</h2>
          <ol><li v-for="e in upcoming" :key="e.id">{{ e.track.title || e.track.name }}<span class="muted"> – {{ e.track.artist }}</span></li></ol>
        </div>
      </div>
      <div v-else-if="tab === 'lyrics'" class="fp-body fp-lyrics">
        <lyrics-view :track="track"></lyrics-view>
      </div>
      <div v-else class="fp-body fp-info">
        <track-info :track="track"></track-info>
        <button type="button" @click="toFolder">{{ $t('action.goFolder') }}</button>
      </div>
    </div>`
};
