/* Admin: user management and shared-playlist moderation (plan milestone 8). Logic: core/user-form.js. */
import { Api, session } from '../api.js';
import { toast } from '../state.js';
import { Icon } from '../components/icons.js';
import { formatTime } from '../core/format.js';
import {
  emptyUserForm, formFromUser, toggleRoot, validateUserForm, toCreateBody, toUpdateBody, inviteLink, inviteDaysLeft, userStatus
} from '../core/user-form.js';

/** Roots checkbox list shared by the create and edit forms. */
const RootsField = {
  name: 'RootsField',
  props: { modelValue: { type: Array, required: true }, roots: { type: Array, required: true }, mainRoot: String, error: String },
  emits: ['update:modelValue'],
  methods: {
    set(root, checked) { this.$emit('update:modelValue', toggleRoot(this.modelValue, root, checked, this.roots)); }
  },
  template: `
    <fieldset class="roots-field">
      <legend>{{ $t('users.allowedRoots') }}</legend>
      <label v-for="r in roots" :key="r" class="inline">
        <input type="checkbox" :checked="modelValue.includes(r)" @change="set(r, $event.target.checked)">
        {{ r }}<span v-if="r === mainRoot" class="badge">{{ $t('users.mainRoot') }}</span>
      </label>
      <p v-if="error" class="bad-text small" role="alert">{{ $t(error) }}</p>
    </fieldset>`
};

