/* Directory settings: sort, explicit order, album fields, Random N, views, sync with tags, art. */
import { Api } from '../api.js';
import { moveItem } from '../core/list.js';

const ALL_VIEWS = ['album', 'artist', 'genre', 'title', 'composer'];
const VIEW_LABELS = { album: 'Albums', artist: 'Artists', genre: 'Genres', title: 'Titles', composer: 'Composers' };
const SORT_MODES = [
  { value: 'filename', label: 'File name' },
  { value: 'title', label: 'Title' },
  { value: 'track', label: 'Disc and track number' },
  { value: 'mtime', label: 'Modification time' },
  { value: 'explicit', label: 'Explicit order (below)' }
];

/** Builds the editable settings form from a folder detail. */
function settingsForm(folder) {
  const s = folder.settings;
  const kids = folder.children.map(c => ({ name: c.name, isDir: c.type === 'folder', missing: false,
    shown: c.type === 'folder' && c.displayName !== c.name ? c.displayName : '' }));
  const byName = {};
  kids.forEach(k => { byName[k.name] = k; });
  let order;
  if (s.order.length) {
    order = s.order.map(n => byName[n] ? { name: n, isDir: byName[n].isDir, missing: false, shown: byName[n].shown }
      : { name: n, isDir: false, missing: true });
    const listed = new Set(s.order);
    kids.filter(k => !listed.has(k.name)).forEach(k => order.push({ name: k.name, isDir: k.isDir, missing: false, shown: k.shown }));
  } else {
    order = kids;
  }
  return {
    displayName: s.displayName || '',
    sort: s.sort,
    order: order,
    album: {
      title: s.album.title || '', albumArtist: s.album.albumArtist || '', year: s.album.year === null ? '' : s.album.year,
      genre: s.album.genre || '', discNumber: s.album.discNumber === null ? '' : s.album.discNumber,
      discTotal: s.album.discTotal === null ? '' : s.album.discTotal
    },
    random: s.random,
    recent: s.recent || 0,
    keepWholeFiles: !!s.keepWholeFiles,
    hidden: !!s.hidden,
    bucketThreshold: s.bucketThreshold == null ? '' : s.bucketThreshold,
    views: s.views.slice(),
    storedOrder: s.order.slice()
  };
}

function numberOrNull(v) {
  if (v === '' || v === null || v === undefined) return null;
  const n = Number(v);
  return Number.isInteger(n) ? n : NaN;
}

/** The settings.json body for a form. */
function settingsBody(form) {
  const a = form.album;
  const album = {
    title: a.title.trim() || null, albumArtist: a.albumArtist.trim() || null, year: numberOrNull(a.year),
    genre: a.genre.trim() || null, discNumber: numberOrNull(a.discNumber), discTotal: numberOrNull(a.discTotal)
  };
  return {
    displayName: form.displayName.trim() || null,
    sort: form.sort,
    order: form.sort === 'explicit' ? form.order.map(o => o.name) : form.storedOrder,
    album: album,
    random: numberOrNull(form.random) || 0,
    recent: numberOrNull(form.recent) || 0,
    keepWholeFiles: !!form.keepWholeFiles,
    hidden: !!form.hidden,
    bucketThreshold: numberOrNull(form.bucketThreshold),
    views: form.views
  };
}

