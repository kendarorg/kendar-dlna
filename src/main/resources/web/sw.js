/*
 * Minimal service worker: makes the app installable and lets the shell (HTML, CSS, JS, icons) start when the
 * network is flaky. It never touches /api/ (data, artwork, audio streams) and never caches audio or range requests.
 */
const CACHE = 'kd-shell-v2';

self.addEventListener('install', () => { self.skipWaiting(); });

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.indexOf('/api/') >= 0 || req.headers.has('range')) return;
  if (req.destination === 'audio' || req.destination === 'video' || req.destination === 'image' && url.pathname.indexOf('/icons/') < 0) return;
  event.respondWith((async () => {
    try {
      const fresh = await fetch(req);
      if (fresh.ok && fresh.type === 'basic') {
        const copy = fresh.clone();
        caches.open(CACHE).then(c => c.put(req, copy)).catch(() => {});
      }
      return fresh;
    } catch (e) {
      const cached = await caches.match(req);
      if (cached) return cached;
      throw e;
    }
  })());
});
