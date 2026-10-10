/* Admin user form: validation, request bodies, invite links. Pure functions. */

export const USERNAME_PATTERN = /^[A-Za-z0-9._@-]{2,64}$/;
export const MAX_DISPLAY_NAME = 100;
export const CREDENTIALS = ['invite', 'temporaryPassword'];
export const ROLES = ['user', 'admin'];

/** Empty create form: the main root is preselected. */
export function emptyUserForm(mainRoot) {
  return {
    username: '', displayName: '', role: 'user',
    allowedRoots: mainRoot ? [mainRoot] : [],
    downloadAllowed: false, credential: 'invite'
  };
}

/** Form for editing an existing user object. */
export function formFromUser(user) {
  return {
    username: user.username, displayName: user.displayName || '', role: user.role,
    allowedRoots: (user.allowedRoots || []).slice(), downloadAllowed: !!user.downloadAllowed,
    enabled: user.enabled !== false
  };
}

/** Checks or unchecks a root, keeping the order of the server's root list. */
export function toggleRoot(selected, root, checked, allRoots) {
  const set = new Set(selected);
  if (checked) set.add(root); else set.delete(root);
  const known = (allRoots || []).filter(r => set.has(r));
  const extra = Array.from(set).filter(r => !(allRoots || []).includes(r));
  return known.concat(extra);
}

/**
 * Problems of a form as {field: i18nKey}; empty object = valid.
 * opts: {roots (known roots), usernames (existing, lower case; create only), creating}
 */
export function validateUserForm(form, opts) {
  const o = opts || {};
  const errors = {};
  if (o.creating !== false) {
    const name = (form.username || '').trim();
    if (!name) errors.username = 'users.err.usernameRequired';
    else if (!USERNAME_PATTERN.test(name)) errors.username = 'users.err.usernameInvalid';
    else if ((o.usernames || []).includes(name.toLowerCase())) errors.username = 'users.err.usernameTaken';
  }
  if ((form.displayName || '').length > MAX_DISPLAY_NAME) errors.displayName = 'users.err.displayNameLong';
  if (!ROLES.includes(form.role)) errors.role = 'users.err.role';
  if (form.role !== 'admin') {
    const roots = form.allowedRoots || [];
    if (!roots.length) errors.allowedRoots = 'users.err.rootsRequired';
    else if (o.roots && roots.some(r => !o.roots.includes(r))) errors.allowedRoots = 'users.err.rootUnknown';
  }
  if (form.credential !== undefined && !CREDENTIALS.includes(form.credential)) errors.credential = 'users.err.credential';
  return errors;
}

/** Body of POST /admin/users. */
export function toCreateBody(form) {
  const body = {
    username: form.username.trim(),
    role: form.role,
    allowedRoots: form.role === 'admin' ? [] : form.allowedRoots.slice(),
    downloadAllowed: !!form.downloadAllowed,
    credential: form.credential
  };
  const display = (form.displayName || '').trim();
  if (display) body.displayName = display;
  return body;
}

/** Body of PUT /admin/users/{id}: only what differs from the user object ({} when nothing). */
export function toUpdateBody(user, form) {
  const body = {};
  const display = (form.displayName || '').trim();
  if (display !== (user.displayName || '')) body.displayName = display;
  if (form.role !== user.role) body.role = form.role;
  const before = (user.allowedRoots || []).slice().sort().join('\n');
  const after = (form.allowedRoots || []).slice().sort().join('\n');
  if (before !== after) body.allowedRoots = form.allowedRoots.slice();
  if (!!form.downloadAllowed !== !!user.downloadAllowed) body.downloadAllowed = !!form.downloadAllowed;
  if (form.enabled !== undefined && !!form.enabled !== (user.enabled !== false)) body.enabled = !!form.enabled;
  return body;
}

/** Absolute invite link from the invite object ({path: "/app/#/invite/<token>"}). */
export function inviteLink(origin, invite) {
  if (!invite) return '';
  const path = invite.path || ('/app/#/invite/' + invite.token);
  return String(origin || '').replace(/\/+$/, '') + (path.startsWith('/') ? path : '/' + path);
}

/** Whole days left until the invite expires (0 when expired or unknown). */
export function inviteDaysLeft(invite, nowMs) {
  if (!invite || !invite.expiresAt) return 0;
  const left = Date.parse(invite.expiresAt) - (nowMs === undefined ? Date.now() : nowMs);
  return left > 0 ? Math.ceil(left / 86400000) : 0;
}

/** Account state shown in the users table. */
export function userStatus(user) {
  if (!user.enabled) return 'disabled';
  if (user.pendingInvite) return 'invited';
  if (user.mustChange) return 'mustChange';
  return 'active';
}