export const SettingsPage = {
  name: 'SettingsPage',
  data() {
    return {
      folder: null, form: null, original: '', error: '', info: '', busy: false,
      conflicts: null, confirmApply: false, applySelected: [], applyResult: null,
      sortModes: SORT_MODES, allViews: ALL_VIEWS, labels: VIEW_LABELS, artVersion: Date.now()
    };
  },
  computed: {
    path() { return this.$route.query.p; },
    dirty() { return !!this.form && JSON.stringify(this.form) !== this.original; },
    /** The tracks whose format can hold album tags (not MIDI, Opus, raw AAC): the files "Apply to tags" can rewrite. */
    applyCandidates() { return this.folder ? this.folder.children.filter(c => c.type === 'track' && c.track.editableFields.includes('album')) : []; },
    applyReadOnly() { return this.folder ? this.folder.children.filter(c => c.type === 'track' && !c.track.editableFields.includes('album')) : []; },
    disabledViews() { return ALL_VIEWS.filter(v => !this.form.views.includes(v)); }
  },
  created() { this.load(); },
  methods: {
    async load() {
      this.error = ''; this.conflicts = null; this.applyResult = null; this.confirmApply = false; this.applySelected = [];
      try {
        this.folder = await Api.folder(this.path);
        this.form = settingsForm(this.folder);
        this.original = JSON.stringify(this.form);
        this.artVersion = Date.now();
      } catch (e) { this.error = e.message; }
    },
    cancel() {
      this.form = settingsForm(this.folder);
      this.original = JSON.stringify(this.form);
      this.info = 'Changes discarded.';
    },
    async save() {
      this.busy = true; this.error = ''; this.info = '';
      try {
        const body = settingsBody(this.form);
        if (Number.isNaN(body.album.year) || Number.isNaN(body.album.discNumber) || Number.isNaN(body.album.discTotal)
          || Number.isNaN(Number(this.form.random)) || Number.isNaN(Number(this.form.recent))
          || Number.isNaN(body.bucketThreshold) || (body.bucketThreshold !== null && body.bucketThreshold < 1)) {
          throw new Error('Year, disc number, disc total, Random N, Recently added and the letter grouping limit must be whole numbers (the limit at least 1).');
        }
        await Api.saveSettings(this.path, body);
        await this.load();
        this.info = 'Settings saved.';
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    move(i, direction) { this.form.order = moveItem(this.form.order, i, direction); },
    removeMissing(i) { this.form.order.splice(i, 1); },
    viewEnabled(v) { return this.form.views.includes(v); },
    toggleView(v) {
      const i = this.form.views.indexOf(v);
      if (i >= 0) this.form.views.splice(i, 1); else this.form.views.push(v);
    },
    moveView(i, direction) { this.form.views = moveItem(this.form.views, i, direction); },
    /** Opens the file list; nothing is pre-selected, every file to be rewritten must be ticked. */
    openApply() { this.confirmApply = true; this.applySelected = []; this.applyResult = null; },
    closeApply() { this.confirmApply = false; this.applySelected = []; },
    applySelectAll() { this.applySelected = this.applyCandidates.map(c => c.name); },
    async toTags() {
      if (!this.applySelected.length) return;
      this.busy = true; this.error = ''; this.info = ''; this.conflicts = null; this.applyResult = null;
      try {
        this.applyResult = await Api.syncToTags(this.path, this.applySelected.slice());
        this.closeApply();
        await this.load2();
      } catch (e) { this.error = e.message; this.closeApply(); } finally { this.busy = false; }
    },
    async load2() {
      const keep = this.applyResult;
      await this.load();
      this.applyResult = keep;
    },
    async fromTags() {
      this.busy = true; this.error = ''; this.info = ''; this.conflicts = null; this.applyResult = null;
      try {
        const r = await Api.syncFromTags(this.path, true);   // preview: nothing is written yet
        this.form.album = settingsForm({ settings: r.settings, children: this.folder.children }).album;
        this.info = this.dirty ? 'Album fields filled from the tags. Review them and press Save (or Cancel).'
          : 'The album fields already match the tags.';
      } catch (e) {
        if (e.code === 'sync_conflict') { this.conflicts = e.details.conflicts; this.error = e.message; }
        else this.error = e.message;
      } finally { this.busy = false; }
    },
    async upload(kind, event) {
      const file = event.target.files[0];
      if (!file) return;
      this.busy = true; this.error = ''; this.info = '';
      try {
        await Api.uploadArt(this.path, kind, file);
        await this.load();
        this.info = (kind === 'front' ? 'Front' : 'Rear') + ' picture saved.';
      } catch (e) { this.error = e.message; } finally { this.busy = false; event.target.value = ''; }
    },
    artUrl(kind) { return Api.artUrl(this.path, kind, this.artVersion); },
    failedResults() { return this.applyResult ? this.applyResult.results.filter(r => !r.ok) : []; }
  },
  template: `
    <section v-if="form" class="settings">
      <nav class="crumbs"><router-link :to="{ path: '/library', query: { p: path } }">&larr; {{ folder.displayName }}</router-link></nav>
      <h1>Directory settings <small class="muted">{{ path }}</small>
        <span v-if="folder.outOfSync" class="badge warn">out of sync with tags</span></h1>
      <p><router-link class="button" :to="{ path: '/list-json', query: { p: path } }">{{ $t('listjson.open') }}</router-link></p>
      <p v-if="folder.settingsError" class="banner error">The current settings.json is invalid ({{ folder.settingsError }}).
        Saving replaces it.</p>
      <banner :message="error"></banner>
      <banner :message="info" kind="ok"></banner>

      <form @submit.prevent="save" class="stack">
        <fieldset>
          <legend>Name</legend>
          <div class="grid">
            <label>Display name <input v-model="form.displayName" maxlength="255" :placeholder="folder.name"></label>
            <label>Directory on disk <input :value="folder.name" readonly></label>
          </div>
          <p class="muted">Shown instead of the directory name; the directory on disk is not renamed.</p>
        </fieldset>

        <fieldset>
          <legend>Order</legend>
          <label>Sort children by
            <select v-model="form.sort"><option v-for="m in sortModes" :key="m.value" :value="m.value">{{ m.label }}</option></select>
          </label>
          <div v-if="form.sort === 'explicit'">
            <p class="muted">Order of the direct children (sub-directories and files). Unlisted children are appended.</p>
            <ol class="order">
              <li v-for="(o, i) in form.order" :key="o.name" class="row" :class="{ missing: o.missing }">
                <move-buttons :index="i" :count="form.order.length" @move="d => move(i, d)"></move-buttons>
                <span class="grow">{{ o.name }} <span v-if="o.shown" class="muted">({{ o.shown }})</span></span>
                <span v-if="o.isDir" class="badge">directory</span>
                <span v-if="o.missing" class="badge bad">not found</span>
                <button v-if="o.missing" type="button" class="icon" title="Remove from the list" @click="removeMissing(i)">&times;</button>
              </li>
            </ol>
          </div>
        </fieldset>

        <fieldset>
          <legend>Album</legend>
          <p class="muted">These values override the tags in all output. Use "Apply to tags" to write them into the files.</p>
          <div class="grid">
            <label>Title <input v-model="form.album.title"></label>
            <label>Album artist <input v-model="form.album.albumArtist"></label>
            <label>Year <input type="number" min="0" max="9999" v-model="form.album.year"></label>
            <label>Genre <input v-model="form.album.genre"></label>
            <label>Disc number <input type="number" min="0" max="999" v-model="form.album.discNumber"></label>
            <label>Disc total <input type="number" min="0" max="999" v-model="form.album.discTotal"></label>
          </div>
        </fieldset>

        <fieldset>
          <legend>{{ $t('settings.visibility') }}</legend>
          <label class="inline"><input type="checkbox" v-model="form.hidden" id="hidden-directory"> {{ $t('settings.hidden') }}</label>
          <p class="muted">{{ $t('settings.hidden.help') }}</p>
        </fieldset>

        <fieldset>
          <legend>{{ $t('settings.split') }}</legend>
          <label class="inline"><input type="checkbox" v-model="form.keepWholeFiles" id="keep-whole-files"> {{ $t('settings.keepWholeFiles') }}</label>
          <p class="muted">{{ $t('settings.keepWholeFiles.help') }}</p>
        </fieldset>

        <fieldset>
          <legend>Virtual containers</legend>
          <label>Random N (0 = off)
            <input type="number" min="0" max="100000" v-model="form.random" class="short">
          </label>
          <label>Recently added N (0 = off)
            <input type="number" min="0" max="100000" v-model="form.recent" class="short">
          </label>
          <p class="muted">Recently added lists the N tracks first seen most recently in this directory's subtree, newest first.</p>
          <p class="muted">Views over this directory's subtree, in the order shown:</p>
          <ul class="plain">
            <li v-for="(v, i) in form.views" :key="v" class="row">
              <input type="checkbox" checked :id="'v-' + v" @change="toggleView(v)">
              <label :for="'v-' + v" class="grow">{{ labels[v] }}</label>
              <move-buttons :index="i" :count="form.views.length" @move="d => moveView(i, d)"></move-buttons>
            </li>
            <li v-for="v in disabledViews" :key="v" class="row">
              <input type="checkbox" :id="'v-' + v" @change="toggleView(v)">
              <label :for="'v-' + v" class="grow">{{ labels[v] }}</label>
            </li>
          </ul>
          <label>{{ $t('settings.bucketThreshold') }}
            <input type="number" min="1" v-model="form.bucketThreshold" class="short" id="bucket-threshold" :placeholder="$t('settings.bucketThreshold.default')">
          </label>
          <p class="muted">{{ $t('settings.bucketThreshold.help') }}</p>
        </fieldset>

        <div class="row">
          <button type="submit" class="primary" :disabled="busy || !dirty">Save</button>
          <button type="button" :disabled="busy || !dirty" @click="cancel">Cancel</button>
          <span v-if="dirty" class="muted">Unsaved changes</span>
        </div>
      </form>

      <fieldset>
        <legend>Sync with tags</legend>
        <p class="muted">Works on the saved settings and the tracks of this directory only ({{ folder.trackCount }} tracks).</p>
        <p v-if="dirty" class="banner info">Save or cancel the changes above first.</p>
        <div class="row">
          <button type="button" :disabled="busy || dirty || !folder.trackCount || confirmApply" @click="openApply">Apply to tags</button>
          <button type="button" :disabled="busy || dirty || !folder.trackCount || confirmApply" @click="fromTags">From tags</button>
        </div>
        <div v-if="confirmApply" class="apply-list">
          <p><strong>Choose the files whose album tags will be rewritten.</strong>
            <span class="muted">Nothing is written until you press the button below.</span></p>
          <div class="row">
            <button type="button" @click="applySelectAll">Select all</button>
            <button type="button" @click="applySelected = []">None</button>
            <span class="muted">{{ applySelected.length }} of {{ applyCandidates.length }} selected</span>
          </div>
          <table>
            <thead><tr><th></th><th>File</th><th>Album tag now</th></tr></thead>
            <tbody>
              <tr v-for="c in applyCandidates" :key="c.path">
                <td><input type="checkbox" :value="c.name" v-model="applySelected" :aria-label="'Write ' + c.name"></td>
                <td>{{ c.name }}</td>
                <td>{{ c.track.album }}</td>
              </tr>
              <tr v-for="c in applyReadOnly" :key="c.path" class="disabled">
                <td><input type="checkbox" disabled :aria-label="c.name + ' cannot be written'"></td>
                <td>{{ c.name }} <span class="badge" :title="c.track.editable ? 'This format cannot store album tags (only title, artist and year)' : 'Opus and raw .aac (ADTS) tags cannot be edited'">{{ c.track.editable ? 'no album tag' : 'read-only' }}</span></td>
                <td>{{ c.track.album }}</td>
              </tr>
            </tbody>
          </table>
          <div class="row">
            <button type="button" class="primary" :disabled="busy || !applySelected.length" @click="toTags">
              Write the tags of {{ applySelected.length }} file{{ applySelected.length === 1 ? '' : 's' }}</button>
            <button type="button" :disabled="busy" @click="closeApply">Cancel</button>
          </div>
        </div>
        <div v-if="applyResult">
          <p>Written to {{ applyResult.results.length - failedResults().length }} of {{ applyResult.results.length }} files.
            <span v-if="applyResult.skipped.length">Skipped (these formats cannot store album tags): {{ applyResult.skipped.join(', ') }}.</span></p>
          <ul v-if="failedResults().length" class="plain"><li v-for="r in failedResults()" :key="r.path" class="bad-text">{{ r.path }}: {{ r.error }}</li></ul>
        </div>
        <div v-if="conflicts" class="conflicts">
          <p><strong>The tracks disagree; nothing was written.</strong></p>
          <table>
            <thead><tr><th>Field</th><th>Value</th><th>Files</th></tr></thead>
            <tbody>
              <template v-for="c in conflicts" :key="c.field">
                <tr v-for="(v, i) in c.values" :key="c.field + i">
                  <td>{{ i === 0 ? c.field : '' }}</td><td>{{ v.value }}</td><td>{{ v.files.join(', ') }}</td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </fieldset>

      <fieldset>
        <legend>Pictures</legend>
        <div class="art-row">
          <div>
            <h3>front.jpg</h3>
            <img v-if="folder.hasFront" class="cover" :src="artUrl('front')" alt="Front cover">
            <p v-else class="muted">None</p>
            <input type="file" accept="image/*" :disabled="busy" @change="e => upload('front', e)">
          </div>
          <div>
            <h3>rear.jpg <small class="muted">(web UI only)</small></h3>
            <img v-if="folder.hasRear" class="cover" :src="artUrl('rear')" alt="Rear cover">
            <p v-else class="muted">None</p>
            <input type="file" accept="image/*" :disabled="busy" @change="e => upload('rear', e)">
          </div>
        </div>
      </fieldset>
    </section>
    <banner v-else :message="error"></banner>`
};
