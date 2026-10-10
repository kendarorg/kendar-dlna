/* The player singleton: real audio engine, Vue reactivity, prefs in localStorage, Media Session, shortcuts. */
import { reactive } from '../vue.js';
import { Api } from '../api.js';
import { createPrefs } from '../core/prefs.js';
import { streamUrl } from '../core/urls.js';
import { detectFormats, negotiateFormats } from '../core/formats.js';
import { shortcutFor } from '../core/keys.js';
import { createEngine } from './engine.js';
import { createController } from './controller.js';
import { bindMediaSession } from './mediasession.js';
import { ui } from '../state.js';

function storage(kind) {
  try { return window[kind]; } catch (e) { return null; }
}

export const prefs = createPrefs(storage('localStorage'));
const engine = createEngine();

export const player = createController({
  engine, prefs, reactive,
  streamUrl: streamUrl,
  prefetch: path => Api.prefetch(path)
});

bindMediaSession(player);

/** Detects the playable formats and tells the server once per login session. Call after sign-in. */
export async function negotiate(sessionKey) {
  const probe = document.createElement('audio');
  const formats = await negotiateFormats({
    canPlay: m => probe.canPlayType(m),
    post: f => Api.postFormats(f),
    storage: storage('sessionStorage'),
    sessionKey: sessionKey
  });
  Api.formats = formats;
  return formats;
}

export function previewFormats() {
  const probe = document.createElement('audio');
  return detectFormats(m => probe.canPlayType(m));
}

/** Keyboard shortcuts (space, arrows, n/p, ...). */
export function installShortcuts(target) {
  target.addEventListener('keydown', ev => {
    const action = shortcutFor(ev);
    if (!action) return;
    const q = player.state.queue;
    switch (action) {
      case 'toggle': player.toggle(); break;
      case 'next': player.next(); break;
      case 'prev': player.prev(); break;
      case 'seekBack': player.seekBy(-5); break;
      case 'seekForward': player.seekBy(5); break;
      case 'volumeUp': player.setVolume(player.state.volume + 0.05); break;
      case 'volumeDown': player.setVolume(player.state.volume - 0.05); break;
      case 'mute': player.toggleMute(); break;
      case 'shuffle': player.toggleShuffle(); break;
      case 'repeat': player.cycleRepeat(); break;
      case 'queue': ui.queueOpen = !ui.queueOpen; break;
      case 'fullscreen': if (q.items.length) ui.fullscreen = !ui.fullscreen; break;
      case 'help': ui.shortcutsOpen = !ui.shortcutsOpen; break;
      default: return;
    }
    ev.preventDefault();
  });
}
