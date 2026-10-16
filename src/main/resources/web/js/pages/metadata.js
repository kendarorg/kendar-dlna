<button type="submit" class="primary" :disabled="busy || !canEdit || !changed">{{ tree ? 'Apply to all&hellip;' : 'Save' }}</button>:disabled="!canEdit || (!tree && fieldDisabled(f.key)) || (clear[f.key])"/* Metadata editor: single and batch tag edits for the tracks of one directory. */
import { Api } from '../api.js';
import { formatDuration } from '../core/format.js';
import { withPlayability } from '../core/track.js';
import { Icon } from '../components/icons.js';
import { player } from '../player/index.js';
import { playList } from '../actions.js';
import { askConsent } from '../state.js';
import { CONSENT_PHRASE } from '../core/consent.js';

const TAG_FIELDS = [
  { key: 'title', label: 'Title', number: false },
  { key: 'artist', label: 'Artist', number: false },
  { key: 'album', label: 'Album', number: false },
  { key: 'albumArtist', label: 'Album artist', number: false },
  { key: 'genre', label: 'Genre', number: false },
  { key: 'composer', label: 'Composer', number: false },
  { key: 'year', label: 'Year', number: true },
  { key: 'trackNo', label: 'Track no.', number: true },
  { key: 'discNo', label: 'Disc no.', number: true },
  { key: 'discTotal', label: 'Disc total', number: true }
];

function emptyForm() {
  const f = {};
  TAG_FIELDS.forEach(t => { f[t.key] = ''; });
  return f;
}

function formFromTrack(track) {
  const f = {};
  TAG_FIELDS.forEach(t => { f[t.key] = track[t.key] === null || track[t.key] === undefined ? '' : String(track[t.key]); });
  return f;
}

function textOf(v) { return String(v === null || v === undefined ? '' : v).trim(); }

/** A track's value of one field as trimmed text; null/blank (and 0 for numeric fields, "unset") count as empty. */
function fieldText(track, t) {
  const v = track[t.key];
  if (v === null || v === undefined) { return ''; }
  if (t.number && Number(v) === 0) { return ''; }
  return textOf(v);
}

/**
 * Common values of the selected tracks, per field: { key: { value, mixed } }. Only tracks whose format can store
 * the field take part (editableFields), so a MIDI file does not make "album" look mixed. All the same (trimmed,
 * case-sensitive) gives that text; all empty gives '' and mixed = false; otherwise '' and mixed = true.
 */
function commonValues(tracks) {
  const out = {};
  TAG_FIELDS.forEach(t => {
    const texts = tracks.filter(x => (x.editableFields || []).includes(t.key)).map(x => fieldText(x, t));
    const same = texts.every(x => x === texts[0]);
    out[t.key] = same ? { value: texts.length ? texts[0] : '', mixed: false } : { value: '', mixed: true };
  });
  return out;
}

/**
 * The edit to send. Single file: the fields that changed (empty text clears the tag).
 * Batch: the form is pre-filled with the value all selected files share ("original"; empty when they differ or all
 * are empty). Only fields the user touched AND that now differ from what was displayed are sent: a pre-filled value
 * left alone is not rewritten, mixed fields stay untouched, an edited value goes to every selected file.
 * Emptying a pre-filled field (shown value non-empty, new text empty) is a request to clear it, same as the
 * "clear" checkbox, which always wins. Emptying a field that showed nothing is "keep".
 */
function buildEdit(single, form, original, clear, touched) {
  const edit = {};
  TAG_FIELDS.forEach(t => {
    const v = textOf(form[t.key]);
    const shown = textOf(original[t.key]);
    if (single) {
      if (v !== shown) edit[t.key] = v;
    } else if (clear[t.key]) {
      edit[t.key] = '';
    } else if (touched[t.key] && v !== shown) {
      edit[t.key] = v;
    }
  });
  return edit;
}

