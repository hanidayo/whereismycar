#!/bin/bash
# 어디댔지 빌드 스크립트 (macOS)
#
#   bash build.sh            도구 점검 → 안드로이드 APK → 아이폰 설치
#   bash build.sh check      도구 점검만
#   bash build.sh android    안드로이드 디버그 APK만
#   bash build.sh ios        아이폰 빌드·설치만 (Xcode 서명, USB)
#   bash build.sh ipa        서명 안 된 Eodi.ipa 만들기 → Sideloadly로 설치 (서명·기기 연결 불필요)
#
# 아이폰 옵션(환경변수): TEAM_ID=XXXXXXXXXX  BUNDLE_ID=kr.hanidayo.eodi
# 로그는 build-logs/ 폴더에 남습니다.

set -o pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
LOGS="$ROOT/build-logs"
mkdir -p "$LOGS"
MODE="${1:-all}"
BUNDLE_ID="${BUNDLE_ID:-kr.hanidayo.eodi}"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }

MISSING=0
need() { red "  ✗ $1"; shift; for l in "$@"; do echo "      $l"; done; MISSING=1; }
ok()   { green "  ✓ $*"; }

# ------------------------------------------------------------------ 도구 찾기

find_java() {
    if [ -n "$JAVA_HOME" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])'; then
        echo "$JAVA_HOME"; return
    fi
    local h
    h=$(/usr/libexec/java_home -v 17+ 2>/dev/null) && { echo "$h"; return; }
    for h in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
             "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
             /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk@21; do
        [ -x "$h/bin/java" ] && { echo "$h"; return; }
    done
}

find_sdk() {
    local d
    for d in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$HOME/Library/Android/sdk" \
             /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
        [ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return; }
    done
}

find_sdkmanager() {
    local s
    for s in "$1/cmdline-tools/latest/bin/sdkmanager" /opt/homebrew/bin/sdkmanager /usr/local/bin/sdkmanager; do
        [ -x "$s" ] && { echo "$s"; return; }
    done
    ls "$1"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1
}

find_team() {
    [ -n "$TEAM_ID" ] && { echo "$TEAM_ID"; return; }
    local t key
    # Xcode › Settings › Accounts 에 로그인한 팀 (무료 계정은 "Personal Team")
    for key in IDEProvisioningTeamByIdentifier IDEProvisioningTeams; do
        t=$(defaults read com.apple.dt.Xcode "$key" 2>/dev/null | grep -oE 'teamID = "?[A-Z0-9]{10}' | head -1 | grep -oE '[A-Z0-9]{10}$')
        [ -n "$t" ] && { echo "$t"; return; }
    done
    # 한 번이라도 서명했으면 인증서에 팀 ID가 있음
    security find-certificate -a -c "Apple Development" -p 2>/dev/null \
        | openssl x509 -noout -subject 2>/dev/null | grep -oE 'OU *= *[A-Z0-9]{10}' | head -1 | grep -oE '[A-Z0-9]{10}$'
}

# ------------------------------------------------------------------ 점검

check_android() {
    bold "안드로이드 도구"
    JAVA_DIR=$(find_java)
    if [ -n "$JAVA_DIR" ]; then ok "JDK: $JAVA_DIR"
    else need "JDK 17 이상이 없어요" "brew install --cask zulu@17" "(또는 Android Studio를 설치하면 내장 JDK를 씁니다)"; fi

    SDK_DIR=$(find_sdk)
    if [ -n "$SDK_DIR" ]; then
        ok "Android SDK: $SDK_DIR"
        if [ ! -d "$SDK_DIR/licenses" ] || [ -z "$(ls "$SDK_DIR/licenses" 2>/dev/null)" ]; then
            local sm; sm=$(find_sdkmanager "$SDK_DIR")
            if [ -n "$sm" ] && [ -n "$JAVA_DIR" ]; then
                echo "    SDK 라이선스 동의 중…"
                yes | JAVA_HOME="$JAVA_DIR" "$sm" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1
            else
                need "SDK 라이선스 동의가 안 돼 있어요" "Android Studio › Settings › Android SDK 에서 Android 15(API 35) 설치"
            fi
        fi
    else
        need "Android SDK가 없어요. 둘 중 하나:" \
             "A) Android Studio 설치 → 처음 실행 마법사(Standard)로 SDK 설치  ← 쉬움" \
             "   brew install --cask android-studio" \
             "B) 명령줄만: brew install --cask android-commandlinetools" \
             "   yes | sdkmanager --licenses && sdkmanager \"platforms;android-35\" \"build-tools;35.0.0\" \"platform-tools\""
    fi
}

