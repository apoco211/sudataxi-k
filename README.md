# 수다택시 K v13 - 아이폰 URL오류 최종 수정

## 핵심 수정
- iPhone: kakaonavi:// → https://kakaonavi.kakao.com/launch/?eName=...&eX=LNG&eY=LAT 유니버설 링크로 변경 (iOS 17 PWA 차단 해결)
- Android: intent://navigate?#Intent;scheme=kakaonavi;... 그대로
- 노란 설명 박스 제거, 토스트만 표시
- 모드2: 현재 위치 + 안양 복귀 경로 주변 콜 많은 곳 검색 (북수원, 성균관대역 등 복귀 경로 +30점)
- 카카오내비만 (맵 fallback 없음)

## 배포
1. github.com/new → sudataxi-k Public
2. 6개 파일 업로드
3. Settings > Pages > main / root
4. iPhone 사파리에서 https://YOUR_ID.github.io/sudataxi-k/ → 공유 → 홈 화면에 추가