export const MetadataPage = {
  name: 'MetadataPage',
  components: { Icon },
  setup() { return { player: player }; },
  data() {
    return {
      folder: null, selected: [], form: emptyForm(), original: emptyForm(), clear: {}, touched: {}, fields: TAG_FIELDS,
      error: '', info: '', failures: [], busy: false, scope: 'selection'
    };
  },
  computed: {
    path() { return this.$route.query.p; },
    tracks() { return this.folder ? this.folder.children.filter(c => c.type === 'track') : []; },
    editable() { return this.tracks.filter(c => c.track.editable); },
    selectedTracks() { return this.tracks.filter(c => this.selected.includes(c.path)); },
    tree() { return this.scope === 'tree'; },
    single() { return !this.tree && this.selected.length === 1; },
    batchLike() { return this.tree || this.selected.length > 1; },
    canEdit() { return this.tree || this.selected.length > 0; },
    edit() { return buildEdit(this.single, this.form, this.original, this.clear, this.touched); },
    changed() { return Object.keys(this.edit).length > 0; },
    playingPath() { const c = this.player.current; return c && c.track ? c.track.path : null; },
    isPlaying() { return this.player.state.status === 'playing' || this.player.state.status === 'loading'; }
  },
  watch: {
    scope() { this.prefill(false); },
    selected(now, before) {
      if (this.tree) { return; }
      // From/to a single file or an empty selection starts afresh; within a batch, keep what the user changed.
      this.prefill(now.length > 1 && before.length > 1);
    }
  },
  created() { this.load(this.$route.query.t); },
  methods: {
    formatDuration,
    /** Listen to a track (the directory plays on from it); the same button pauses the track that is playing. */
    listen(c) {
      if (c.track.path === this.playingPath) { this.player.toggle(); return; }
      // ephemeral: maintenance listening is never reported to the history nor saved as the stored queue
      const tracks = this.tracks.map(x => Object.assign({}, withPlayability(x.track, Api.formats), { ephemeral: true }));
      playList(tracks, { start: Math.max(0, this.tracks.indexOf(c)) });
    },
    /** No selected file can hold this field (e.g. album for MIDI only): the input is disabled. */
    fieldDisabled(key) {
      return this.selectedTracks.length > 0 && this.selectedTracks.every(c => !(c.track.editableFields || []).includes(key));
    },
    /** Why a field is limited, or empty: some or all of the selected files cannot hold it. */
    fieldNote(key) {
      const skipped = this.selectedTracks.filter(c => !(c.track.editableFields || []).includes(key));
      if (!skipped.length) { return ''; }
      const formats = [...new Set(skipped.map(c => c.track.format.toUpperCase()))].join(', ');
      return skipped.length === this.selectedTracks.length
        ? formats + ' files cannot store this field (a MIDI file only holds title, artist and year)'
        : 'Skipped for ' + skipped.length + ' ' + formats + ' file(s) that cannot store this field';
    },
    async load(preselect) {
      this.error = '';
      try {
        this.folder = await Api.folder(this.path);
        const keep = this.selected.filter(p => this.editable.some(c => c.path === p));
        this.selected = preselect && this.editable.some(c => c.path === preselect) ? [preselect] : keep;
        this.prefill();
      } catch (e) { this.error = e.message; }
    },
    /** Will the field be cleared in all selected files (box ticked, or a pre-filled value was deleted)? */
    clearing(key) {
      if (this.single) { return false; }
      if (this.clear[key]) { return true; }
      return !!this.touched[key] && textOf(this.form[key]) === '' && textOf(this.original[key]) !== '';
    },
    /** "(keep)" only when the selected files disagree on this field. */
    placeholder(key) {
      if (this.selected.length < 2) { return ''; }
      return commonValues(this.selectedTracks.map(c => c.track))[key].mixed ? '(keep)' : '';
    },
    /**
     * Sets the displayed values. keepTyped (batch selection changed): untouched fields follow the new common
     * values, touched fields keep the user's text and the clear boxes stay as they are.
     */
    prefill(keepTyped) {
      if (this.tree) {
        this.clear = {}; this.touched = {}; this.original = emptyForm(); this.form = emptyForm();
        return;
      }
      if (this.selected.length > 1) {
        const common = commonValues(this.selectedTracks.map(c => c.track));
        const original = emptyForm();
        const form = keepTyped ? Object.assign({}, this.form) : emptyForm();
        TAG_FIELDS.forEach(t => {
          original[t.key] = common[t.key].value;
          if (!keepTyped || !this.touched[t.key]) { form[t.key] = common[t.key].value; }
        });
        if (!keepTyped) { this.clear = {}; this.touched = {}; }
        this.original = original;
        this.form = form;
        return;
      }
      this.clear = {};
      this.touched = {};
      if (this.single) {
        const c = this.tracks.find(x => x.path === this.selected[0]);
        this.original = c ? formFromTrack(c.track) : emptyForm();
        this.form = Object.assign({}, this.original);
      } else {
        this.original = emptyForm();
        this.form = emptyForm();
      }
    },
    selectAll() { this.selected = this.editable.map(c => c.path); },
    selectNone() { this.selected = []; },
    /** Whole folder tree: count first, typed consent, then the background job with its progress. */
    async saveTree() {
      this.busy = true; this.error = ''; this.info = ''; this.failures = [];
      try {
        const edit = this.edit;
        const p = await Api.bulkTagsPreview(this.path, edit);
        if (!p.files) { this.error = 'No file below this folder can hold these fields.'; return; }
        const lines = [p.files + ' file(s) will be rewritten: ' + Object.keys(edit).join(', ') + '.'];
        if (p.segments) lines.push(p.segments + ' cue/chapter track(s) have no tags of their own and are skipped.');
        if (p.notEditable) lines.push(p.notEditable + ' read-only file(s) (Opus, raw AAC) are skipped.');
        if (p.notApplicable) lines.push(p.notApplicable + ' file(s) cannot store these fields and are skipped.');
        lines.push('This changes the files on disk and cannot be undone (old values are logged).');
        const ok = await askConsent({ title: 'Edit tags below ' + this.path + '?', lines: lines, confirmLabel: 'Edit ' + p.files + ' files' });
        if (!ok) return;
        let job = await Api.bulkTagsApply(this.path, edit, CONSENT_PHRASE, p.files);
        while (job.running) {
          this.info = 'Editing tags: ' + (job.done + job.failed) + ' of ' + job.total + '...';
          await new Promise(r => setTimeout(r, 700));
          job = await Api.bulkTagsJob();
        }
        this.failures = job.failures;
        if (job.error) this.error = job.error;
        this.info = 'Tags written to ' + job.done + ' of ' + job.total + ' files' + (job.failed ? ' (' + job.failed + ' failed)' : '') + '.';
        await this.load();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async save() {
      if (this.tree) { return this.saveTree(); }
      this.busy = true; this.error = ''; this.info = ''; this.failures = [];
      const edit = this.edit;
      try {
        if (this.single) {
          await Api.saveTrack(this.selected[0], edit);
          this.info = 'Tags written.';
        } else {
          const r = await Api.saveTracks(this.selected, edit);
          this.failures = r.results.filter(x => !x.ok);
          this.info = 'Tags written to ' + (r.results.length - r.failed) + ' of ' + r.results.length + ' files.';
        }
        const keep = this.selected.slice();
        await this.load();
        this.selected = keep;
        this.prefill(false);
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async rename(c) {
      const name = window.prompt('Rename file', c.name);
      if (name === null || !name.trim() || name.trim() === c.name) { return; }
      this.busy = true; this.error = ''; this.info = '';
      try {
        const r = await Api.renameTrack(c.path, name.trim());
        this.info = 'Renamed to ' + r.name + '.';
        this.selected = [];
        await this.load();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    cancel() { this.prefill(false); }
  },
  template: `
    <section class="metadata">
      <nav class="crumbs"><router-link :to="{ path: '/library', query: { p: path } }">&larr; Back to the directory</router-link></nav>
      <h1>Edit tags <small class="muted">{{ path }}</small></h1>
      <banner :message="error"></banner>
      <banner :message="info" kind="ok"></banner>
      <ul v-if="failures.length" class="plain"><li v-for="f in failures" :key="f.path" class="bad-text">{{ f.path }}: {{ f.error }}</li></ul>
      <div v-if="folder" class="split">
        <div class="scroll list">
          <div class="row">
            <button type="button" @click="selectAll">Select all</button>
            <button type="button" @click="selectNone">None</button>
            <span class="muted">{{ selected.length }} selected</span>
          </div>
          <table>
            <thead><tr><th></th><th></th><th>#</th><th>Title</th><th>Artist</th><th>Album</th><th>Length</th></tr></thead>
            <tbody>
              <tr v-for="c in tracks" :key="c.path" :class="{ disabled: !c.track.editable }">
                <td><input type="checkbox" :value="c.path" v-model="selected" :disabled="!c.track.editable"
                           :aria-label="'Select ' + c.name"></td>
                <td><button type="button" class="icon-btn" :title="$t('player.play')" :aria-label="$t('player.play') + ' ' + c.name"
                            @click="listen(c)"><icon :name="c.track.path === playingPath && isPlaying ? 'pause' : 'play'"></icon></button></td>
                <td>{{ c.track.trackNo || '' }}</td>
                <td>{{ c.track.displayTitle }}<div class="muted small">{{ c.name }}
                  <button type="button" class="icon-btn" title="Rename file" aria-label="Rename file" :disabled="busy" @click="rename(c)"><icon name="edit"></icon></button></div></td>
                <td>{{ c.track.artist }}</td>
                <td>{{ c.track.album }}</td>
                <td>{{ formatDuration(c.track.durationMillis) }}
                  <span v-if="!c.track.editable" class="badge" title="Opus and raw .aac (ADTS) tags cannot be edited">read-only</span>
                  <span v-else-if="c.track.editableFields.length < 10" class="badge" title="This format only stores: title, artist, year">limited</span></td>
              </tr>
            </tbody>
          </table>
        </div>
        <form class="editor stack" @submit.prevent="save">
          <fieldset class="scope">
            <legend>Apply to</legend>
            <label class="inline"><input type="radio" value="selection" v-model="scope"> the selected files</label>
            <label class="inline"><input type="radio" value="tree" v-model="scope"> this folder and all its subfolders</label>
          </fieldset>
          <p v-if="tree" class="banner info">Every file below <code>{{ path }}</code> that can hold the filled fields is rewritten. You will see the count first and must type I CONSENT. Old values are logged to tag-changes.log in the data directory.</p>
          <h2 v-if="tree">Edit the whole folder tree</h2>
          <h2 v-else-if="!selected.length">Select tracks</h2>
          <h2 v-else-if="single">Edit one file</h2>
          <h2 v-else>Edit {{ selected.length }} files</h2>
          <p v-if="selected.length > 1" class="muted">Shown values are shared by all selected files; "(keep)" means they differ. Only fields you change are written. Tick "clear" (or delete a shown value) to remove a tag.</p>
          <div v-for="f in fields" :key="f.key" class="field">
            <label :for="'f-' + f.key">{{ f.label }}</label>
            <input :id="'f-' + f.key" :type="f.number ? 'number' : 'text'" v-model="form[f.key]" :disabled="!selected.length || fieldDisabled(f.key) || (clear[f.key])" :title="fieldNote(f.key)"
                   :placeholder="placeholder(f.key)" @input="touched[f.key] = true">
            <label v-if="batchLike" class="inline"><input type="checkbox" v-model="clear[f.key]" :disabled="!tree && fieldDisabled(f.key)"> clear</label>
            <p v-if="clearing(f.key) && !clear[f.key]" class="muted small">Will be cleared in all selected files</p>
            <p v-if="fieldNote(f.key)" class="muted small">{{ fieldNote(f.key) }}</p>
          </div>
          <div class="row">
            <button type="submit" class="primary" :disabled="busy || !selected.length || !changed">Save</button>
            <button type="button" :disabled="busy" @click="cancel">Cancel</button>
          </div>
          <p class="muted small">Files are rewritten safely (temporary file, then atomic rename).</p>
        </form>
      </div>
    </section>`
};
