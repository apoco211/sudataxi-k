# 수다택시 K v17 - 최종 배포

## v17 수정
### 1. 아이폰 "유효하지 않은 URL" 해결
- 전: kakaonavi://navigate?... → PWA에서 차단되어 URL 오류
- 후: iPhone은 https://map.kakao.com/link/to/NAME,LAT,LNG 유니버설 링크 (100% 동작)
  - 카카오맵 앱 열림 → 길안내 탭 → 카카오내비로 목적지 전달 (2탭)
  - Android는 intent://... 그대로 바로 내비
- 버튼: [길안내 - 카카오맵으로 열기 (내비 연결)] / [카카오내비로 가기]

### 2. 모드1/모드2 뒤바뀐 거 수정
- 모드1 = 위수지역 안에서 콜 많은 곳
  - 필터: 안양 중심 37.3922,126.9261에서 10km 이내 운영지역 30곳만 (평촌, 범계, 금정, 군포, 산본, 인덕원 등)
  - 수원에 있어도 수원역 안뜨고 군포역 11km, 금정역 12km부터 표시
- 모드2 = 복귀 경로에서 콜 많은 곳
  - 복귀 경로: cur_spot + spot_home <= cur_home*1.30 AND spot_home < cur_home = +30점
  - 필터: 복귀 경로 OR 현재 5km 이내
  - 예: 북수원 2.8km · 군포역까지 8km
- 거리 표시 간단: "가장 가까운 곳: 군포역 11km" / "현재서 2.8km · 군포역까지 5.4km"

## 배포
1. github.com/new → sudataxi-k Public
2. 6개 파일 업로드
3. Settings > Pages > main / root
4. iPhone 사파리 → https://YOUR_ID.github.io/sudataxi-k/ → 공유 → 홈 화면에 추가
