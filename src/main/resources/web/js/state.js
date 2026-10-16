/* Shared UI state and the app-wide event bus. */
import { reactive } from './vue.js';
import { createEmitter } from './core/events.js';

export const ui = reactive({
  sidebarOpen: false,     // phone: the sidebar is a drawer
  queueOpen: false,       // Now playing panel
  fullscreen: false,      // full-screen player
  fullTab: 'playing',     // its tab: playing | info | lyrics (back to playing when it closes)
  shortcutsOpen: false,
  menu: null,             // {x, y, subject}: the track/album/folder action menu
  lyrics: null,           // a track whose lyrics are shown in a dialog
  info: null,             // a track shown in the info dialog
  addTo: null,            // {title, tracks(): Promise<track[]>}: the "add to playlist" dialog
  consent: null,          // {title, lines, confirmLabel, resolve}: the typed-consent dialog (see askConsent)
  toast: ''
});

/** App events: 'navigate' ... Extension code may add its own. */
export const bus = createEmitter();

/** Set by main.js once the router exists, so non-component code can navigate. */
export const nav = { router: null };

let toastTimer = null;
export function toast(message) {
  ui.toast = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { ui.toast = ''; }, 3000);
}

/**
 * Opens the consent dialog: resolves true only when the user typed the exact phrase and confirmed, false otherwise.
 * `lines` are the facts shown above the text box (counts, what is removed); `confirmLabel` is the button text.
 */
export function askConsent(opts) {
  return new Promise(resolve => {
    if (ui.consent && ui.consent.resolve) ui.consent.resolve(false);
    ui.consent = { title: opts.title, lines: opts.lines || [], confirmLabel: opts.confirmLabel, resolve: resolve };
  });
}

/** The configured tag navigations (names), used to build the sidebar and the navigation pages. */
export const navs = reactive({ items: [], loaded: false });
