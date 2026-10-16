/* Playlists: list/create/delete, and the editor (rename, add via search, reorder, remove, fix broken paths). */
import { Api } from '../api.js';
import { moveItem } from '../core/list.js';

export const PlaylistsPage = {
  name: 'PlaylistsPage',
  data() { return { lists: [], name: '', error: '', busy: false, confirmDelete: null }; },
  created() { this.load(); },
  methods: {
    async load() {
      try { this.lists = (await Api.playlists()).playlists; } catch (e) { this.error = e.message; }
    },
    async create() {
      if (!this.name.trim()) return;
      this.busy = true; this.error = '';
      try {
        const p = await Api.createPlaylist(this.name.trim());
        this.name = '';
        this.$router.push('/playlists/' + encodeURIComponent(p.key));
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async remove(key) {
      this.busy = true; this.error = '';
      try { await Api.deletePlaylist(key); this.confirmDelete = null; await this.load(); }
      catch (e) { this.error = e.message; } finally { this.busy = false; }
    }
  },
  template: `
    <section>
      <h1>Playlists</h1>
      <banner :message="error"></banner>
      <form class="row" @submit.prevent="create">
        <input v-model="name" placeholder="New playlist name" aria-label="New playlist name" maxlength="200">
        <button type="submit" class="primary" :disabled="busy || !name.trim()">Create</button>
      </form>
      <p v-if="!lists.length" class="muted">No playlists yet.</p>
      <table v-else>
        <thead><tr><th>Name</th><th>Songs</th><th>Broken</th><th></th></tr></thead>
        <tbody>
          <tr v-for="p in lists" :key="p.key">
            <td><router-link :to="'/playlists/' + encodeURIComponent(p.key)">{{ p.name }}</router-link> <span v-if="p.system" class="badge" title="Read from a .m3u/.m3u8/.pls file in the library">file</span></td>
            <td>{{ p.count }}</td>
            <td><span v-if="p.broken" class="badge bad">{{ p.broken }}</span></td>
            <td class="right">
              <template v-if="confirmDelete === p.key">
                Delete "{{ p.name }}"?
                <button type="button" class="danger" :disabled="busy" @click="remove(p.key)">Delete</button>
                <button type="button" @click="confirmDelete = null">Keep</button>
              </template>
              <button v-else-if="!p.system" type="button" @click="confirmDelete = p.key">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>`
};

export const PlaylistPage = {
  name: 'PlaylistPage',
  data() {
    return { pl: null, entries: [], original: '', name: '', error: '', info: '', busy: false, fixing: null };
  },
  computed: {
    key() { return this.$route.params.key; },
    paths() { return this.entries.map(e => e.path); },
    dirty() { return this.original !== JSON.stringify(this.paths); },
    broken() { return this.entries.filter(e => e.status !== 'ok').length; }
  },
  created() { this.load(); },
  methods: {
    async load() {
      this.error = ''; this.fixing = null;
      try {
        this.pl = await Api.playlist(this.key);
        this.entries = this.pl.entries.map(e => ({ path: e.path, status: e.status, track: e.track || null }));
        this.original = JSON.stringify(this.paths);
        this.name = this.pl.name;
      } catch (e) { this.error = e.message; }
    },
    move(i, direction) { this.entries = moveItem(this.entries, i, direction); },
    remove(i) { this.entries.splice(i, 1); this.fixing = null; },
    add(hit) {
      if (hit.type !== 'track') return;
      this.entries.push({ path: hit.path, status: 'ok', track: hit.track });
      this.info = '';
    },
    startFix(i) { this.fixing = this.fixing === i ? null : i; },
    fix(hit) {
      if (hit.type !== 'track' || this.fixing === null) return;
      this.entries.splice(this.fixing, 1, { path: hit.path, status: 'ok', track: hit.track });
      this.fixing = null;
    },
    async save() {
      this.busy = true; this.error = ''; this.info = '';
      try {
        await Api.setSongs(this.key, this.paths);
        await this.load();
        this.info = 'Playlist saved.';
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    cancel() {
      this.entries = this.pl.entries.map(e => ({ path: e.path, status: e.status, track: e.track || null }));
      this.fixing = null;
    },
    async rename() {
      this.busy = true; this.error = ''; this.info = '';
      try { await Api.renamePlaylist(this.key, this.name); await this.load(); this.info = 'Renamed.'; }
      catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    describe(e) {
      if (!e.track) return e.path;
      return (e.track.displayTitle || e.track.name) + (e.track.artist ? ' — ' + e.track.artist : '');
    }
  },
  template: `
    <section v-if="pl">
      <nav class="crumbs"><router-link to="/playlists">&larr; All playlists</router-link></nav>
      <h1>{{ pl.name }}</h1>
      <banner :message="error"></banner>
      <banner :message="info" kind="ok"></banner>
      <p v-if="pl.system" class="banner info">This playlist is read from a playlist file in the library (.m3u, .m3u8 or .pls) and is read-only. Only the files that exist in the library are listed.</p>
      <form v-if="!pl.system" class="row" @submit.prevent="rename">
        <input v-model="name" aria-label="Playlist name" maxlength="200">
        <button type="submit" :disabled="busy || !name.trim() || name.trim() === pl.name">Rename</button>
      </form>

      <p v-if="broken" class="banner info">{{ broken }} entries cannot be played (file missing or unsupported). They are skipped
        by the DLNA clients. Use "Fix" to point them to the new file.</p>
      <p v-if="!entries.length" class="muted">The playlist is empty. Add songs below.</p>
      <ol class="order songs" :class="{ readonly: pl.system }">
        <li v-for="(e, i) in entries" :key="i + e.path" :class="{ missing: e.status !== 'ok' }">
          <div class="row">
            <move-buttons v-if="!pl.system" :index="i" :count="entries.length" @move="d => move(i, d)"></move-buttons>
            <span class="grow">{{ describe(e) }}<div class="muted small">{{ e.path }}</div></span>
            <span v-if="e.status !== 'ok'" class="badge bad">{{ e.status }}</span>
            <button v-if="e.status !== 'ok' && !pl.system" type="button" @click="startFix(i)">{{ fixing === i ? 'Cancel fix' : 'Fix' }}</button>
            <button v-if="!pl.system" type="button" class="icon" title="Remove from the playlist" @click="remove(i)">&times;</button>
          </div>
          <div v-if="fixing === i" class="fixbox">
            <p>Find the new file for <code>{{ e.path }}</code>:</p>
            <search-picker kind="track" action-label="Use this file" @pick="fix"></search-picker>
          </div>
        </li>
      </ol>
      <div v-if="!pl.system" class="row">
        <button type="button" class="primary" :disabled="busy || !dirty" @click="save">Save</button>
        <button type="button" :disabled="busy || !dirty" @click="cancel">Cancel</button>
        <span v-if="dirty" class="muted">Unsaved changes</span>
      </div>

      <template v-if="!pl.system">
        <h2>Add songs</h2>
        <search-picker kind="track" action-label="Add" @pick="add"></search-picker>
      </template>
    </section>
    <banner v-else :message="error"></banner>`
};
