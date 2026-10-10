/* Login and change-password pages. */
import { Api, session } from '../api.js';

export const LoginPage = {
  name: 'LoginPage',
  data() { return { username: '', password: '', remember: true, error: '', busy: false }; },
  methods: {
    async submit() {
      this.busy = true; this.error = '';
      try {
        await Api.login(this.username, this.password, this.remember);
        this.password = '';
        this.$router.push(session.mustChange ? '/password' : '/');
      } catch (e) {
        this.error = e.code === 'too_many_attempts' ? e.message : (e.status === 401 ? 'Wrong user name or password.' : e.message);
      } finally { this.busy = false; }
    }
  },
  template: `
    <section class="card narrow">
      <h1>Sign in</h1>
      <form @submit.prevent="submit" class="stack">
        <label>User name <input v-model="username" autocomplete="username" required autofocus></label>
        <label>Password <input type="password" v-model="password" autocomplete="current-password" required></label>
        <banner :message="error"></banner>
        <label class="inline"><input type="checkbox" v-model="remember"> Keep me signed in on this device (30 days)</label>
        <button type="submit" class="primary" :disabled="busy">Sign in</button>
      </form>
      <p class="muted">The first password is printed once in the server log. Lost it? Create an empty file
        named <code>reset-password</code> in the config directory.</p>
    </section>`
};

export const PasswordPage = {
  name: 'PasswordPage',
  data() { return { current: '', next: '', repeat: '', error: '', done: false, busy: false, session: session }; },
  computed: {
    forced() { return this.session.mustChange; },
    tooShort() { return this.next.length > 0 && this.next.length < this.session.minPasswordLength; },
    mismatch() { return this.repeat.length > 0 && this.repeat !== this.next; },
    valid() { return this.current && this.next.length >= this.session.minPasswordLength && this.next === this.repeat; }
  },
  methods: {
    async submit() {
      this.busy = true; this.error = ''; this.done = false;
      try {
        await Api.changePassword(this.current, this.next);
        this.current = this.next = this.repeat = '';
        this.done = true;
        if (!session.mustChange) setTimeout(() => this.$router.push('/'), 800);
      } catch (e) { this.error = e.message; } finally { this.busy = false; }
    }
  },
  template: `
    <section class="card narrow">
      <h1>Change password</h1>
      <p v-if="forced" class="banner info">You must choose a new password before you can continue.</p>
      <form @submit.prevent="submit" class="stack">
        <label>Current password <input type="password" v-model="current" autocomplete="current-password" required></label>
        <label>New password <input type="password" v-model="next" autocomplete="new-password" required></label>
        <small v-if="tooShort" class="bad-text">At least {{ session.minPasswordLength }} characters.</small>
        <label>Repeat new password <input type="password" v-model="repeat" autocomplete="new-password" required></label>
        <small v-if="mismatch" class="bad-text">The passwords differ.</small>
        <banner :message="error"></banner>
        <banner v-if="done" message="Password changed. Other sessions were signed out." kind="ok"></banner>
        <button type="submit" class="primary" :disabled="busy || !valid">Change password</button>
      </form>
    </section>`
};

export const InvitePage = {
  name: 'InvitePage',
  data() { return { user: null, password: '', repeat: '', error: '', invalid: false, busy: false, session: session }; },
  computed: {
    token() { return this.$route.params.token; },
    tooShort() { return this.password.length > 0 && this.password.length < this.session.minPasswordLength; },
    mismatch() { return this.repeat.length > 0 && this.repeat !== this.password; },
    valid() { return this.password.length >= this.session.minPasswordLength && this.password === this.repeat; }
  },
  async created() {
    try { this.user = await Api.invite(this.token); } catch (e) { this.invalid = true; }
  },
  methods: {
    async submit() {
      this.busy = true; this.error = '';
      try {
        await Api.acceptInvite(this.token, this.password, true);
        this.password = this.repeat = '';
        this.$router.push('/');
      } catch (e) {
        this.error = e.message;
        if (e.code === 'invalid_invite') this.invalid = true;
      } finally { this.busy = false; }
    }
  },
  template: `
    <section class="card narrow">
      <h1>Choose your password</h1>
      <p v-if="invalid" class="banner bad">This invite link is invalid, already used or expired. Ask an administrator for a new one.</p>
      <form v-else-if="user" @submit.prevent="submit" class="stack">
        <p>Welcome, <strong>{{ user.displayName }}</strong> ({{ user.username }}).</p>
        <label>New password <input type="password" v-model="password" autocomplete="new-password" required autofocus></label>
        <small v-if="tooShort" class="bad-text">At least {{ session.minPasswordLength }} characters.</small>
        <label>Repeat password <input type="password" v-model="repeat" autocomplete="new-password" required></label>
        <small v-if="mismatch" class="bad-text">The passwords differ.</small>
        <banner :message="error"></banner>
        <button type="submit" class="primary" :disabled="busy || !valid">Set password and sign in</button>
      </form>
    </section>`
};

export const NoAccessPage = {
  name: 'NoAccessPage',
  template: `
    <section class="card narrow">
      <h1>No access</h1>
      <p>This page is for administrators.</p>
    </section>`
};
