# 수다택시 K PWA v7 - 현재위치 + 모드2 수정 + 탑승 하단분리

## v7 수정사항 (사용자 피드백 반영)
1. 현재 위치 항상 표시: 위도/경도 + 안양까지 거리 + 위수지역 안/밖 + 갱신시간 + 다시찾기
2. 모드2 버그 수정: 모드1과 같은 장소 뜨던 버그 수정. 모드2는 귀가 방향 필터 (dist(spot,HOME) < dist(current,HOME)) + 귀가게이트 +30점, 반대방향 -50점. 카드에 거리 2개 표시
3. 탑승 버튼 하단 고정 분리: 카드에는 [선택→네비]만, 하단 고정 [🟢 탑승 위치 저장 (현재 위치)]로 실제 탑승한 현재 GPS 저장 (추천장소 아니어도 됨)

## 기능
- 모드1: 위수지역 안 TOP3 (노란 테두리)
- 모드2: 위수지역 밖 8km 이상 시 귀가 방향 TOP3 (파란 테두리 + 귀가 방향 배지 + 거리 2개)
- 카드 터치 = 바로 카카오내비 (kakaonavi:// + fallback)
- 탑승 저장 = 현재 위치 3중 백업 (폰 localStorage + 구글시트 + JSON)
- PWA: manifest + sw.js + 오프라인 TOP3 계산

## 배포
1. github.com/new → sudataxi-k Repo Public 생성
2. 이 폴더 6개 파일 업로드: index.html, manifest.json, sw.js, icon-192.png, icon-512.png, .nojekyll
3. Settings > Pages > Deploy from a branch > main / root > Save
4. https://YOUR_ID.github.io/sudataxi-k/ → 크롬에서 홈 화면에 추가

## 백업 시트
https://docs.google.com/spreadsheets/d/1hta2VkSb9eC7OIQvVyQZjFgTBvjpENIDAWWwmBVckqc/edit

## 알고리즘 V0
baseScore(70~85) + timeBonus(06-09역+15, 11-15병원/마트+10, 18-22술/맛집+20, 금토22-02술+25) + personalBonus(탑승횟수×5) - distance×10 + 모드2 방향보너스
