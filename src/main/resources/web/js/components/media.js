/* Cover art, transport controls and seek bar. */
import { Icon } from './icons.js';
import { artUrl } from '../core/urls.js';
import { formatClock } from '../core/format.js';
import { player } from '../player/index.js';

export const Cover = {
  name: 'Cover',
  components: { Icon },
  props: { art: { type: String, default: null }, size: { type: String, default: 'small' }, round: Boolean, icon: { type: String, default: 'note' } },
  data() { return { failed: false }; },
  computed: { src() { return artUrl(this.art, this.size); } },
  watch: { art() { this.failed = false; } },
  template: `
    <span class="art" :class="{ round: round }">
      <img v-if="src && !failed" :src="src" alt="" loading="lazy" decoding="async" @error="failed = true">
      <icon v-else :name="icon"></icon>
    </span>`
};

export const PlayerControls = {
  name: 'PlayerControls',
  components: { Icon },
  setup() { return { player: player }; },
  computed: {
    q() { return this.player.state.queue; },
    busy() { return this.player.state.status === 'loading'; },
    playing() { return this.player.state.status === 'playing' || this.busy; },
    empty() { return this.q.items.length === 0; },
    repeatIcon() { return this.q.repeat === 'one' ? 'repeatOne' : 'repeat'; },
    repeatLabel() { return this.$t('player.repeat.' + this.q.repeat); }
  },
  template: `
    <div class="controls">
      <button type="button" class="icon-btn" :class="{ on: q.shuffle }" :aria-pressed="q.shuffle" :title="$t('player.shuffle')"
              :aria-label="$t('player.shuffle')" @click="player.toggleShuffle()"><icon name="shuffle"></icon></button>
      <button type="button" class="icon-btn" :title="$t('player.previous')" :aria-label="$t('player.previous')"
              :disabled="empty" @click="player.prev()"><icon name="prev"></icon></button>
      <button type="button" class="icon-btn big" :class="{ spin: busy }" :title="playing ? $t('player.pause') : $t('player.play')"
              :aria-label="playing ? $t('player.pause') : $t('player.play')" :disabled="empty" @click="player.toggle()">
        <icon :name="playing ? 'pause' : 'play'"></icon></button>
      <button type="button" class="icon-btn" :title="$t('player.next')" :aria-label="$t('player.next')"
              :disabled="empty" @click="player.next()"><icon name="next"></icon></button>
      <button type="button" class="icon-btn" :class="{ on: q.repeat !== 'off' }" :title="repeatLabel" :aria-label="repeatLabel"
              @click="player.cycleRepeat()"><icon :name="repeatIcon"></icon></button>
    </div>`
};

export const SeekBar = {
  name: 'SeekBar',
  setup() { return { player: player, formatClock }; },
  data() { return { dragging: false, dragValue: 0 }; },
  computed: {
    duration() { return this.player.state.duration || 0; },
    value() { return this.dragging ? this.dragValue : Math.min(this.player.state.position, this.duration || 0); }
  },
  methods: {
    onInput(e) { this.dragging = true; this.dragValue = Number(e.target.value); },
    onChange(e) { this.player.seek(Number(e.target.value)); this.dragging = false; }
  },
  template: `
    <div class="seek">
      <span class="time">{{ formatClock(value) }}</span>
      <input type="range" min="0" :max="duration || 1" step="1" :value="value" :disabled="!duration"
             :aria-label="$t('player.seek')" @input="onInput" @change="onChange">
      <span class="time">{{ formatClock(duration) }}</span>
    </div>`
};
