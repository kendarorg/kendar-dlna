/* "Add to playlist" dialog: pick one of the user's playlists or create a new one. Also used for "save queue as playlist". */
import { Icon } from './icons.js';
import { Api } from '../api.js';
import { ui, toast } from '../state.js';
import { validName } from '../core/playlist-ops.js';

export const AddToPlaylistDialog = {
  name: 'AddToPlaylistDialog',
  components: { Icon },
  setup() { return { ui: ui }; },
  data() { return { lists: [], loading: false, newName: '', busy: false, error: '' }; },
  computed: {
    request() { return this.ui.addTo; },
    canCreate() { return validName(this.newName) && !this.busy; }
  },
  watch: {
    request(r) {
      if (!r) return;
      this.newName = r.suggest || ''; this.error = ''; this.busy = false;
      this.load();
      this.$nextTick(() => { if (this.$refs.name) this.$refs.name.focus(); });
    }
  },
  mounted() {
    this.onKey = e => { if (this.ui.addTo && e.key === 'Escape') { this.close(); e.stopPropagation(); } };
    document.addEventListener('keydown', this.onKey, true);
  },
  beforeUnmount() { document.removeEventListener('keydown', this.onKey, true); },
  methods: {
    close() { this.ui.addTo = null; },
    async load() {
      this.loading = true;
      try { this.lists = (await Api.myPlaylists()).playlists; } catch (e) { this.error = e.message; } finally { this.loading = false; }
    },
    async paths() {
      const tracks = await this.request.tracks();
      if (!tracks.length) throw new Error(this.$t('toast.nothing'));
      return tracks.map(t => t.path);
    },
    async addTo(list) {
      this.busy = true; this.error = '';
      try {
        const paths = await this.paths();
        await Api.addToMyPlaylist(list.id, paths);
        toast(this.$tn('toast.addedToPlaylist', paths.length, { name: list.name }));
        this.close();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async create() {
      if (!this.canCreate) return;
      this.busy = true; this.error = '';
      try {
        const paths = await this.paths();
        const created = await Api.createMyPlaylist(this.newName.trim(), paths);
        toast(this.$t('toast.playlistCreated', { name: created.name }));
        this.close();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    }
  },
  template: `
    <div v-if="request" class="modal-backdrop" @click.self="close">
      <section class="modal" role="dialog" aria-modal="true" :aria-label="$t('playlist.addTo')">
        <header class="modal-head"><h2>{{ $t('playlist.addTo') }}</h2>
          <button type="button" class="icon-btn" :aria-label="$t('common.close')" @click="close"><icon name="close"></icon></button></header>
        <p class="muted">{{ request.title }}</p>
        <p v-if="error" class="banner error">{{ error }}</p>
        <form class="row" @submit.prevent="create">
          <input ref="name" v-model="newName" maxlength="200" :placeholder="$t('playlist.newName')" :aria-label="$t('playlist.newName')">
          <button type="submit" class="primary" :disabled="!canCreate"><icon name="add"></icon> {{ $t('playlist.createAndAdd') }}</button>
        </form>
        <h3>{{ $t('playlist.existing') }}</h3>
        <p v-if="loading" class="muted">{{ $t('list.loading') }}</p>
        <p v-else-if="!lists.length" class="muted">{{ $t('playlist.noneYet') }}</p>
        <ul class="plain pick-list">
          <li v-for="l in lists" :key="l.id">
            <button type="button" class="pick" :disabled="busy" @click="addTo(l)">
              <icon name="playlist"></icon><span class="grow">{{ l.name }}</span>
              <small class="muted">{{ $tn('count.tracks', l.count) }}</small></button>
          </li>
        </ul>
      </section>
    </div>`
};
