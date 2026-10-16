/*
 * Lyrics (admin): look the lyrics of one track up at the configured provider and save them next to the file as
 * .lrc (synced) or .txt, or look up a whole folder tree and apply the matches you choose. Nothing is written
 * until you save or apply.
 */
import { Api } from '../api.js';

function previewOf(lyrics) {
  return lyrics.lines.map(l => l.text).filter(t => t && t.trim()).slice(0, 2).join(' / ');
}

/** The first ten lines that have something in them (empty and white lines left out). */
export function previewLines(lyrics) {
  return lyrics.lines.map(l => (l.text || '').trim()).filter(t => t).slice(0, 10);
}

function countLines(lyrics) {
  return lyrics.lines.filter(l => l.text && l.text.trim()).length;
}

function toText(lyrics) {
  if (!lyrics) return '';
  return lyrics.lines.map(l => {
    if (!lyrics.synced || l.t < 0) return l.text;
    const ms = Math.max(0, l.t);
    const mm = String(Math.floor(ms / 60000)).padStart(2, '0');
    const ss = String(Math.floor(ms / 1000) % 60).padStart(2, '0');
    const cs = String(Math.floor((ms % 1000) / 10)).padStart(2, '0');
    return '[' + mm + ':' + ss + '.' + cs + ']' + l.text;
  }).join('\n');
}

