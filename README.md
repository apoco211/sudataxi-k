# 수다택시 K v16 - 최종 배포

## v16 핵심
- 팝업 "내비 다시 시도" 제거, App Store 자동 이동 제거
- 버튼: [카카오내비로 가기] + [맵] 백업
  - iPhone: kakaonavi://navigate?name=...&x=...&y=... (앱 설치 시 바로 열림)
  - Android: intent://...#Intent;scheme=kakaonavi;package=com.locnall.KimGiSa;end
  - 맵 백업: https://map.kakao.com/link/to/NAME,LAT,LNG (100% 동작)
- 거리 표시 간단화:
  - "위수지역 중 가장 가까운 곳" → "가장 가까운 곳: 군포역 8km"
  - 모드2: "현재서 2.8km · 군포역까지 5.4km" (수원에서 군포가 안양보다 가까우므로 군포까지 거리)
- 모드2 복귀 경로: cur+spot_home <= cur_home*1.30 AND spot_home < cur_home = +30점, return route OR within 5km
- 50곳 DB, 3중 백업

## 배포
1. github.com/new → sudataxi-k Public
2. 6개 파일 업로드
3. Settings > Pages > main / root
4. iPhone 사파리에서 https://YOUR_ID.github.io/sudataxi-k/ → 공유 → 홈 화면에 추가