check_ios() {
    bold "아이폰 도구"
    local xp; xp=$(xcode-select -p 2>/dev/null)
    if [[ "$xp" == *Xcode*.app* ]] && xcodebuild -version >/dev/null 2>&1; then
        ok "$(xcodebuild -version | head -1) ($xp)"
        if ! xcrun --find devicectl >/dev/null 2>&1; then
            need "devicectl이 없어요 (Xcode 15 이상 필요)" "App Store에서 Xcode 업데이트"
        fi
    elif [ -d /Applications/Xcode.app ]; then
        need "Xcode는 있는데 명령줄 도구가 Xcode를 안 가리켜요" \
             "sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" \
             "sudo xcodebuild -license accept && xcodebuild -runFirstLaunch"
    else
        need "Xcode가 없어요" "App Store에서 Xcode 설치 (수십 GB) → 한 번 실행해서 iOS 플랫폼 설치" \
             "그다음: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"
    fi

    XCODEGEN=$(command -v xcodegen)
    local local_xg="$ROOT/.tools/xcodegen/bin/xcodegen"
    if [ -z "$XCODEGEN" ] && [ ! -x "$local_xg" ]; then
        echo "    xcodegen 내려받는 중 (GitHub 공식 릴리스)…"
        mkdir -p "$ROOT/.tools" && rm -rf "$ROOT/.tools/xcodegen"
        curl -fsSL -o "$ROOT/.tools/xcodegen.zip" \
            https://github.com/yonaskolb/XcodeGen/releases/latest/download/xcodegen.zip \
          && unzip -q -o "$ROOT/.tools/xcodegen.zip" -d "$ROOT/.tools" \
          && rm -f "$ROOT/.tools/xcodegen.zip"
        xattr -dr com.apple.quarantine "$ROOT/.tools/xcodegen" 2>/dev/null
    fi
    [ -z "$XCODEGEN" ] && [ -x "$local_xg" ] && XCODEGEN="$local_xg"
    if [ -n "$XCODEGEN" ] && "$XCODEGEN" --version >/dev/null 2>&1; then
        ok "xcodegen $("$XCODEGEN" --version 2>/dev/null | awk '{print $NF}')"
    else
        need "xcodegen을 준비하지 못했어요" "Homebrew 설치 후: brew install xcodegen"
    fi

    [ "$MODE" = ipa ] && return
    TEAM=$(find_team)
    if [ -n "$TEAM" ]; then ok "서명 팀: $TEAM"
    else need "Xcode에 Apple ID가 로그인돼 있지 않아요" \
              "Xcode › Settings(⌘,) › Accounts › + › Apple ID 로 로그인 (무료 계정 OK)" \
              "그래도 못 찾으면: TEAM_ID=팀ID bash build.sh ios"; fi
}

# ------------------------------------------------------------------ 안드로이드

