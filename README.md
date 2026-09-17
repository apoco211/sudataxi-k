# 수다택시 K - PWA v6 (탑승만 저장)

하차 후 콜 많은 곳 TOP3 + 바로 카카오내비 + 탑승 위치 3중 백업

## 깃허브 Pages 배포 방법

### 1. 새 Repo 만들기
- github.com/new 에서 `sudataxi-k` Repo 생성 (Public)

### 2. 이 폴더 파일 업로드
- 이 폴더의 모든 파일을 Repo root에 업로드:
  - index.html
  - manifest.json
  - sw.js
  - icon-192.png
  - icon-512.png
  - .nojekyll

### 3. Pages 활성화
- Repo > Settings > Pages
- Source: Deploy from a branch
- Branch: main / root
- Save → 1분 후 https://YOUR_USERNAME.github.io/sudataxi-k/ 에서 앱 실행

### 4. PWA 설치
- 핸드폰 크롬으로 접속 → 주소창 "설치" 또는 메뉴 "홈 화면에 추가"
- iPhone: 공유 버튼 → 홈 화면에 추가

## 기능
- 모드1: 나의 위수지역 (안양/군포/의왕/과천) TOP3
- 모드2: 시계외지역 → 집 방향 필터 + 거리 2개 표시
- 카드 터치 = 바로 카카오내비 (kakaonavi:// + fallback)
- 탑승 위치만 저장: 🟢 여기서 탑승! +1 → 폰 + 구글시트 + JSON 3중 백업
- V0 알고리즘: baseScore + timeBonus - distance + personalBonus

## 백업 시트
- https://docs.google.com/spreadsheets/d/1hta2VkSb9eC7OIQvVyQZjFgTBvjpENIDAWWwmBVckqc/edit
- Apps Script Web App으로 연결하면 자동 동기화 가능 (다음 단계)

## 다음 단계
- Apps Script 배포: 시트 > 확장 프로그램 > Apps Script에서 doPost로 call_logs append
- 도메인 연결: sudataxi-k.com

Made for drivers who count boardings only.
