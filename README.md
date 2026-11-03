# 어디댔지

지하 주차장 몇 층에 세웠는지 기압계로 계산해서 기록하고, 가족과 공유하는 앱.

```
Eodi/
├─ server/    M6에 올릴 가족 공유 서버 (Docker)
├─ android/   갤럭시 앱 (Kotlin · Jetpack Compose)
└─ ios/       아이폰 앱 (SwiftUI)
```

## 동작 방식

1. **운전 시작** — 차 블루투스(갤럭시) / 단축어 자동화(아이폰)로 자동 시작
2. 지상에서 GPS가 잘 잡히는 동안 "기준점"(위치 + 기압)을 계속 갱신
   → 주차장에 들어가 GPS가 나빠지면 입구 직전 값이 남음
3. **주차 기록** — 연결이 끊기면 지금 기압과 기준 기압 차이 → 높이(m) → 층
4. 서버에 올리면 **가족 모두의 안드로이드 폰에 푸시 알림**(FCM). 누르면 그 주차 화면이 열림
5. 틀리면 "층 보정" → 그 주차장(반경 120m)의 층 높이를 학습, 가족과 공유
6. 기둥 사진을 찍으면 글자 인식으로 층(B2, 지하2층)과 구역(A-12)을 읽음

차는 고유 ID로 구분해서 **이름을 바꿔도** 기록·블루투스 연결·단축어·알림이 그대로 이어집니다.

---

## 1단계: Firebase 프로젝트 만들기 (안드로이드 푸시용, 무료)

1. https://console.firebase.google.com → 프로젝트 추가 (이름 예: eodi, 애널리틱스 꺼도 됨)
2. **안드로이드 앱 추가** → 패키지 이름 `kr.hanidayo.eodi`
3. `google-services.json` 내려받기 → `android/app/` 폴더에 넣기
4. 프로젝트 설정 › **서비스 계정** › "새 비공개 키 생성" → JSON 파일 내려받기
   → 이름을 `firebase-service-account.json`으로 바꿔서 서버의 `data/` 폴더에 넣기 (2단계)

> 서비스 계정 키는 비밀번호와 같습니다. 서버 밖으로 돌아다니지 않게 하세요.

## 2단계: M6에 서버 올리기

```bash
# M6에서
scp -r server m6:~/eodi        # 또는 git으로 옮기기
cd ~/eodi
cp .env.example .env
openssl rand -base64 18        # 나온 값을 .env의 FAMILY_TOKEN에
nano .env
mkdir -p data
cp ~/Downloads/firebase-service-account.json data/
docker compose up -d --build
curl http://127.0.0.1:45900/api/health   # {"ok":true, ..., "fcm":true} 이면 성공
```

`data/` 폴더 소유자가 컨테이너 사용자(uid 1000)와 다르면 `sudo chown -R 1000 data`.

### Cloudflare Tunnel 연결

아나 서버와 같은 터널에 호스트 하나만 추가하면 됩니다.

- **대시보드로 관리하는 터널**: Zero Trust › Networks › Tunnels › 해당 터널 › Public Hostname 추가
  - Subdomain `eodi`, Domain `hanidayo.kr`, Service `http://127.0.0.1:45900`
- **config.yml로 관리하는 터널**: `ingress:`의 마지막 `http_status:404` 줄 **위에** 추가 후 `sudo systemctl restart cloudflared`
  ```yaml
    - hostname: eodi.hanidayo.kr
      service: http://127.0.0.1:45900
  ```
  그리고 `cloudflared tunnel route dns <터널이름> eodi.hanidayo.kr`

확인: 휴대폰 브라우저에서 `https://eodi.hanidayo.kr/api/health`

### 백업

`~/eodi/data/` 하나만 백업하면 됩니다(DB + 사진 + Firebase 키). NAS로 주기 복사 권장.

## 빌드 (맥에서 한 번에)

```bash
cd ~/Desktop/"아이폰 주차 위치"/Eodi
bash build.sh check     # 필요한 도구(Xcode, xcodegen, JDK, Android SDK) 점검 + 설치 방법 안내
bash build.sh           # 안드로이드 APK + 아이폰 설치
bash build.sh android   # → 어디댔지-debug.apk
bash build.sh ios       # USB로 연결한 아이폰에 설치 (Xcode에 Apple ID 로그인 필요, 무료 계정 OK)
```

