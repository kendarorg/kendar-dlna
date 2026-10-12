/* Admin page: rescan, scan status and errors, unsupported files, invalid settings, streams, snapshots, version. */
import { Api } from '../api.js';
import { formatSize, formatTime } from '../core/format.js';

export const AdminPage = {
  name: 'AdminPage',
  data() {
    return {
      scan: null, unsupported: [], invalid: [], streams: null, snapshots: [], version: null,
      importFailures: [], settingsParts: [{ id: 'library', label: 'Library settings' }, { id: 'server', label: 'Server config' }],
      folderPath: '', forceTags: false, error: '', info: '', busy: false, timer: null
    };
  },
  created() {
    this.loadAll();
    this.timer = setInterval(() => this.refreshLive(), 3000);
  },
  beforeUnmount() { clearInterval(this.timer); },
  methods: {
    formatSize, formatTime,
    async exportSettings(part) {
      this.error = ''; this.info = '';
      try {
        const data = await Api.settingsExport(part);
        const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2) + '\n'], { type: 'application/json' }));
        const a = document.createElement('a');
        a.href = url; a.download = 'kendar-' + part + '-settings.json';
        document.body.appendChild(a); a.click(); a.remove();
        URL.revokeObjectURL(url);
      } catch (e) { this.error = e.message; }
    },
    async importSettings(part, event) {
      const file = event.target.files[0];
      event.target.value = '';
      if (!file) { return; }
      this.error = ''; this.info = ''; this.importFailures = [];
      if (!window.confirm('Import ' + file.name + ' as ' + part + ' settings? Existing settings are overwritten.')) { return; }
      this.busy = true;
      try {
        let data;
        try { data = JSON.parse(await file.text()); } catch (e) { throw new Error('The file is not valid JSON'); }
        const r = await Api.settingsImport(part, data);
        if (part === 'server') {
          this.info = 'Server config written (previous one: ' + r.backup + '). Restart the server to apply it.';
        } else {
          this.importFailures = r.results.filter(x => !x.ok);
          this.info = 'Imported ' + (r.results.length - r.failed) + ' of ' + r.results.length + ' directory settings.';
          await this.loadAll();
        }
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async loadAll() {
      this.error = '';
      try {
        const [scan, unsupported, invalid, streams, snapshots, version] = await Promise.all([
          Api.scan(), Api.unsupported(), Api.invalidSettings(), Api.streams(), Api.snapshots(), Api.version()]);
        this.scan = scan; this.unsupported = unsupported.files; this.invalid = invalid.folders;
        this.streams = streams; this.snapshots = snapshots.snapshots; this.version = version;
      } catch (e) { this.error = e.message; }
    },
    async refreshLive() {
      try {
        const [scan, streams, snapshots] = await Promise.all([Api.scan(), Api.streams(), Api.snapshots()]);
        const wasRunning = this.scan && this.scan.running;
        this.scan = scan; this.streams = streams; this.snapshots = snapshots.snapshots;
        if (wasRunning && !scan.running) {
          const [u, i] = await Promise.all([Api.unsupported(), Api.invalidSettings()]);
          this.unsupported = u.files; this.invalid = i.folders;
        }
      } catch (e) { /* session lost or server restarting: the next manual action reports it */ }
    },
    async rescan(path) {
      this.busy = true; this.error = ''; this.info = '';
      try {
        await Api.rescan(path, this.forceTags);
        const what = this.forceTags ? ' (re-reading all tags)' : '';
        this.info = (path ? 'Rescan of ' + path : 'Rescan of the whole library') + ' started' + what + '.';
        this.scan = await Api.scan();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    }
  },
  template: `
    <section class="admin">
      <h1>Admin</h1>
      <banner :message="error"></banner>
      <banner :message="info" kind="ok"></banner>

      <div class="card" v-if="scan">
        <h2>Library scan</h2>
        <p v-if="scan.running">
          <span class="badge warn">running</span> {{ scan.trigger }}<span v-if="scan.scope"> of {{ scan.scope }}</span>
          since {{ formatTime(scan.startedAt) }}: {{ scan.directories }} directories, {{ scan.audioFiles }} audio files
          ({{ scan.tracksRead }} read, {{ scan.tracksReused }} unchanged)
        </p>
        <p v-else class="muted">Idle. {{ scan.trackCount }} tracks; last completed scan {{ formatTime(scan.scannedAt) }}.</p>
        <p class="row">
          <label class="inline"><input type="checkbox" v-model="forceTags"> Re-read all tags</label>
          <span class="muted">Ignore the unchanged-file shortcut and re-read the tags of every file in scope (slower).</span>
        </p>
        <div class="row">
          <button type="button" class="primary" :disabled="busy || scan.running" @click="rescan(null)">Rescan the whole library</button>
          <form class="row" @submit.prevent="rescan(folderPath.trim())">
            <input v-model="folderPath" placeholder="root/directory" aria-label="Directory to rescan">
            <button type="submit" :disabled="busy || scan.running || !folderPath.trim()">Rescan directory</button>
          </form>
        </div>
        <div v-if="scan.last" class="last">
          <h3>Last scan</h3>
          <p>{{ scan.last.trigger }}<span v-if="scan.last.scope"> of {{ scan.last.scope }}</span>,
            {{ formatTime(scan.last.startedAt) }} to {{ formatTime(scan.last.finishedAt) }}:
            {{ scan.last.tracksBefore }} &rarr; {{ scan.last.tracksAfter }} tracks, {{ scan.last.tracksRead }} read,
            {{ scan.last.tracksReused }} unchanged, {{ scan.last.changedContainers }} containers changed.</p>
          <p v-if="scan.last.failure" class="banner error">The scan failed (catalog unchanged): {{ scan.last.failure }}</p>
        </div>
        <div v-if="scan.errors.length || (scan.last && scan.last.errors.length)">
          <h3>Scan errors</h3>
          <ul class="plain errors"><li v-for="(m, i) in (scan.running ? scan.errors : scan.last.errors)" :key="i">{{ m }}</li></ul>
        </div>
      </div>

      <div class="card">
        <h2>Export / import settings</h2>
        <p class="muted">Library settings: the settings.json of every directory (applied immediately, existing ones are overwritten).
          Server config: config.json without the admin password; written to the config directory, the previous file is kept as config.json.bak and the server must be restarted.
          Library roots and paths in the server config are machine specific: check them before importing on another host.</p>
        <div v-for="p in settingsParts" :key="p.id" class="row">
          <strong>{{ p.label }}</strong>
          <button type="button" :disabled="busy" @click="exportSettings(p.id)">Export</button>
          <label class="button"><input type="file" accept="application/json,.json" hidden :disabled="busy" @change="importSettings(p.id, $event)"> Import&hellip;</label>
        </div>
        <ul v-if="importFailures.length" class="plain errors"><li v-for="f in importFailures" :key="f.path">{{ f.path }}: {{ f.error }}</li></ul>
      </div>

      <div class="card">
        <h2>Invalid settings.json<span class="badge" :class="{ bad: invalid.length }">{{ invalid.length }}</span></h2>
        <p v-if="!invalid.length" class="muted">All settings.json files are valid.</p>
        <table v-else>
          <thead><tr><th>Directory</th><th>Problem</th></tr></thead>
          <tbody><tr v-for="f in invalid" :key="f.path">
            <td><router-link :to="{ path: '/settings', query: { p: f.path } }">{{ f.path }}</router-link></td><td>{{ f.error }}</td></tr></tbody>
        </table>
      </div>

      <div class="card">
        <h2>Unsupported files <span class="badge">{{ unsupported.length }}</span></h2>
        <p class="muted">Listed here, ignored by DLNA clients.</p>
        <div class="scroll" v-if="unsupported.length">
          <table>
            <thead><tr><th>File</th><th>Size</th><th>Reason</th></tr></thead>
            <tbody><tr v-for="u in unsupported" :key="u.path"><td>{{ u.path }}</td><td>{{ formatSize(u.size) }}</td><td>{{ u.reason }}</td></tr></tbody>
          </table>
        </div>
      </div>

      <div class="card" v-if="streams">
        <h2>Active streams <span class="badge">{{ streams.streams.length }}</span></h2>
        <p v-if="!streams.streams.length" class="muted">No client is streaming.</p>
        <div class="scroll" v-else>
          <table>
            <thead><tr><th>Client</th><th>Track</th><th>Form</th><th>Since</th></tr></thead>
            <tbody><tr v-for="s in streams.streams" :key="s.id">
              <td>{{ s.client }}</td><td>{{ s.title || s.item }}<div class="muted small">{{ s.path }}</div></td>
              <td>{{ s.transcoded ? 'transcoded (' + s.variant + ')' : 'original' }}</td><td>{{ formatTime(s.startedAt) }}</td></tr></tbody>
          </table>
        </div>
        <p class="muted">
          Transcode cache:
          <span v-if="streams.transcodeCache">{{ streams.transcodeCache.entries }} entries, {{ formatSize(streams.transcodeCache.sizeBytes) }}</span>
          <span v-else>transcoding is off</span>
          &middot; prefetch jobs running: {{ streams.prefetchJobs }}
        </p>
      </div>

      <div class="card">
        <h2>Random N snapshots <span class="badge">{{ snapshots.length }}</span></h2>
        <p v-if="!snapshots.length" class="muted">No snapshots (they are created when a client browses a Random N container).</p>
        <div class="scroll" v-else>
          <table>
            <thead><tr><th>Directory</th><th>Client</th><th>N</th><th>Tracks</th><th>Last activity</th><th>Streams</th></tr></thead>
            <tbody><tr v-for="s in snapshots" :key="s.context">
              <td>{{ s.directory }}</td><td>{{ s.clientIp }}<div class="muted small">{{ s.clientAgent }}</div></td>
              <td>{{ s.requestedSize }}</td><td>{{ s.tracks }}</td><td>{{ formatTime(s.lastActivity) }}</td><td>{{ s.activeStreams }}</td></tr></tbody>
          </table>
        </div>
      </div>

      <div class="card" v-if="version">
        <h2>Version</h2>
        <dl class="kv">
          <dt>Version</dt><dd>{{ version.version }}</dd>
          <dt>Build commit</dt><dd>{{ version.commit }}</dd>
          <dt>Build time</dt><dd>{{ version.buildTime }}</dd>
          <dt>Java</dt><dd>{{ version.java }}</dd>
          <dt>Started</dt><dd>{{ formatTime(version.startedAt) }}</dd>
        </dl>
      </div>
    </section>`
};
