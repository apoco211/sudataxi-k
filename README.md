# 수다택시 K v10 - 복귀 경로 검색 최종

## 핵심 수정
- 모드2: 현재 위치 + 위수지역(안양) 복귀 경로 주변에서 콜 많은 곳 검색
- 공식: cur_spot + spot_home <= cur_home*1.3 AND spot_home < cur_home = 복귀 경로 (+30점)
- 예: 수원 → 안양 복귀시 북수원, 성균관대역, 의왕역이 복귀 경로로, 인계동은 현재 주변으로 TOP3에 섞여 뜸
- 카카오내비만: kakaonavi://navigate (맵 fallback 없음, 앱 없으면 설치 안내)
- 현재 위치 항상 표시, 탑승 버튼 하단 고정 분리 (추천 장소 아닌 곳에서도 현재 GPS 저장)

## 배포
1. github.com/new → Repo sudataxi-k Public
2. 이 폴더 6개 파일 업로드: index.html, manifest.json, sw.js, icon-192.png, icon-512.png, .nojekyll
3. Settings > Pages > Deploy from branch > main / root > Save
4. https://YOUR_ID.github.io/sudataxi-k/ → 홈 화면에 추가

## 백업 시트
https://docs.google.com/spreadsheets/d/1hta2VkSb9eC7OIQvVyQZjFgTBvjpENIDAWWwmBVckqc/edit
