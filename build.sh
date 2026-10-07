#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
AB="$HOME/.local/opt/android-build"
JAVA_HOME="$AB/jdk-17.0.20.1+1"
BT="$AB/build-tools-r34"
ANDROID_JAR="$AB/android-35/android.jar"
KEYS="/home/yunwei/.config/car-hu/aosp-keys"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$BT:$PATH"

cd "$ROOT"
rm -rf gen obj build
mkdir -p gen obj build

echo "[1/6] 编译资源"
aapt2 compile --dir res -o build/res.zip

MODE="${1:-system}"
if [ "$MODE" = "normal" ]; then
  MANIFEST="AndroidManifest.normal.xml"
  OUT_APK="FMTuner-normal.apk"
else
  MANIFEST="AndroidManifest.xml"
  OUT_APK="FMTuner.apk"
fi

echo "[2/6] 链接APK骨架 ($MODE)"
aapt2 link \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  -R build/res.zip \
  --auto-add-overlay \
  --java gen \
  -A assets \
  --min-sdk-version 29 \
  --target-sdk-version 29 \
  -o build/base.apk

echo "[3/6] 编译Java"
find gen src -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 11 -target 11 -nowarn \
  -cp "$ANDROID_JAR" \
  -d obj \
  @build/sources.txt

echo "[4/6] dex"
find obj -name '*.class' > build/classes.txt
d8 --release --lib "$ANDROID_JAR" --output build @build/classes.txt
cp build/base.apk build/fmtuner-unsigned.apk
( cd build && jar uf fmtuner-unsigned.apk classes.dex )

echo "[5/6] 对齐"
zipalign -f -p 4 build/fmtuner-unsigned.apk build/fmtuner-aligned.apk

echo "[6/6] AOSP平台签名"
apksigner sign \
  --key "$KEYS/platform.pk8" \
  --cert "$KEYS/platform.x509.pem" \
  --out "build/$OUT_APK" \
  build/fmtuner-aligned.apk
apksigner verify --print-certs "build/$OUT_APK" | head -6

ls -la "build/$OUT_APK"
echo "BUILD_OK"