build_android() {
    bold "▶ 안드로이드 디버그 APK 빌드"
    cd "$ROOT/android" || return 1
    [ -f app/google-services.json ] || echo "  (google-services.json 없음 → 푸시 없이 빌드)"
    echo "sdk.dir=$SDK_DIR" > local.properties
    chmod +x gradlew
    if JAVA_HOME="$JAVA_DIR" ./gradlew --no-daemon assembleDebug 2>&1 | tee "$LOGS/android.log"; then
        local apk=app/build/outputs/apk/debug/app-debug.apk
        cp "$apk" "$ROOT/어디댔지-debug.apk"
        green "✓ APK: $ROOT/어디댔지-debug.apk"
        return 0
    fi
    red "✗ 안드로이드 빌드 실패 → build-logs/android.log"
    return 1
}

# ------------------------------------------------------------------ 아이폰

pick_device() {
    # 출력: 상태<TAB>udid<TAB>이름<TAB>iOS버전   (상태: ok | notconnected | none)
    local json="$LOGS/devices.json"
    xcrun devicectl list devices --json-output "$json" >/dev/null 2>&1 || { printf 'none\n'; return; }
    /usr/bin/python3 - "$json" "${DEVICE:-}" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
want = sys.argv[2].lower()
phones = []
for dev in d.get("result", {}).get("devices", []):
    hp = dev.get("hardwareProperties", {})
    cp = dev.get("connectionProperties", {})
    dp = dev.get("deviceProperties", {})
    if hp.get("platform") != "iOS" or hp.get("reality") == "virtual":
        continue
    name = dp.get("name", "")
    if want:
        if want not in name.lower() and want != str(hp.get("udid", "")).lower():
            continue
    elif hp.get("deviceType") != "iPhone":
        continue
    connected = cp.get("transportType") == "wired" or cp.get("tunnelState") == "connected"
    phones.append((connected, hp.get("udid") or dev.get("identifier"), name, dp.get("osVersionNumber", "")))
if not phones:
    print("none")
else:
    phones.sort(key=lambda p: not p[0])
    c, udid, name, os_ = phones[0]
    print("ok" if c else "notconnected", udid, name, os_, sep="\t")
PY
}

build_ios() {
    bold "▶ 아이폰 빌드·설치"
    cd "$ROOT/ios" || return 1

    "$XCODEGEN" generate 2>&1 | tee "$LOGS/xcodegen.log" || { red "✗ xcodegen 실패"; return 1; }

    local line st udid name ios xmaj imaj
    IFS=$'\t' read -r st udid name ios <<< "$(pick_device)"
    if [ "$st" = none ]; then
        red "✗ 이 맥과 페어링된 아이폰이 없어요."
        echo "   케이블 연결 → 아이폰 잠금 해제 → '이 컴퓨터를 신뢰' → 다시 실행"
        return 1
    fi
    if [ "$st" = notconnected ]; then
        red "✗ $name 이(가) 지금 연결돼 있지 않아요."
        echo "   USB 케이블로 연결하고 아이폰 잠금을 푼 뒤 다시 실행하세요."
        echo "   (다른 기기를 쓰려면: DEVICE=\"기기 이름\" bash build.sh ios)"
        return 1
    fi
    ok "기기: $name · iOS $ios ($udid)"

    xmaj=$(xcodebuild -version | awk 'NR==1{split($2,v,"."); print v[1]}')
    imaj=${ios%%.*}
    if [ -n "$imaj" ] && [ -n "$xmaj" ] && [ "$imaj" -gt "$xmaj" ] 2>/dev/null; then
        red "✗ 아이폰은 iOS $ios 인데 Xcode는 $xmaj 버전이에요."
        echo "   iOS $imaj 기기에 설치하려면 Xcode $imaj 이상이 필요해요."
        echo "   App Store › 업데이트 에서 Xcode를 올린 뒤(한 번 실행해서 구성요소 설치) 다시 실행하세요."
        return 1
    fi

    echo "  빌드 중… (처음엔 기기 준비 때문에 몇 분 걸릴 수 있어요. 아이폰 잠금은 풀어 두세요)"
    if ! xcodebuild -project Eodi.xcodeproj -scheme Eodi -configuration Debug \
            -destination "id=$udid" -derivedDataPath build \
            -allowProvisioningUpdates \
            DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic \
            PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID" \
            build 2>&1 | tee "$LOGS/ios.log" | grep -E "error:|warning: .*(sign|provision)|BUILD (SUCCEEDED|FAILED)|\*\*"; then
        :
    fi
    if ! grep -q "BUILD SUCCEEDED" "$LOGS/ios.log"; then
        red "✗ 아이폰 빌드 실패 → build-logs/ios.log"
        if grep -qiE "is not available|already in use|bundle identifier" "$LOGS/ios.log"; then
            echo "   번들 ID가 이미 쓰이는 것 같아요. 다른 이름으로: BUNDLE_ID=kr.hanidayo.eodi2 bash build.sh ios"
        fi
        if grep -qi "Developer Mode" "$LOGS/ios.log"; then
            echo "   아이폰 › 설정 › 개인정보 보호 및 보안 › 개발자 모드 켜기 (재시동 후 다시 실행)"
        fi
        return 1
    fi

    local app="build/Build/Products/Debug-iphoneos/Eodi.app"
    echo "  설치 중…"
    if ! xcrun devicectl device install app --device "$udid" "$app" 2>&1 | tee "$LOGS/ios-install.log"; then
        red "✗ 설치 실패 → build-logs/ios-install.log"
        return 1
    fi
    green "✓ 아이폰에 설치했어요."
    if ! xcrun devicectl device process launch --device "$udid" "$BUNDLE_ID" >/dev/null 2>&1; then
        echo "   앱이 안 열리면 아이폰 › 설정 › 일반 › VPN 및 기기 관리 › 내 Apple ID › '신뢰' 를 누르세요."
    fi
    echo "   무료 계정은 7일마다 다시 설치해야 해요: bash build.sh ios"
    return 0
}

