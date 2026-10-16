/*
 * Lyrics (admin): look the lyrics of one track up at the configured provider and save them next to the file as
 * .lrc (synced) or .txt, or look up a whole folder tree and apply the matches you choose. Nothing is written
 * until you save or apply.
 */
import { Api } from '../api.js';

function previewOf(lyrics) {
  return lyrics.lines.map(l => l.text).filter(t => t && t.trim()).slice(0, 6).join(' / ');
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
      provider: null, existing: null, candidates: [], text: '', looked: false,
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
  beforeUnmount() { clearTimeout(this.timer); },
  methods: {
    previewOf,
    async loadExisting() {
      try {
        const r = await Api.lyrics(this.track);
        this.existing = r.lyrics;
        this.text = toText(r.lyrics);
      } catch (e) { this.error = e.message; }
    },
    async lookup() {
      this.busy = true; this.error = ''; this.info = '';
      try {
        const r = await Api.lyricsLookup(this.track);
        this.candidates = r.candidates;
        this.looked = true;
        if (r.candidates.length && !this.text) this.text = toText(r.candidates[0]);
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    use(c) { this.text = toText(c); this.info = ''; },
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
        <p v-if="looked && !candidates.length" class="muted">Nothing found.</p>
        <ul class="plain">
          <li v-for="(c, i) in candidates" :key="i" class="card">
            <div class="row"><strong>Candidate {{ i + 1 }}</strong>
              <span class="badge" :class="c.synced ? 'ok' : ''">{{ c.synced ? 'synced' : 'plain' }}</span>
              <span class="grow"></span><button type="button" @click="use(c)">Use this</button></div>
            <p class="muted small">{{ previewOf(c) }}</p>
          </li>
        </ul>
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
