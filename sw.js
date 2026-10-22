// sudataxi-k PWA Service Worker v8.9.91 - Network-first for index.html to prevent old version
const CACHE_VERSION = 'v8.9.91';
const CACHE_NAME = `sudataxi-k-${CACHE_VERSION}`;
const STATIC_ASSETS = [
  './',
  './index.html',
  './manifest.json'
];

// Install - cache static but don't cache old index aggressively
self.addEventListener('install', (event) => {
  self.skipWaiting(); // 즉시 새 버전 활성화
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      // index.html은 네트워크 우선이라 여기서는 캐시 안 함 (항상 최신)
      return cache.addAll(STATIC_ASSETS.filter(u => !u.includes('index.html')));
    })
  );
});

// Activate - 이전 버전 캐시 전부 삭제 (이전 버전 뜨는 증상 근본 해결)
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then(keys => {
      return Promise.all(
        keys.map(key => {
          if (key.startsWith('sudataxi-k-') && key !== CACHE_NAME) {
            console.log('[SW] Deleting old cache:', key);
            return caches.delete(key);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

// Fetch - index.html은 반드시 네트워크 우선 (PWA가 이전 버전 뜨는 이유 해결)
self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // index.html 또는 루트 요청은 네트워크 우선
  if (url.pathname.endsWith('/') || url.pathname.endsWith('/index.html') || url.pathname === '/' || req.mode === 'navigate') {
    event.respondWith(
      fetch(req, { cache: 'no-store' })
        .then(res => {
          // 성공하면 캐시 갱신
          const clone = res.clone();
          caches.open(CACHE_NAME).then(cache => cache.put(req, clone));
          return res;
        })
        .catch(() => {
          // 오프라인이면 캐시에서
          return caches.match(req).then(cached => cached || caches.match('./index.html'));
        })
    );
    return;
  }

  // 나머지 정적 자산은 캐시 우선 + 네트워크 fallback
  event.respondWith(
    caches.match(req).then(cached => {
      if (cached) return cached;
      return fetch(req).then(res => {
        // 외부 API(kakao 등)는 캐시 안 함
        if (url.hostname.includes('kakao') || url.hostname.includes('openstreetmap') || url.hostname.includes('tile')) {
          return res;
        }
        const clone = res.clone();
        caches.open(CACHE_NAME).then(cache => cache.put(req, clone));
        return res;
      });
    })
  );
});

// 메시지 처리 (타이머 등 기존 기능 유지)
self.addEventListener('message', (event) => {
  const data = event.data || {};
  if (data.action === 'skipWaiting') {
    self.skipWaiting();
  }
  // 기존 타이머 로직이 있다면 여기에 유지
});
