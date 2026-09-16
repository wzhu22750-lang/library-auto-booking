#!/bin/bash
set -e
export BT=~/Library/Android/sdk/build-tools/35.0.0
export PLATFORM=~/Library/Android/sdk/platforms/android-35/android.jar
cd "$(dirname "$0")"
VER_CODE=${1:-3}
VER_NAME=${2:-1.2}
rm -rf build && mkdir -p build/compiled build/gen build/classes build/dex
$BT/aapt2 compile --dir res -o build/compiled/res.zip
$BT/aapt2 link -o build/base.apk -I $PLATFORM --manifest AndroidManifest.xml \
  -R build/compiled/res.zip -A assets --java build/gen \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code $VER_CODE --version-name $VER_NAME --auto-add-overlay
find src build/gen -name '*.java' > build/sources.txt
JAVAC_LOG=$(javac -nowarn -source 8 -target 8 -bootclasspath $PLATFORM -classpath $PLATFORM \
  -d build/classes @build/sources.txt 2>&1 | grep -vE "bootstrap|^注" || true)
if echo "$JAVAC_LOG" | grep -qE "错误|error:"; then
  echo "COMPILE FAILED:"; echo "$JAVAC_LOG"; exit 1
fi
$BT/d8 --min-api 21 --lib $PLATFORM --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build && zip -q -j unsigned.apk dex/classes.dex)
$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out zwlib-quick.apk build/aligned.apk
echo "built: $(ls -la zwlib-quick.apk | awk '{print $5" bytes"}')"
