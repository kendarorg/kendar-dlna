/* Metadata editor: single and batch tag edits for the tracks of one directory. */
import { Api } from '../api.js';
import { formatDuration } from '../core/format.js';

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
  data() {
    return {
      folder: null, selected: [], form: emptyForm(), original: emptyForm(), clear: {}, touched: {}, fields: TAG_FIELDS,
      error: '', info: '', failures: [], busy: false
    };
  },
  computed: {
    path() { return this.$route.query.p; },
    tracks() { return this.folder ? this.folder.children.filter(c => c.type === 'track') : []; },
    editable() { return this.tracks.filter(c => c.track.editable); },
    selectedTracks() { return this.tracks.filter(c => this.selected.includes(c.path)); },
    single() { return this.selected.length === 1; },
    edit() { return buildEdit(this.single, this.form, this.original, this.clear, this.touched); },
    changed() { return Object.keys(this.edit).length > 0; }
  },
  watch: {
    selected(now, before) {
      // From/to a single file or an empty selection starts afresh; within a batch, keep what the user changed.
      this.prefill(now.length > 1 && before.length > 1);
    }
  },
  created() { this.load(this.$route.query.t); },
  methods: {
    formatDuration,
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
    async save() {
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
            <thead><tr><th></th><th>#</th><th>Title</th><th>Artist</th><th>Album</th><th>Length</th></tr></thead>
            <tbody>
              <tr v-for="c in tracks" :key="c.path" :class="{ disabled: !c.track.editable }">
                <td><input type="checkbox" :value="c.path" v-model="selected" :disabled="!c.track.editable"
                           :aria-label="'Select ' + c.name"></td>
                <td>{{ c.track.trackNo || '' }}</td>
                <td>{{ c.track.displayTitle }}<div class="muted small">{{ c.name }}</div></td>
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
          <h2 v-if="!selected.length">Select tracks</h2>
          <h2 v-else-if="single">Edit one file</h2>
          <h2 v-else>Edit {{ selected.length }} files</h2>
          <p v-if="selected.length > 1" class="muted">Shown values are shared by all selected files; "(keep)" means they differ. Only fields you change are written. Tick "clear" (or delete a shown value) to remove a tag.</p>
          <div v-for="f in fields" :key="f.key" class="field">
            <label :for="'f-' + f.key">{{ f.label }}</label>
            <input :id="'f-' + f.key" :type="f.number ? 'number' : 'text'" v-model="form[f.key]" :disabled="!selected.length || fieldDisabled(f.key) || (clear[f.key])" :title="fieldNote(f.key)"
                   :placeholder="placeholder(f.key)" @input="touched[f.key] = true">
            <label v-if="selected.length > 1" class="inline"><input type="checkbox" v-model="clear[f.key]" :disabled="fieldDisabled(f.key)"> clear</label>
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