export const LyricsPage = {
  name: 'LyricsPage',
  data() {
    return {
      provider: null, existing: null, candidates: [], chosen: null, text: '', looked: false, looking: false, providers: [],
      job: null, selected: [], replaceExisting: false, error: '', info: '', busy: false, timer: null
    };
  },
  computed: {
    path() { return this.$route.query.p; },
    track() { return this.$route.query.t || null; },
    target() { return this.track || this.path; },
    reviewable() { return this.job ? this.job.items.filter(i => i.outcome === 'matched' || i.outcome === 'ambiguous') : []; }
  },
  async created() {
    try { this.provider = (await Api.lyricsStatus()).provider; } catch (e) { this.error = e.message; }
    if (this.track) await this.loadExisting();
    else await this.refreshJob();
  },
  beforeUnmount() { clearTimeout(this.timer); clearTimeout(this.lookupTimer); },
  methods: {
    previewOf, previewLines, countLines,
    async loadExisting() {
      try {
        const r = await Api.lyrics(this.track);
        this.existing = r.lyrics;
        this.text = toText(r.lyrics);
      } catch (e) { this.error = e.message; }
    },
    /** Starts the lookup; what each provider finds is shown the moment it arrives, so one can be chosen at once. */
    async lookup() {
      clearTimeout(this.lookupTimer);
      this.busy = true; this.looking = true; this.error = ''; this.info = '';
      this.candidates = []; this.providers = []; this.chosen = null; this.looked = false;   // nothing is taken until chosen
      try {
        const r = await Api.lyricsLookupStart(this.track);
        this.absorb(r);
        this.pollLookup(r.id);
      } catch (e) { this.error = e.message; this.looking = false; this.busy = false; }
    },
    /** New candidates are only appended: the index of the one being read or chosen never moves. */
    absorb(r) {
      this.providers = r.providers;
      r.candidates.forEach(c => this.candidates.push(c));
      if (r.error) this.error = r.error;
    },
    pollLookup(id) {
      this.lookupTimer = setTimeout(async () => {
        try {
          const r = await Api.lyricsLookupJob(id, this.candidates.length);
          this.absorb(r);
          if (r.running) { this.pollLookup(id); return; }
        } catch (e) { this.error = e.message; }
        this.looking = false; this.busy = false; this.looked = true;
      }, 400);
    },
    providerLabel(p) {
      if (p.state === 'pending') return p.id + ' …';
      if (p.state === 'failed') return p.id + ' failed';
      return p.id + ': ' + (p.count ? p.count + ' found' : 'nothing');
    },
    /** One candidate (its index) or none (-1): the editor below holds what would be saved. */
    choose(i) {
      this.chosen = i;
      this.info = '';
      this.text = i >= 0 ? toText(this.candidates[i]) : toText(this.existing);
    },
    async save() {
      this.busy = true; this.error = ''; this.info = '';
      try {
        const r = await Api.lyricsSave(this.track, this.text);
        this.info = 'Saved as ' + r.file + (r.synced ? ' (synced)' : '') + '.';
        await this.loadExisting();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async startFolder() {
      this.busy = true; this.error = ''; this.info = '';
      try {
        this.job = await Api.lyricsFolderStart(this.path, this.replaceExisting);
        this.selected = [];
        this.poll();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async refreshJob() {
      try {
        const j = await Api.lyricsFolderJob();
        if (j.directory === this.path || j.running) { this.job = j; if (j.running) this.poll(); }
      } catch (e) { /* no job yet */ }
    },
    poll() {
      clearTimeout(this.timer);
      this.timer = setTimeout(async () => {
        try {
          this.job = await Api.lyricsFolderJob();
          if (this.job.running) this.poll();
          else this.selected = this.job.items.filter(i => i.outcome === 'matched' && !i.applied).map(i => i.path);
        } catch (e) { this.error = e.message; }
      }, 1000);
    },
    async apply() {
      this.busy = true; this.error = ''; this.info = '';
      try {
        const r = await Api.lyricsFolderApply(this.selected);
        this.job = r.job;
        this.info = r.written.length + ' lyrics file(s) written.';
        this.selected = [];
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    }
  },
  template: `
    <section class="lyrics-admin">
      <nav class="crumbs"><router-link :to="{ path: '/library', query: { p: path } }">&larr; Back to the directory</router-link></nav>
      <h1>Lyrics <small class="muted">{{ target }}</small></h1>
      <banner :message="error"></banner>
      <banner :message="info" kind="ok"></banner>
      <p v-if="provider === null" class="banner info">No online provider is configured, so only saving lyrics you type or paste works.
        Set <code>lyrics.provider</code> in <code>config.json</code> (for example <code>lrclib</code>) and restart to look them up.</p>

      <template v-if="track">
        <div class="row">
          <button type="button" class="primary" :disabled="busy || provider === null" @click="lookup">Look up at {{ provider || 'the provider' }}</button>
          <span v-if="existing" class="muted">This track has {{ existing.synced ? 'synced' : 'plain' }} lyrics ({{ existing.source }}).</span>
        </div>
        <p v-if="providers.length" class="small provider-states">
          <span v-for="p in providers" :key="p.id" class="badge" :class="p.state === 'failed' ? 'bad' : (p.count ? 'ok' : '')"
                :title="p.error || ''">{{ providerLabel(p) }}</span></p>
        <p v-if="looked && !candidates.length" class="muted">Nothing found.</p>
        <fieldset v-if="candidates.length" class="candidates">
          <legend>Found {{ candidates.length }}<span v-if="looking"> so far, still looking&hellip;</span>: choose one, or none</legend>
          <label v-for="(c, i) in candidates" :key="i" class="card candidate" :class="{ chosen: chosen === i }">
            <div class="row"><input type="radio" name="candidate" :value="i" :checked="chosen === i" @change="choose(i)">
              <strong>{{ c.source }}</strong>
              <span class="badge" :class="c.synced ? 'ok' : ''">{{ c.synced ? 'synced' : 'plain' }}</span>
              <span class="muted small">{{ countLines(c) }} lines</span></div>
            <ol class="plain preview"><li v-for="(t, j) in previewLines(c)" :key="j">{{ t }}</li></ol>
            <p v-if="countLines(c) > 10" class="muted small">&hellip;</p>
          </label>
          <label class="card candidate" :class="{ chosen: chosen === -1 }"><div class="row">
            <input type="radio" name="candidate" :value="-1" :checked="chosen === -1" @change="choose(-1)">
            <strong>None of these</strong></div></label>
        </fieldset>
        <label class="stack">Lyrics (time tags like <code>[01:23.45]</code> make them synced)
          <textarea v-model="text" rows="18" spellcheck="false" aria-label="Lyrics"></textarea></label>
        <div class="row">
          <button type="button" class="primary" :disabled="busy || !text.trim()" @click="save">Save next to the file</button>
          <span class="muted small">Written as a .lrc (synced) or .txt sidecar; the audio file is not touched.</span>
        </div>
      </template>

      <template v-else>
        <p class="muted">Looks up the lyrics of every track below this folder, one request at a time, and lets you choose which matches to save.</p>
        <div class="row">
          <button type="button" class="primary" :disabled="busy || provider === null || (job && job.running)" @click="startFolder">Find lyrics for this folder</button>
          <label class="inline"><input type="checkbox" v-model="replaceExisting"> also tracks that already have lyrics</label>
        </div>
        <template v-if="job">
          <p class="muted">{{ job.done }} of {{ job.total }} looked up<span v-if="job.running"> &hellip;</span></p>
          <table v-if="job.items.length">
            <thead><tr><th></th><th>Track</th><th>Result</th><th>Lyrics</th></tr></thead>
            <tbody>
              <tr v-for="i in job.items" :key="i.path">
                <td><input v-if="(i.outcome === 'matched' || i.outcome === 'ambiguous') && !i.applied" type="checkbox"
                           :value="i.path" v-model="selected" :aria-label="'Select ' + i.title"></td>
                <td>{{ i.title }}<div class="muted small">{{ i.path }}</div></td>
                <td><span class="badge" :class="i.outcome === 'matched' ? 'ok' : (i.outcome === 'failed' ? 'bad' : '')">{{ i.applied ? 'saved' : i.outcome.replace('_', ' ') }}</span>
                  <span v-if="i.error" class="bad-text small"> {{ i.error }}</span></td>
                <td class="muted small">{{ i.preview }}<span v-if="i.preview"> ({{ i.synced ? 'synced' : 'plain' }})</span></td>
              </tr>
            </tbody>
          </table>
          <div class="row" v-if="reviewable.length">
            <button type="button" class="primary" :disabled="busy || !selected.length" @click="apply">Save the {{ selected.length }} selected</button>
            <span class="muted small">Ambiguous: several different candidates were found; the first is proposed.</span>
          </div>
        </template>
      </template>
    </section>`
};
