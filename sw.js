
const CACHE_NAME = 'sudataxi-k-v7-fixed';
const urlsToCache = ['./','./index.html','./manifest.json'];

self.addEventListener('install', e=>{
  e.waitUntil(caches.open(CACHE_NAME).then(c=>c.addAll(urlsToCache)));
  self.skipWaiting();
});
self.addEventListener('activate', e=>{
  e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE_NAME).map(k=>caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener('fetch', e=>{
  e.respondWith(
    caches.match(e.request).then(r=>{
      if(r) return r;
      return fetch(e.request).then(res=>{
        if(e.request.url.includes('google')) return res;
        const clone=res.clone();
        caches.open(CACHE_NAME).then(c=>c.put(e.request,clone));
        return res;
      }).catch(()=>{
        if(e.request.destination==='document') return caches.match('./index.html');
      });
    })
  );
});
