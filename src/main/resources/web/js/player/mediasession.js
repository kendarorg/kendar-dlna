/* Media Session API: OS media controls, lock screen, media keys. Does nothing where unsupported. */
import { artUrl } from '../core/urls.js';

export function bindMediaSession(player, nav, MetadataCtor) {
  const n = nav || (typeof navigator !== 'undefined' ? navigator : null);
  const Meta = MetadataCtor || (typeof MediaMetadata !== 'undefined' ? MediaMetadata : null);
  if (!n || !('mediaSession' in n) || !n.mediaSession || !Meta) return false;
  const ms = n.mediaSession;

  function handler(action, fn) {
    try { ms.setActionHandler(action, fn); } catch (e) { /* action not supported by this browser */ }
  }

  handler('play', () => player.resume());
  handler('pause', () => player.pause());
  handler('previoustrack', () => player.prev());
  handler('nexttrack', () => player.next());
  handler('seekto', d => { if (d && typeof d.seekTime === 'number') player.seek(d.seekTime); });
  handler('seekbackward', d => player.seekBy(-((d && d.seekOffset) || 10)));
  handler('seekforward', d => player.seekBy((d && d.seekOffset) || 10));
  handler('stop', () => player.pause());

  player.events.on('trackstart', ({ track }) => {
    const artwork = track.art ? [
      { src: artUrl(track.art, 'small'), sizes: '160x160', type: 'image/jpeg' },
      { src: artUrl(track.art, 'medium'), sizes: '300x300', type: 'image/jpeg' },
      { src: artUrl(track.art, 'large'), sizes: '800x800', type: 'image/jpeg' }
    ] : [];
    try {
      ms.metadata = new Meta({
        title: track.title || track.name || '', artist: track.artist || '', album: track.album || '', artwork: artwork
      });
    } catch (e) { /* ignore */ }
  });

  player.events.on('state', ({ status }) => {
    try {
      ms.playbackState = status === 'playing' || status === 'loading' ? 'playing' : (status === 'idle' ? 'none' : 'paused');
    } catch (e) { /* ignore */ }
  });

  let lastPush = 0;
  player.events.on('time', ({ position, duration }) => {
    const now = Date.now();
    if (now - lastPush < 1000 || !duration || !ms.setPositionState) return;
    lastPush = now;
    try { ms.setPositionState({ duration: duration, position: Math.min(position, duration), playbackRate: player.rate || 1 }); } catch (e) { /* ignore */ }
  });
  return true;
}
