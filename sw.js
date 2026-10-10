// 백그라운드 타이머를 위한 Service Worker
// 파일명: sw.js
// 위치: index.html 파일과 동일한 디렉토리에 저장하세요.

let timerId = null;

self.addEventListener('install', (event) => {
    // 즉시 활성화
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

self.addEventListener('message', (event) => {
    if (event.data && event.data.action === 'startTimer') {
        const minutes = event.data.minutes || 5;
        const ms = minutes * 60 * 1000;
        
        // 기존 타이머가 있다면 취소
        if (timerId) {
            clearTimeout(timerId);
        }
        
        console.log(`[수다택시 SW] ${minutes}분 타이머가 백그라운드에 등록되었습니다.`);
        
        // 백그라운드 타이머 시작
        timerId = setTimeout(() => {
            self.registration.showNotification('⏱️ [수다택시 K] 대기 완료!', {
                body: `${minutes}분 대기가 종료되었습니다. 추천지로 이동하세요!`,
                icon: './icon-192.png',
                vibrate: [300, 150, 300, 150, 500, 200, 500],
                requireInteraction: true // 알림을 사용자가 닫기 전까지 화면에 유지
            });
            timerId = null;
        }, ms);
        
    } else if (event.data && event.data.action === 'stopTimer') {
        if (timerId) {
            clearTimeout(timerId);
            timerId = null;
            console.log('[수다택시 SW] 백그라운드 타이머가 취소되었습니다.');
        }
    }
});

// 알림을 클릭했을 때 수다택시 K 앱을 앞으로 띄우는 이벤트
self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    event.waitUntil(
        clients.matchAll({ type: 'window' }).then(windowClients => {
            // 이미 띄워진 수다택시 탭이 있으면 포커스
            for (let i = 0; i < windowClients.length; i++) {
                let client = windowClients[i];
                if (client.url.includes('sudataxi-k') && 'focus' in client) {
                    return client.focus();
                }
            }
            // 없으면 새로 엽니다
            if (clients.openWindow) {
                return clients.openWindow('/');
            }
        })
    );
});