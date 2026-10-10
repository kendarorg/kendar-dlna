/* Admin: editor of a directory's list.json (per-file title, author, part of, transcriber). Logic: core/list-json.js. */
import { Api } from '../api.js';
import { toast } from '../state.js';
import {
  emptyRow, rowsFromView, snapshot, isDirty, missingFiles, fileChoices, addMissing, bulkSet, removeRow,
  validateRows, toBody, FIELDS, MAX_FIELD, rowsFromSnapshot, previewGenerated, mergeGenerated,
  rowsForFile, addSection, splitFilePath, sectionsRoute
} from '../core/list-json.js';

export const ListJsonPage = {
  name: 'ListJsonPage',
  data() {
    return {
      view: null, rows: [], original: '', errors: {}, error: '', conflict: false, busy: false,
      bulk: { field: 'author', value: '', onlyEmpty: true }, fields: FIELDS, maxField: MAX_FIELD,
      gen: null   // {proposal, before (snapshot of the rows), overwrite, preview, result}
    };
  },
  computed: {
    path() { return this.$route.query.p; },
    /** True on /sections: the sidecar `<file>.json` of the one audio file at `path`, not the directory's list.json. */
    fileMode() { return this.$route.path === '/sections'; },
    dirty() { return isDirty(this.rows, this.original); },
    missing() { return this.view && !this.fileMode ? missingFiles(this.rows, this.view.files) : []; },
    dir() { return this.path ? this.path.split('/').pop() : ''; },
    dirPath() { return this.fileMode ? splitFilePath(this.path).dir : this.path; },
    fileName() { return this.fileMode ? splitFilePath(this.path).name : ''; },
    sidecarLinks() {
      return this.view && !this.fileMode && this.view.sidecars
        ? this.view.sidecars.map(n => ({ name: n, to: sectionsRoute(this.path + '/' + n) })) : [];
    }
  },
  watch: { '$route.fullPath'() { this.gen = null; this.load(); } },
  created() { this.load(); },
  methods: {
    async load() {
      this.error = ''; this.conflict = false; this.errors = {};
      try {
        this.view = this.fileMode ? await Api.fileList(this.path) : await Api.listJson(this.path);
        this.rows = this.fileMode ? rowsForFile(this.view) : rowsFromView(this.view);
        this.original = snapshot(this.rows);
      } catch (e) { this.error = e.message; this.view = null; }
    },
    choices(i) { return fileChoices(this.rows, this.view.files, i); },
    addRow() {
      if (this.fileMode) addSection(this.rows, this.view.fileName);
      else this.rows.push(emptyRow(''));
    },
    addAllMissing() {
      const n = addMissing(this.rows, this.view.files);
      if (n) toast(this.$tn('listjson.added', n));
    },
    remove(i) { removeRow(this.rows, i); },
    applyBulk() {
      const n = bulkSet(this.rows, this.bulk.field, this.bulk.value.trim(), this.bulk.onlyEmpty);
      toast(this.$tn('listjson.bulkDone', n));
    },
    discard() { this.rows = rowsFromSnapshot(this.original); this.gen = null; },
    /** Asks the server what the cue sheets / chapters say (writes nothing) and merges it into the table. */
    async generate() {
      this.busy = true; this.error = '';
      try {
        const proposal = this.fileMode ? await Api.generateFileList(this.path) : await Api.generateListJson(this.path);
        if (!proposal.entries.length) { this.gen = null; toast(this.$t('listjson.gen.none')); return; }
        this.gen = { proposal: proposal, before: snapshot(this.rows), overwrite: false,
          preview: previewGenerated(this.rows, proposal.entries), result: null };
        this.mergeProposal();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    /** (Re)builds the table from the rows as they were before generating plus the proposal. */
    mergeProposal() {
      this.rows = rowsFromSnapshot(this.gen.before);
      this.gen.result = mergeGenerated(this.rows, this.gen.proposal.entries, this.gen.overwrite);
    },
    closeGenerate() { this.gen = null; this.rows.forEach(r => { delete r.generated; }); },
    async save() {
      this.errors = validateRows(this.rows, this.view.files);
      if (Object.keys(this.errors).length) { this.error = this.$t('listjson.fix'); return; }
      this.busy = true; this.error = ''; this.conflict = false;
      try {
        const body = toBody(this.rows, this.view.version);
        this.view = this.fileMode ? await Api.saveFileList(this.path, body) : await Api.saveListJson(this.path, body);
        this.rows = this.fileMode ? rowsForFile(this.view) : rowsFromView(this.view);
        this.original = snapshot(this.rows);
        this.gen = null;
        toast(this.$t(this.fileMode ? 'listjson.file.saved' : 'listjson.saved'));
      } catch (e) {
        if (e.status === 409 || e.code === 'conflict') this.conflict = true;
        this.error = e.message;
      } finally { this.busy = false; }
    }
  },
  template: `
    <section v-if="view" class="list-json">
      <nav class="crumbs"><router-link :to="{ path: '/library', query: { p: dirPath } }">&larr; {{ dirPath.split('/').pop() }}</router-link>
        <router-link v-if="fileMode" :to="{ path: '/list-json', query: { p: dirPath } }">{{ $t('listjson.file.dirList') }}</router-link></nav>
      <h1 v-if="fileMode">{{ $t('listjson.file.title') }} <small class="muted">{{ view.sidecar }}</small></h1>
      <h1 v-else>{{ $t('listjson.title') }} <small class="muted">{{ path }}</small></h1>
      <p class="muted">{{ fileMode ? $t('listjson.file.help', { file: fileName }) : $t('listjson.help') }}</p>
      <p v-if="view.error" class="banner error">{{ fileMode ? $t('listjson.file.invalid', { error: view.error }) : $t('listjson.invalid', { error: view.error }) }}</p>
      <ul v-if="view.warnings.length" class="plain small muted"><li v-for="(w, i) in view.warnings" :key="i">{{ w }}</li></ul>
      <p v-if="!fileMode && view.unknown.length" class="banner info">{{ $t('listjson.unknown', { files: view.unknown.join(', ') }) }}</p>
      <p v-if="sidecarLinks.length" class="banner info">{{ $t('listjson.sidecars') }}
        <router-link v-for="s in sidecarLinks" :key="s.name" :to="s.to" class="sidecar-link">{{ s.name }}</router-link></p>
      <banner :message="error"></banner>
      <p v-if="conflict" class="row"><span>{{ fileMode ? $t('listjson.file.conflict') : $t('listjson.conflict') }}</span>
        <button type="button" @click="load">{{ $t('listjson.reload') }}</button></p>

      <div class="row">
        <button v-if="!fileMode" type="button" :disabled="busy || !missing.length" @click="addAllMissing">{{ $tn('listjson.addMissing', missing.length) }}</button>
        <button type="button" :disabled="busy" @click="addRow">{{ fileMode ? $t('listjson.file.addSection') : $t('listjson.addRow') }}</button>
        <button type="button" :disabled="busy" :title="$t('listjson.gen.help')" @click="generate">{{ $t('listjson.gen.button') }}</button>
        <details class="inline-details">
          <summary>{{ $t('listjson.bulk') }}</summary>
          <form class="row" @submit.prevent="applyBulk">
            <select v-model="bulk.field" :aria-label="$t('listjson.bulkField')">
              <option v-for="f in fields" :key="f" :value="f">{{ $t('listjson.col.' + f) }}</option>
            </select>
            <input v-model="bulk.value" :maxlength="maxField" :aria-label="$t('listjson.bulkValue')">
            <label class="inline"><input type="checkbox" v-model="bulk.onlyEmpty"> {{ $t('listjson.onlyEmpty') }}</label>
            <button type="submit" :disabled="busy || !rows.length">{{ $t('listjson.apply') }}</button>
          </form>
        </details>
      </div>

      <div v-if="gen" class="generate-panel" role="status">
        <p><strong>{{ $t('listjson.gen.title') }}</strong>
          {{ $tn('listjson.gen.sources', gen.proposal.sources.length) }}</p>
        <p>{{ $t('listjson.gen.result', gen.result) }}</p>
        <ul v-if="gen.proposal.warnings.length" class="plain small muted"><li v-for="(w, i) in gen.proposal.warnings" :key="i">{{ w }}</li></ul>
        <div class="row">
          <label class="inline" v-if="gen.preview.withData"><input type="checkbox" v-model="gen.overwrite" @change="mergeProposal">
            {{ $tn('listjson.gen.overwrite', gen.preview.withData) }}</label>
          <button type="button" @click="closeGenerate">{{ $t('listjson.gen.done') }}</button>
          <span class="muted">{{ $t('listjson.gen.saveHint') }}</span>
        </div>
      </div>

      <p v-if="!rows.length" class="muted">{{ fileMode ? $t('listjson.file.empty') : $t('listjson.empty') }}</p>
      <div class="scroll" v-else>
        <table class="list-table">
          <thead><tr>
            <th v-if="!fileMode">{{ $t('listjson.col.filename') }}</th><th>{{ $t('listjson.col.index') }}</th><th>{{ $t('listjson.col.start') }}</th><th>{{ $t('listjson.col.author') }}</th><th>{{ $t('listjson.col.partOf') }}</th>
            <th>{{ $t('listjson.col.title') }}</th><th>{{ $t('listjson.col.transcriber') }}</th><th></th>
          </tr></thead>
          <tbody>
            <tr v-for="(r, i) in rows" :key="r.id" :class="{ generated: r.generated }">
              <td v-if="!fileMode">
                <select v-model="r.filename" :aria-label="$t('listjson.col.filename')" :aria-invalid="!!errors[i]">
                  <option value="">{{ $t('listjson.pick') }}</option>
                  <option v-for="f in choices(i)" :key="f" :value="f">{{ f }}</option>
                </select>
                <div v-if="errors[i]" class="bad-text small" role="alert">{{ $t(errors[i]) }}</div>
              </td>
              <td><input v-model="r.index" class="narrow" inputmode="numeric" maxlength="9" :aria-label="$t('listjson.col.index')" :aria-invalid="fileMode && !!errors[i]">
                <div v-if="fileMode && errors[i]" class="bad-text small" role="alert">{{ $t(errors[i]) }}</div></td>
              <td><input v-model="r.start" class="narrow" maxlength="16" placeholder="mm:ss.fff" :aria-label="$t('listjson.col.start')"></td>
              <td><input v-model="r.author" :maxlength="maxField" :aria-label="$t('listjson.col.author')"></td>
              <td><input v-model="r.partOf" :maxlength="maxField" :aria-label="$t('listjson.col.partOf')"></td>
              <td><input v-model="r.title" :maxlength="maxField" :aria-label="$t('listjson.col.title')"></td>
              <td><input v-model="r.transcriber" :maxlength="maxField" :aria-label="$t('listjson.col.transcriber')"></td>
              <td><button type="button" :disabled="busy" @click="remove(i)">{{ $t('common.delete') }}</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="row">
        <button type="button" class="primary" :disabled="busy || !dirty" @click="save">{{ $t('common.save') }}</button>
        <button type="button" :disabled="busy || !dirty" @click="discard">{{ $t('common.cancel') }}</button>
        <span v-if="dirty" class="muted">{{ $t('listjson.unsaved') }}</span>
        <span v-if="!rows.length && view.exists" class="muted">{{ fileMode ? $t('listjson.file.deleteHint', { sidecar: view.sidecar }) : $t('listjson.deleteHint') }}</span>
      </div>
    </section>
    <banner v-else :message="error"></banner>`
};
