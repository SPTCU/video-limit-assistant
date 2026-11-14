#!/usr/bin/env bash
# ============================================================
# 刷视频限时助手 - 一键构建 APK（无需 Gradle，纯 Android SDK 命令行工具）
# 依赖：JDK 17+、Android SDK（platform 34 + build-tools 35.0.0）
# 注意：aapt2/zipalign 是原生 Windows 程序，不能直接吃中文路径，
#       因此资源编译时切到工程目录用相对路径，中间产物统一放 C:\vl_build。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"

# SDK 定位：优先 ANDROID_HOME，否则用工程旁的 android-sdk
SDK_RAW="${ANDROID_HOME:-$ROOT/../android-sdk}"
# 原生工具（aapt2/zipalign）无法处理非 ASCII 路径，必要时经 C:\AndroidSdk 联接访问
if printf '%s' "$SDK_RAW" | LC_ALL=C grep -q '[^ -~]'; then
  if [ ! -e /c/AndroidSdk ]; then
    PS1="$BUILD/mk_junction.ps1"
    mkdir -p "$BUILD"
    printf '\xef\xbb\xbfNew-Item -ItemType Junction -Path "C:\\AndroidSdk" -Target "%s" -Force | Out-Null\n' \
      "$(cygpath -w "$SDK_RAW")" > "$PS1"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$PS1")"
  fi
  SDK="/c/AndroidSdk"
else
  SDK="$SDK_RAW"
fi
BT="$SDK/build-tools/35.0.0"     # d8 需 35+（34 的 d8 与 JDK21 编译产物不兼容）
PLATFORM="$SDK/platforms/android-34/android.jar"
# JDK 定位：优先 JAVA_HOME，否则问 java 自己（java.home 最可靠，Windows 的 javapath 是转发壳，
# 用 readlink 只会解析到没有 keytool 的中转目录）
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  if command -v java >/dev/null 2>&1; then
    _JH="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^ *java\.home = /{print $2; exit}')"
    if [ -n "$_JH" ]; then
      JAVA_HOME="$(cygpath -u "$_JH" 2>/dev/null || printf '%s' "$_JH")"
    fi
  fi
fi
if [ ! -x "${JAVA_HOME:-}/bin/javac" ]; then
  echo "错误：未找到可用 JDK。请安装 JDK 17+ 并设置 JAVA_HOME" >&2
  exit 1
fi
BUILD="/c/vl_build"
FINAL_APK="$ROOT/刷视频限时助手-v1.4.apk"

export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "==> [1/7] 编译资源 (aapt2 compile)"
( cd "$APP/src/main" && "$BT/aapt2.exe" compile --dir res -o "$BUILD/res.zip" )

echo "==> [2/7] 链接资源与清单 (aapt2 link)"
( cd "$APP/src/main" && "$BT/aapt2.exe" link \
    -o "$BUILD/base.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --java "$BUILD/gen" \
    --min-sdk-version 24 \
    --target-sdk-version 33 \
    --version-code 5 \
    --version-name 1.4 \
    "$BUILD/res.zip" )

echo "==> [3/7] 编译 Java (javac)"
# @argfile 内容不会被 MSYS 转换，需手工转成 Windows 路径（C:/...）
find "$BUILD/gen" "$APP/src/main/java" -name '*.java' \
  | while read -r f; do cygpath -m "$f"; done > "$BUILD/sources.txt"
javac -nowarn -encoding UTF-8 --release 11 \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "==> [4/7] 生成 dex (d8)"
CLASSES=$(find "$BUILD/classes" -name '*.class' | tr '\n' ' ')
"$BT/d8.bat" --release \
  --lib "$PLATFORM" \
  --min-api 24 \
  --output "$BUILD/dex" \
  $CLASSES

echo "==> [5/7] 打包 dex 进 APK"
python "$ROOT/tools/add_dex.py" "$BUILD/base.apk" "$BUILD/dex/classes.dex"

echo "==> [6/7] 对齐 (zipalign)"
"$BT/zipalign.exe" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "==> [7/7] 签名 (apksigner)"
KS="$BUILD/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner.bat" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/signed.apk" "$BUILD/aligned.apk"

cp "$BUILD/signed.apk" "$FINAL_APK"

echo ""
echo "==> 构建完成：$FINAL_APK"
( cd "$BUILD" && "$BT/aapt2.exe" dump badging signed.apk | grep -E "package|application-label|sdkVersion|targetSdkVersion" )
