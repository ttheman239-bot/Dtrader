// Offline cache: core files are precached on install, avatars are cached the
// first time they are used. Videos are fetched whole by app.js (blob URLs),
// so no Range-request handling is needed here.
const CACHE = 'dh-lite-v1';
const CORE = [
  './', 'index.html', 'style.css', 'app.js', 'manifest.webmanifest',
  'icons/icon-192.png', 'icons/icon-512.png',
  'DHLiveMini.wasm', 'js/DHLiveMini.js', 'js/MiniLive2.js', 'js/MiniMateLoader.js', 'js/pako.min.js',
  'common/bs_texture_halfFace.png', 'common/test.wav', 'background/bg.mp4',
  'assets/01.mp4', 'assets/combined_data.json.gz',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(CORE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin || req.headers.has('range')) return;
  e.respondWith(
    caches.match(req, { ignoreSearch: true }).then((hit) => hit || fetch(req).then((res) => {
      if (res.ok && res.status === 200) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy));
      }
      return res;
    }))
  );
});