export const UsersPage = {
  name: 'UsersPage',
  components: { Icon, RootsField },
  setup() { return { session: session, formatTime: formatTime }; },
  data() {
    return {
      users: [], roots: [], mainRoot: '', loading: true, error: '',
      creating: false, form: null, errors: {}, busy: false,
      issued: null,          // {user, invite?, temporaryPassword?}: shown once after create / invite / reset
      editing: null,         // {id, user, form, errors}
      confirmDelete: null
    };
  },
  created() { this.load(); },
  computed: {
    usernames() { return this.users.map(u => u.username.toLowerCase()); },
    linkText() { return this.issued && this.issued.invite ? inviteLink(window.location.origin, this.issued.invite) : ''; },
    daysLeft() { return this.issued && this.issued.invite ? inviteDaysLeft(this.issued.invite) : 0; }
  },
  methods: {
    status(u) { return userStatus(u); },
    async load() {
      this.loading = true; this.error = '';
      try {
        const r = await Api.adminUsers();
        this.users = r.users; this.roots = r.roots; this.mainRoot = r.mainRoot;
      } catch (e) { this.error = e.message; } finally { this.loading = false; }
    },
    startCreate() {
      this.form = emptyUserForm(this.mainRoot);
      this.errors = {}; this.creating = true; this.issued = null;
    },
    async create() {
      this.errors = validateUserForm(this.form, { roots: this.roots, usernames: this.usernames, creating: true });
      if (Object.keys(this.errors).length) return;
      this.busy = true; this.error = '';
      try {
        const r = await Api.adminCreateUser(toCreateBody(this.form));
        this.issued = r;
        this.creating = false;
        await this.load();
      } catch (e) {
        if (e.code === 'user_exists') this.errors = { username: 'users.err.usernameTaken' };
        else this.error = e.message;
      } finally { this.busy = false; }
    },
    startEdit(u) { this.editing = { id: u.id, user: u, form: formFromUser(u), errors: {} }; this.issued = null; },
    async saveEdit() {
      const ed = this.editing;
      ed.errors = validateUserForm(ed.form, { roots: this.roots, creating: false });
      if (Object.keys(ed.errors).length) return;
      const body = toUpdateBody(ed.user, ed.form);
      if (!Object.keys(body).length) { this.editing = null; return; }
      this.busy = true; this.error = '';
      try {
        await Api.adminUpdateUser(ed.id, body);
        this.editing = null;
        toast(this.$t('users.saved'));
        await this.load();
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async setEnabled(u, enabled) {
      this.busy = true; this.error = '';
      try { await Api.adminUpdateUser(u.id, { enabled: enabled }); await this.load(); }
      catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async invite(u) {
      this.busy = true; this.error = '';
      try { this.issued = await Api.adminInvite(u.id); await this.load(); }
      catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async tempPassword(u) {
      this.busy = true; this.error = '';
      try { this.issued = await Api.adminTemporaryPassword(u.id); await this.load(); }
      catch (e) { this.error = e.message; } finally { this.busy = false; }
    },
    async remove(u) {
      this.busy = true; this.error = '';
      try {
        await Api.adminDeleteUser(u.id);
        this.confirmDelete = null;
        toast(this.$t('users.deleted', { name: u.username }));
        await this.load();
      } catch (e) { this.error = e.message; this.confirmDelete = null; } finally { this.busy = false; }
    },
    async copy(text, ref) {
      try {
        await navigator.clipboard.writeText(text);
        toast(this.$t('users.copied'));
      } catch (e) {
        const el = this.$refs[ref];
        if (el) { el.focus(); el.select(); }
        toast(this.$t('users.copyManually'));
      }
    }
  },
  template: `
    <section class="admin-users">
      <header class="page-head"><h1>{{ $t('nav.adminUsers') }}</h1>
        <button type="button" class="primary" :disabled="creating" @click="startCreate"><icon name="add"></icon> {{ $t('users.new') }}</button></header>
      <p v-if="error" class="banner error" role="alert">{{ error }}</p>

      <div v-if="issued" class="card issued" role="status">
        <h2>{{ $t('users.issuedFor', { name: issued.user.username }) }}</h2>
        <template v-if="issued.invite">
          <p>{{ $t('users.inviteHelp') }}</p>
          <div class="row"><input ref="link" class="grow mono" readonly :value="linkText" :aria-label="$t('users.inviteLink')" @focus="$event.target.select()">
            <button type="button" class="primary" @click="copy(linkText, 'link')"><icon name="copy"></icon> {{ $t('users.copy') }}</button></div>
          <p class="muted small">{{ $tn('users.expiresIn', daysLeft) }} {{ $t('users.shownOnce') }}</p>
        </template>
        <template v-if="issued.temporaryPassword">
          <p>{{ $t('users.tempHelp') }}</p>
          <div class="row"><input ref="temp" class="grow mono" readonly :value="issued.temporaryPassword" :aria-label="$t('users.tempPassword')" @focus="$event.target.select()">
            <button type="button" class="primary" @click="copy(issued.temporaryPassword, 'temp')"><icon name="copy"></icon> {{ $t('users.copy') }}</button></div>
          <p class="muted small">{{ $t('users.tempOnce') }}</p>
        </template>
        <button type="button" @click="issued = null">{{ $t('common.dismiss') }}</button>
      </div>

      <form v-if="creating" class="card stack" @submit.prevent="create" novalidate>
        <h2>{{ $t('users.new') }}</h2>
        <label>{{ $t('users.username') }}
          <input v-model="form.username" autocomplete="off" autocapitalize="none" maxlength="64" :aria-invalid="!!errors.username">
          <span v-if="errors.username" class="bad-text small" role="alert">{{ $t(errors.username) }}</span></label>
        <label>{{ $t('users.displayName') }}
          <input v-model="form.displayName" maxlength="100" autocomplete="off">
          <span v-if="errors.displayName" class="bad-text small" role="alert">{{ $t(errors.displayName) }}</span></label>
        <label>{{ $t('users.role') }}
          <select v-model="form.role"><option value="user">{{ $t('role.user') }}</option><option value="admin">{{ $t('role.admin') }}</option></select></label>
        <roots-field v-if="form.role !== 'admin'" v-model="form.allowedRoots" :roots="roots" :main-root="mainRoot" :error="errors.allowedRoots"></roots-field>
        <p v-else class="muted small">{{ $t('users.adminAllRoots') }}</p>
        <label class="inline"><input type="checkbox" v-model="form.downloadAllowed"> {{ $t('users.download') }}</label>
        <fieldset>
          <legend>{{ $t('users.credential') }}</legend>
          <label class="inline"><input type="radio" value="invite" v-model="form.credential"> {{ $t('users.credInvite') }}</label>
          <label class="inline"><input type="radio" value="temporaryPassword" v-model="form.credential"> {{ $t('users.credTemp') }}</label>
        </fieldset>
        <div class="row">
          <button type="submit" class="primary" :disabled="busy">{{ $t('users.create') }}</button>
          <button type="button" @click="creating = false">{{ $t('common.cancel') }}</button>
        </div>
      </form>

      <p v-if="loading" class="muted">{{ $t('list.loading') }}</p>
      <div class="scroll"><table v-if="users.length" class="users-table">
        <thead><tr><th>{{ $t('users.username') }}</th><th>{{ $t('users.role') }}</th><th>{{ $t('users.status') }}</th>
          <th>{{ $t('users.allowedRoots') }}</th><th>{{ $t('users.downloadShort') }}</th><th>{{ $t('users.lastLogin') }}</th><th></th></tr></thead>
        <tbody>
          <template v-for="u in users" :key="u.id">
            <tr :class="{ disabled: !u.enabled }">
              <td><strong>{{ u.username }}</strong><div v-if="u.displayName && u.displayName !== u.username" class="muted small">{{ u.displayName }}</div></td>
              <td>{{ $t('role.' + u.role) }}</td>
              <td><span class="badge" :class="{ warn: status(u) === 'invited' || status(u) === 'mustChange', bad: status(u) === 'disabled' }">{{ $t('users.status.' + status(u)) }}</span></td>
              <td>{{ u.role === 'admin' ? $t('users.allRoots') : u.allowedRoots.join(', ') }}</td>
              <td>{{ u.role === 'admin' || u.downloadAllowed ? $t('common.yes') : $t('common.no') }}</td>
              <td>{{ u.lastLogin ? formatTime(u.lastLogin) : '' }}</td>
              <td class="actions">
                <button type="button" :disabled="busy" @click="startEdit(u)">{{ $t('common.edit') }}</button>
                <button v-if="u.enabled" type="button" :disabled="busy || u.id === session.id" @click="setEnabled(u, false)">{{ $t('users.disable') }}</button>
                <button v-else type="button" :disabled="busy" @click="setEnabled(u, true)">{{ $t('users.enable') }}</button>
                <button type="button" :disabled="busy || u.id === session.id" @click="invite(u)">{{ $t(u.pendingInvite ? 'users.newInvite' : 'users.resetInvite') }}</button>
                <button type="button" :disabled="busy || u.id === session.id" @click="tempPassword(u)">{{ $t('users.tempPassword') }}</button>
                <template v-if="confirmDelete === u.id">
                  <span>{{ $t('users.confirmDelete', { name: u.username }) }}</span>
                  <button type="button" class="danger" :disabled="busy" @click="remove(u)">{{ $t('common.delete') }}</button>
                  <button type="button" @click="confirmDelete = null">{{ $t('common.cancel') }}</button>
                </template>
                <button v-else type="button" :disabled="busy || u.id === session.id" @click="confirmDelete = u.id">{{ $t('common.delete') }}</button>
              </td>
            </tr>
            <tr v-if="editing && editing.id === u.id" class="edit-row">
              <td colspan="7">
                <form class="stack" @submit.prevent="saveEdit" novalidate>
                  <label>{{ $t('users.displayName') }}<input v-model="editing.form.displayName" maxlength="100"></label>
                  <label>{{ $t('users.role') }}
                    <select v-model="editing.form.role" :disabled="u.id === session.id">
                      <option value="user">{{ $t('role.user') }}</option><option value="admin">{{ $t('role.admin') }}</option></select></label>
                  <roots-field v-if="editing.form.role !== 'admin'" v-model="editing.form.allowedRoots" :roots="roots" :main-root="mainRoot" :error="editing.errors.allowedRoots"></roots-field>
                  <label class="inline"><input type="checkbox" v-model="editing.form.downloadAllowed"> {{ $t('users.download') }}</label>
                  <div class="row"><button type="submit" class="primary" :disabled="busy">{{ $t('common.save') }}</button>
                    <button type="button" @click="editing = null">{{ $t('common.cancel') }}</button></div>
                </form>
              </td>
            </tr>
          </template>
        </tbody>
      </table></div>
    </section>`
};

export const SharedModerationPage = {
  name: 'SharedModerationPage',
  components: { Icon },
  setup() { return { formatTime: formatTime }; },
  data() { return { lists: [], loading: true, error: '', busy: false, confirm: null }; },
  created() { this.load(); },
  methods: {
    key(p) { return p.owner.id + '/' + p.id; },
    async load() {
      this.loading = true; this.error = '';
      try { this.lists = (await Api.adminSharedPlaylists()).playlists; }
      catch (e) { this.error = e.message; } finally { this.loading = false; }
    },
    async run(p, action) {
      this.busy = true; this.error = '';
      try {
        if (action === 'unshare') await Api.adminUnshare(p.owner.id, p.id); else await Api.adminDeleteShared(p.owner.id, p.id);
        this.confirm = null;
        toast(this.$t(action === 'unshare' ? 'moderation.unshared' : 'moderation.deleted', { name: p.name }));
        await this.load();
      } catch (e) { this.error = e.message; this.confirm = null; } finally { this.busy = false; }
    }
  },
  template: `
    <section>
      <header class="page-head"><h1>{{ $t('nav.adminShared') }}</h1></header>
      <p class="muted">{{ $t('moderation.help') }}</p>
      <p v-if="error" class="banner error" role="alert">{{ error }}</p>
      <p v-if="loading" class="muted">{{ $t('list.loading') }}</p>
      <p v-else-if="!lists.length" class="muted">{{ $t('moderation.none') }}</p>
      <div class="scroll"><table v-if="lists.length">
        <thead><tr><th>{{ $t('moderation.name') }}</th><th>{{ $t('moderation.owner') }}</th><th>{{ $t('moderation.tracks') }}</th><th>{{ $t('moderation.updated') }}</th><th></th></tr></thead>
        <tbody>
          <tr v-for="p in lists" :key="key(p)">
            <td><strong>{{ p.name }}</strong><div v-if="p.description" class="muted small">{{ p.description }}</div></td>
            <td>{{ p.owner.displayName || p.owner.username }}<div class="muted small">{{ p.owner.username }}</div></td>
            <td>{{ p.count }}</td>
            <td>{{ formatTime(p.updated) }}</td>
            <td class="actions">
              <template v-if="confirm && confirm.key === key(p)">
                <span>{{ $t(confirm.action === 'unshare' ? 'moderation.confirmUnshare' : 'moderation.confirmDelete', { name: p.name }) }}</span>
                <button type="button" class="danger" :disabled="busy" @click="run(p, confirm.action)">{{ $t('common.confirm') }}</button>
                <button type="button" @click="confirm = null">{{ $t('common.cancel') }}</button>
              </template>
              <template v-else>
                <button type="button" :disabled="busy" @click="confirm = { key: key(p), action: 'unshare' }">{{ $t('moderation.unshare') }}</button>
                <button type="button" :disabled="busy" @click="confirm = { key: key(p), action: 'delete' }">{{ $t('common.delete') }}</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table></div>
    </section>`
};
