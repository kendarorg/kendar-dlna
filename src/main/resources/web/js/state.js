/* Shared UI state and the app-wide event bus. */
import { reactive } from './vue.js';
import { createEmitter } from './core/events.js';

export const ui = reactive({
  sidebarOpen: false,     // phone: the sidebar is a drawer
  queueOpen: false,       // Now playing panel
  fullscreen: false,      // full-screen player
  shortcutsOpen: false,
  menu: null,             // {x, y, subject}: the track/album/folder action menu
  info: null,             // a track shown in the info dialog
  addTo: null,            // {title, tracks(): Promise<track[]>}: the "add to playlist" dialog
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