build_ipa() {
    bold "▶ 서명 안 된 IPA 만들기"
    cd "$ROOT/ios" || return 1
    "$XCODEGEN" generate 2>&1 | tee "$LOGS/xcodegen.log" || { red "✗ xcodegen 실패"; return 1; }
    xcodebuild -project Eodi.xcodeproj -scheme Eodi -configuration Release \
        -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build \
        CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
        build 2>&1 | tee "$LOGS/ipa.log" | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
    if ! grep -q "BUILD SUCCEEDED" "$LOGS/ipa.log"; then
        red "✗ 빌드 실패 → build-logs/ipa.log"; return 1
    fi
    rm -rf Payload && mkdir Payload
    cp -R build/Build/Products/Release-iphoneos/Eodi.app Payload/
    rm -f "$ROOT/Eodi.ipa"
    zip -qry "$ROOT/Eodi.ipa" Payload && rm -rf Payload
    green "✓ $ROOT/Eodi.ipa  → Sideloadly에 끌어다 놓고 설치하세요"
}

# ------------------------------------------------------------------ 실행

[ "$(uname)" = "Darwin" ] || { red "macOS에서 실행하세요."; exit 1; }

case "$MODE" in
    check)   check_android; check_ios ;;
    android) check_android ;;
    ios)     check_ios ;;
    ipa)     check_ios ;;
    all)     check_android; check_ios ;;
    *)       echo "사용법: bash build.sh [check|android|ios|ipa]"; exit 1 ;;
esac

if [ "$MISSING" = 1 ]; then
    echo; red "위 도구를 먼저 설치한 뒤 다시 실행하세요."; exit 1
fi
[ "$MODE" = check ] && { green "도구 준비 완료"; exit 0; }

RESULT=0
if [ "$MODE" = all ] || [ "$MODE" = android ]; then build_android || RESULT=1; fi
if [ "$MODE" = all ] || [ "$MODE" = ios ]; then build_ios || RESULT=1; fi
if [ "$MODE" = ipa ]; then build_ipa || RESULT=1; fi
exit $RESULT
