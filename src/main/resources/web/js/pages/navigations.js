/*
 * Navigations editor (admin): the tag navigations of config.json - name, optional subitems/ranges overrides and the
 * ordered tag levels - plus the extra tags the scanner may index. Saved navigations apply live (no restart).
 */
import { Api } from '../api.js';
import { moveItem } from '../core/list.js';

export const DEFAULT_NAVIGATIONS = [
  { name: 'Album', index: ['album', 'title'] },
  { name: 'Artists', index: ['artist', 'album', 'title'] },
  { name: 'Genres', index: ['genre', 'artist', 'album', 'title'] },
  { name: 'Songs', index: ['title'] }
];

function toForm(n) {
  return {
    name: n.name || '',
    subitems: n.subitems === null || n.subitems === undefined ? '' : String(n.subitems),
    ranges: n.ranges === null || n.ranges === undefined ? '' : String(n.ranges),
    index: (n.index || []).slice(),
    adding: ''
  };
}

function fromForm(f) {
  const out = { name: f.name.trim(), index: f.index.slice() };
  if (String(f.subitems).trim() !== '') out.subitems = Number(f.subitems);
  if (String(f.ranges).trim() !== '') out.ranges = Number(f.ranges);
  return out;
}

export const NavigationsPage = {
  name: 'NavigationsPage',
  data() {
    return {
      version: 0, navs: [], extraTags: [], tags: [], virtualTags: [], discovered: [], newExtra: '',
      error: '', info: '', busy: false, dirty: false
    };
  },
  computed: {
    allTags() {
      const extra = this.extraTags.filter(t => !this.tags.concat(this.virtualTags).some(x => x.toLowerCase() === t.toLowerCase()));
      return this.tags.concat(this.virtualTags, extra);
    },
    candidates() {
      // discovered tags that are not built in and not yet chosen
      const known = this.tags.concat(this.virtualTags, this.extraTags).map(t => t.toLowerCase());
      return this.discovered.filter(d => !known.includes(d.name.toLowerCase()));
    }
  },
  created() { this.load(); },
  methods: {
    async load() {
      this.error = ''; this.info = '';
      try {
        const r = await Api.navigations();
        this.version = r.version;
        this.navs = r.navigations.map(toForm);
        this.extraTags = r.extraTags.slice();
        this.tags = r.tags; this.virtualTags = r.virtualTags; this.discovered = r.discovered;
        this.dirty = false;
      } catch (e) { this.error = e.message; }
    },
    touch() { this.dirty = true; this.info = ''; },
    addNav() { this.navs.push(toForm({ name: 'New navigation', index: ['title'] })); this.touch(); },
    removeNav(i) { this.navs.splice(i, 1); this.touch(); },
    moveNav(i, d) { this.navs = moveItem(this.navs, i, d); this.touch(); },
    addLevel(n) {
      if (!n.adding) return;
      n.index.push(n.adding);
      n.adding = '';
      this.touch();
    },
    removeLevel(n, i) { n.index.splice(i, 1); this.touch(); },
    moveLevel(n, i, d) { n.index = moveItem(n.index, i, d); this.touch(); },
    resetDefaults() {
      this.navs = DEFAULT_NAVIGATIONS.map(toForm);
      this.touch();
    },
    addExtra(name) {
      const t = String(name || '').trim();
      if (!t || this.extraTags.some(x => x.toLowerCase() === t.toLowerCase())) return;
      this.extraTags.push(t);
      this.newExtra = '';
      this.touch();
    },
    removeExtra(t) { this.extraTags = this.extraTags.filter(x => x !== t); this.touch(); },
    async save() {
      this.busy = true; this.error = ''; this.info = '';
      try {
        const r = await Api.saveNavigations({
          version: this.version, navigations: this.navs.map(fromForm), extraTags: this.extraTags
        });
        this.info = 'Saved and applied' + (r.persisted ? '' : ' (not written to config.json: the config directory is unknown)') + '.';
        await this.load();
        this.info = 'Saved and applied.';
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    }
  },
  template: `
    <section class="navigations">
      <h1>Navigations</h1>
      <p class="muted">Tag navigations appear as containers at the root of every library and in the directories that list
        them (settings: <code>navigations</code>, inherited by sub-directories). Changes apply at once, no restart.
        A level with more than <em>subitems</em> entries is split into at most <em>ranges</em> containers; leave both empty to use the global defaults.</p>
      <banner :message="error"></banner>
      <banner :message="info" kind="ok"></banner>

      <div v-for="(n, i) in navs" :key="i" class="card nav-card">
        <div class="row">
          <move-buttons :index="i" :count="navs.length" @move="d => moveNav(i, d)"></move-buttons>
          <input v-model="n.name" class="grow" aria-label="Navigation name" maxlength="80" @input="touch">
          <label class="inline">subitems <input v-model="n.subitems" type="number" min="1" placeholder="default" @input="touch"></label>
          <label class="inline">ranges <input v-model="n.ranges" type="number" min="2" placeholder="default" @input="touch"></label>
          <button type="button" class="danger" @click="removeNav(i)">Remove</button>
        </div>
        <ol class="nav-levels">
          <li v-for="(t, j) in n.index" :key="j + t" class="row">
            <move-buttons :index="j" :count="n.index.length" @move="d => moveLevel(n, j, d)"></move-buttons>
            <span class="grow"><strong>{{ t }}</strong></span>
            <button type="button" class="icon" title="Remove this level" @click="removeLevel(n, j)">&times;</button>
          </li>
        </ol>
        <div class="row">
          <select v-model="n.adding" aria-label="Add a level">
            <option value="">Add a level&hellip;</option>
            <option v-for="t in allTags" :key="t" :value="t">{{ t }}</option>
          </select>
          <button type="button" :disabled="!n.adding" @click="addLevel(n)">Add</button>
          <span class="muted small">The last level lists the tracks; <code>title</code> ends the chain.</span>
        </div>
      </div>
      <p v-if="!navs.length" class="muted">No navigations: libraries show only their folders.</p>

      <div class="row">
        <button type="button" @click="addNav">Add a navigation</button>
        <button type="button" @click="resetDefaults">Reset to the sample (Album, Artists, Genres, Songs)</button>
      </div>

      <h2>Extra tags</h2>
      <p class="muted">Tags found in your files that you want to use in navigations. The scanner reads only the tags listed here
        (a rescan is needed to fill them in).</p>
      <ul class="plain">
        <li v-for="t in extraTags" :key="t" class="row"><code class="grow">{{ t }}</code>
          <button type="button" class="icon" title="Stop using this tag" @click="removeExtra(t)">&times;</button></li>
      </ul>
      <p v-if="!extraTags.length" class="muted small">None chosen.</p>
      <div v-if="candidates.length" class="stack">
        <h3>Found in the library</h3>
        <ul class="plain">
          <li v-for="d in candidates" :key="d.name" class="row"><code>{{ d.name }}</code>
            <span class="muted small grow">{{ d.count }} tracks</span>
            <button type="button" @click="addExtra(d.name)">Use</button></li>
        </ul>
      </div>
      <form class="row" @submit.prevent="addExtra(newExtra)">
        <input v-model="newExtra" placeholder="Tag name" aria-label="Extra tag name" maxlength="60">
        <button type="submit" :disabled="!newExtra.trim()">Add tag</button>
      </form>

      <div class="row save-row">
        <button type="button" class="primary" :disabled="busy || !dirty" @click="save">Save and apply</button>
        <button type="button" :disabled="busy || !dirty" @click="load">Cancel</button>
        <span v-if="dirty" class="muted">Unsaved changes</span>
      </div>
    </section>`
};
