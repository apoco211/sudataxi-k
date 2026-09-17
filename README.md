# 수다택시 K v11 - 아이폰 대응 최종

## iPhone에서 반드시 되게 수정
- Android: intent://navigate?name=...&x=...&y=...#Intent;scheme=kakaonavi;package=com.locnall.KimGiSa;end
- iPhone: kakaonavi://navigate?name=...&x=...&y=...&coord_type=wgs84 (사용자 터치로 호출)
- 앱 없으면 iPhone은 App Store https://apps.apple.com/app/kakao-navi/id304608425 로, Android는 Play Store로 안내
- 카카오내비만, 맵 fallback 없음

## 모드2: 복귀 경로 검색
- 현재 위치 + 위수지역(안양) 복귀 경로 주변 콜 많은 곳 검색
- 예: 수원 → 안양 복귀시 북수원, 성균관대역이 복귀 경로로 잡힘

## 배포
1. github.com/new → sudataxi-k Public
2. 6개 파일 업로드
3. Settings > Pages > main / root
4. https://YOUR_ID.github.io/sudataxi-k/ → 사파리에서 열기 → 공유 버튼 → 홈 화면에 추가 (iPhone PWA)

## iPhone 테스트
- 사파리에서 열고 위치 허용 → 모드2 → 주변 콜 많은 곳 찾기 → [카카오내비로 가기] 터치 → "카카오내비에서 열기" 허용
- PWA로 설치 후에도 동일하게 동작해야 함
