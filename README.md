# 수다택시 K v18 - 네비 직접 연결 최종

## v18 핵심 - 카카오내비 직접 (맵 아님)
- iPhone "유효하지 않은 URL" 해결: iframe → <a> 태그 click() 방식
  const a = document.createElement('a');
  a.href = `kakaonavi://navigate?name=...&x=...&y=...`;
  a.target = '_blank';
  a.click();
- App Store 자동 이동 삭제, 팝업 삭제
- 버튼: [카카오내비로 가기] 하나만 (네비 직접)
- Android: intent:// 그대로

## 모드 로직 (유지)
- 모드1 = 위수지역 안에서 콜 많은 곳 (안양 10km 이내 30곳만, 수원에서 군포/금정이 가장 가까운 곳)
- 모드2 = 복귀 경로 (cur+spot_home <= cur_home*1.3 AND spot_home < cur_home = +30)
- 거리 표시: "가장 가까운 곳: 군포역 11km" / "현재서 2.8km · 군포역까지 5.4km"
- 나머지 절대 안바꿈

## 배포
1. github.com/new → sudataxi-k Public
2. 6개 파일 업로드
3. Settings > Pages > main / root
4. iPhone 사파리 → https://YOUR_ID.github.io/sudataxi-k/ → 공유 → 홈 화면에 추가
