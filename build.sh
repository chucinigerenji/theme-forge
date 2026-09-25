#!/usr/bin/env bash
#
# 主题工坊 ThemeForge —— 纯命令行构建脚本
# 只用 aapt2 + javac + d8 + apksigner，不需要 Gradle / Android Studio。
#
# 用法：
#   bash build.sh                 # 输出到 dist/ThemeForge-v2.0.apk
#   bash build.sh /tmp/out.apk    # 指定输出路径
#
# 可用环境变量覆盖：
#   ANDROID_JAR  AAPT2  D8  JAVAC  ZIPALIGN  APKSIGNER  KEYTOOL  KEYSTORE
#
set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="ThemeForge"
VERSION_CODE=13
VERSION_NAME="2.0"
MIN_SDK=24
TARGET_SDK=34

# ---------- 工具 ----------
JAVAC="${JAVAC:-javac}"
AAPT2="${AAPT2:-aapt2}"
D8="${D8:-d8}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"
KEYTOOL="${KEYTOOL:-keytool}"
for t in "$JAVAC" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$KEYTOOL"; do
  command -v "$t" >/dev/null 2>&1 || { echo "缺少命令：$t"; exit 1; }
done

# ---------- android.jar ----------
AJAR="${ANDROID_JAR:-}"
if [ -z "$AJAR" ]; then
  for c in "$ROOT/sdk/android.jar" \
           "$HOME/.android-sdk/android.jar" \
           "${ANDROID_HOME:-/nonexistent}/platforms/android-35/android.jar" \
           "${ANDROID_SDK_ROOT:-/nonexistent}/platforms/android-35/android.jar" \
           /opt/android-sdk/platforms/android-35/android.jar \
           /usr/lib/android-sdk/platforms/android-35/android.jar; do
    if [ -f "$c" ]; then AJAR="$c"; break; fi
  done
fi
if [ ! -f "$AJAR" ]; then
  cat <<'EOF'
找不到 android.jar

任选一种方式：
  1) 把 android.jar 放到  sdk/android.jar
  2) export ANDROID_JAR=/path/to/android.jar
  3) 安装 Android SDK 并设置 ANDROID_HOME

android.jar 是 Android 平台的编译桩（只有签名、没有实现），
任意 API 33+ 的版本都能用来编译本项目。
EOF
  exit 1
fi

# ---------- 第三方依赖（7z 解码） ----------
LIBS="$ROOT/third_party/commons-compress-1.21.jar:$ROOT/third_party/xz-1.9.jar"
if [ ! -f "$ROOT/third_party/commons-compress-1.21.jar" ]; then
  echo "缺少 third_party 里的 7z 依赖，先执行： bash fetch-deps.sh"
  exit 1
fi

BUILD="$ROOT/build"
OUT="${1:-$ROOT/dist/${APP}-v${VERSION_NAME}.apk}"
mkdir -p "$(dirname "$OUT")"

cd "$ROOT"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir res -o "$BUILD/res.zip"

echo "[2/6] aapt2 link"
"$AAPT2" link -o "$BUILD/base.apk" \
  -I "$AJAR" \
  --manifest AndroidManifest.xml \
  "$BUILD/res.zip" \
  -A assets \
  --java "$BUILD/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME"

echo "[3/6] javac"
find src "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 \
  -classpath "$AJAR:$LIBS" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[4/6] d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$D8" --lib "$AJAR" --min-api "$MIN_SDK" --output "$BUILD/dex" \
  @"$BUILD/classes.txt" \
  "$ROOT/third_party/commons-compress-1.21.jar" "$ROOT/third_party/xz-1.9.jar"

echo "[5/6] 打包 dex"
# 固定时间戳：让每次构建产物完全一致（可复现构建）
touch -t 198001010000 "$BUILD/dex/classes.dex"
( cd "$BUILD/dex" && zip -qX "$BUILD/base.apk" classes.dex )

echo "[6/6] zipalign + 签名"
"$ZIPALIGN" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

KS="${KEYSTORE:-$ROOT/debug.keystore}"
if [ ! -f "$KS" ]; then
  echo "    生成自签名 keystore：$KS"
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias forge \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=ThemeForge, OU=DSH, O=DSH, L=CN, S=CN, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-key-alias forge \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled false --v2-signing-enabled true \
  --out "$OUT" "$BUILD/aligned.apk"

rm -rf "$BUILD"
echo "构建完成：$OUT"
ls -la "$OUT"
