#!/bin/bash
# 在桌面 JVM 上跑真实的 Net/Booker 代码。
# expected 值来自独立 oracle：站点自己的 crypto-js + 真实抓包 + 手算 fixture。
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
AJ="$SDK/platforms/android-35/android.jar"
OUT="$DIR/out"

if [ ! -d "$ROOT/build/gen" ]; then
  echo "先跑一次 ./build.sh 生成 build/gen/R.java" >&2
  exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT"
SRC=$(find "$ROOT/src" "$ROOT/build/gen" -name '*.java')

javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" \
      -classpath "$AJ:$DIR/lib/json.jar" -d "$OUT" \
      "$DIR/android/util/Base64.java" $SRC "$DIR/com/zwlib/quick/Verify.java" 2>&1 \
  | grep -vE "bootstrap|^注" || true

# out 必须排在 android.jar 前面，自己的 android.util.Base64 替身才生效
java -Dfixtures="$DIR/fixtures" -cp "$OUT:$DIR/lib/json.jar:$AJ" com.zwlib.quick.Verify