로그는 `build-logs/`에 남습니다. 무료 Apple ID로 설치한 앱은 7일 뒤 만료되니 `bash build.sh ios`로 다시 설치하세요.

## 3단계: 갤럭시 앱 설치

1. Android Studio(최신)에서 `android/` 폴더 열기
2. `android/app/google-services.json`을 넣으면 푸시가 켜짐 (없어도 빌드되고, 푸시만 꺼짐)
3. 폰: 설정 › 휴대전화 정보 › 소프트웨어 정보 › 빌드번호 7번 탭 → 개발자 옵션 › USB 디버깅 켜기
4. USB로 연결 → ▶ 실행
5. 가족 폰에 줄 APK: Build › Build APK(s) → `app/build/outputs/apk/debug/app-debug.apk`를 카톡 등으로 전달

### 첫 실행 설정

1. 권한: 위치(정확한 위치) · 근처 기기 · 알림 허용 → 홈 카드의 **권한 허용**으로 위치를 **항상 허용**으로
2. **배터리 최적화 해제** (중요, 갤럭시는 이걸 안 하면 블루투스 자동 기록이 늦거나 빠질 수 있음)
   - 설정 › 애플리케이션 › 어디댔지 › 배터리 › **제한 없음**
3. 앱 설정 › 가족 공유: 서버 주소 `eodi.hanidayo.kr`, 가족 코드, 내 이름 → **연결 확인** → **알림 테스트**
4. 앱 설정 › 차 › 각 차를 눌러 **블루투스 기기 지정** (차 오디오 이름 선택)

이후는 시동 걸면 기록 시작(상단에 "운전 기록 중" 알림), 시동 끄면 주차 기록 + 가족 알림.

## 4단계: 아이폰 앱 (공유만, 알림은 나중에)

```bash
brew install xcodegen
cd ios && xcodegen && open Eodi.xcodeproj
```

Signing에서 팀 선택 → 실행. 앱 설정 › 가족 공유에 같은 주소·코드 입력.
차 목록은 서버 것으로 맞춰지고, 단축어 자동화에서 **차를 목록에서 선택**합니다(설정 › 단축어 자동화 연결 방법).
아이폰은 지금은 푸시가 없고 앱을 열 때 최신 기록을 받아옵니다.
임시로 알림을 받고 싶으면 서버 `.env`의 `NTFY_TOPIC`을 채우고 아이폰에 ntfy 앱으로 구독하면 됩니다.

---

## 서버 API

모든 요청에 `Authorization: Bearer <가족 코드>` (health 제외)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /api/health | 상태, FCM 켜짐 여부 |
| GET | /api/state | 차 목록 + 차별 마지막 주차 + 학습된 주차장 |
| PUT / DELETE | /api/cars/{id} | 차 추가·수정(이름, 색) / 삭제 |
| POST | /api/parkings | 주차 기록 (새 기록이면 가족 전원 푸시) |
| PATCH | /api/parkings/{id} | 층 보정·기둥 번호 (바뀌면 조용한 알림) |
| PUT / GET | /api/parkings/{id}/photo | 기둥 사진 (JPEG) |
| PUT / DELETE | /api/garages/{id} | 주차장 학습값 |
| PUT | /api/devices | FCM 토큰 등록 |
| POST | /api/test-push | 테스트 알림 |

알림은 차별로 묶여서(같은 차의 새 알림이 이전 것을 대체) 쌓이지 않습니다.

## 조정할 만한 값

| 값 | 위치 | 기본 |
|---|---|---|
| 기준점으로 인정할 GPS 정확도 | DriveService / DriveRecorder `GOOD_ACCURACY` | 25m |
| 기준 기압을 앞당기는 시간 | `LOOKBACK` | 5초 |
| 같은 주차장 반경 | Store / ParkingStore | 120m |
| 기본 층 높이 | 앱 설정 | 3.3m |

## 알려진 한계

- 빌드 에러는 `build-logs/`의 로그를 보면 됩니다.
- 지상 주차 타워처럼 GPS가 안 끊기는 곳은 층이 작게 나올 수 있음 → 보정으로 학습
- 갤럭시 A 등 기압계 없는 폰은 위치와 사진만 기록
