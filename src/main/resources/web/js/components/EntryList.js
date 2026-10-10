/*
 * Rows of playlist/favorites/history entries ({index, path, status: ok|missing|no_access, track?}).
 * Playable rows behave like TrackList rows; missing and no-access rows stay visible for the owner (fix / remove).
 *   editable   reorder buttons and remove (own playlists)
 *   removable  remove only (favorites)
 *   canFix     "fix path" on missing entries
 *   extra      function(entry) -> text for an extra column (played at, play count)
 * Events: play(entryIndex) move({entry, direction}) remove(entry) fix(entry)
 */
import { Icon } from './icons.js';
import { Cover } from './media.js';
import { FavButton } from './FavButton.js';
import { player } from '../player/index.js';
import { openMenu, trackSubject } from '../actions.js';
import { formatDuration } from '../core/format.js';
import { moveStates } from '../core/playlist-ops.js';

export const EntryList = {
  name: 'EntryList',
  components: { Icon, Cover, FavButton },
  props: {
    entries: { type: Array, required: true },
    editable: Boolean,
    removable: Boolean,
    canFix: Boolean,
    extra: { type: Function, default: null },
    numbered: Boolean
  },
  emits: ['play', 'move', 'remove', 'fix'],
  setup() { return { player: player, formatDuration }; },
  computed: {
    playingPath() { const c = this.player.current; return c ? c.track.path : null; },
    isPlaying() { return this.player.state.status === 'playing' || this.player.state.status === 'loading'; }
  },
  methods: {
    ok(e) { return e.status === 'ok' && !!e.track; },
    can(i) { return moveStates(i, this.entries.length); },
    menu(event, e) { if (this.ok(e)) openMenu(event, trackSubject(e.track)); },
    rowPlay(i, e) {
      if (e.track && e.track.path === this.playingPath) this.player.toggle(); else this.$emit('play', i);
    }
  },
  template: `
    <div class="entries" role="list" :class="{ withextra: !!extra }">
      <div v-for="(e, i) in entries" :key="i + '|' + e.path" class="en-row" role="listitem"
           :class="{ current: ok(e) && e.track.path === playingPath, missing: e.status === 'missing', locked: e.status === 'no_access' }"
           @dblclick="ok(e) && $emit('play', i)" @contextmenu.prevent="menu($event, e)">
        <template v-if="ok(e)">
          <button type="button" class="icon-btn c-play" :aria-label="$t('player.play')" @click="rowPlay(i, e)">
            <span v-if="numbered && e.track.path !== playingPath" class="num">{{ i + 1 }}</span>
            <icon v-else :name="e.track.path === playingPath && isPlaying ? 'pause' : 'play'"></icon></button>
          <cover class="c-art" :art="e.track.art" size="small"></cover>
          <div class="c-title"><div class="t">{{ e.track.title || e.track.name }}</div><div class="s">{{ e.track.artist }}</div></div>
          <div class="c-album">{{ e.track.album }}</div>
          <div class="c-dur">{{ formatDuration(e.track.durationMillis) }}</div>
        </template>
        <template v-else>
          <span class="c-play bad-text"><icon name="warning"></icon></span>
          <span class="c-art"></span>
          <div class="c-title"><div class="t">{{ e.status === 'missing' ? $t('entry.missing') : $t('entry.noAccess') }}</div><div class="s path">{{ e.path }}</div></div>
          <div class="c-album"></div>
          <div class="c-dur"></div>
        </template>
        <div v-if="extra" class="c-extra">{{ extra(e) }}</div>
        <span class="en-tools">
          <button v-if="canFix && e.status === 'missing'" type="button" class="fixbtn" @click="$emit('fix', e)"><icon name="edit"></icon> {{ $t('entry.fix') }}</button>
          <template v-if="editable">
            <button type="button" class="icon-btn" :disabled="!can(i).top" :title="$t('queue.top')" :aria-label="$t('queue.top')" @click="$emit('move', { entry: e, direction: 'top' })"><icon name="top"></icon></button>
            <button type="button" class="icon-btn" :disabled="!can(i).up" :title="$t('queue.up')" :aria-label="$t('queue.up')" @click="$emit('move', { entry: e, direction: 'up' })"><icon name="up"></icon></button>
            <button type="button" class="icon-btn" :disabled="!can(i).down" :title="$t('queue.down')" :aria-label="$t('queue.down')" @click="$emit('move', { entry: e, direction: 'down' })"><icon name="down"></icon></button>
            <button type="button" class="icon-btn" :disabled="!can(i).bottom" :title="$t('queue.bottom')" :aria-label="$t('queue.bottom')" @click="$emit('move', { entry: e, direction: 'bottom' })"><icon name="bottom"></icon></button>
          </template>
          <fav-button v-if="ok(e)" :track="e.track"></fav-button>
          <button v-if="editable || removable" type="button" class="icon-btn" :title="$t('entry.remove')" :aria-label="$t('entry.remove')" @click="$emit('remove', e)"><icon name="close"></icon></button>
          <button v-if="ok(e)" type="button" class="icon-btn" :aria-label="$t('action.more')" aria-haspopup="menu" @click.stop="menu($event, e)"><icon name="more"></icon></button>
        </span>
      </div>
    </div>`
};
