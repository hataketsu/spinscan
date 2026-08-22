#!/usr/bin/env bash
# Build and sign the Collmap APK without Gradle.
#
# The chain is the one Gradle would drive anyway:
#   aapt2 compile/link  -> resources + a resource-only APK
#   kotlinc             -> JVM class files against android.jar
#   d8                  -> classes.dex (app classes + Kotlin stdlib)
#   zipalign, apksigner -> installable APK
#
# Nothing is downloaded at build time: the Android SDK and a standalone kotlinc
# under tools/ are all it needs.
#
# Usage: ./android/build.sh [--version-code N] [--version-name X.Y]
set -euo pipefail

main() {
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
PLATFORM="${PLATFORM:-$SDK/platforms/android-34}"
KOTLINC="${KOTLINC:-$HERE/tools/kotlinc/bin/kotlinc}"
# AGP builds on 17 or 21; the system default here is 25, which kotlinc rejects.
JAVA_HOME="${JAVA_HOME_OVERRIDE:-/usr/lib/jvm/java-21-openjdk-amd64}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"

VERSION_CODE=""
VERSION_NAME=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

for f in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" \
         "$BUILD_TOOLS/apksigner" "$PLATFORM/android.jar" "$KOTLINC" "$KEYSTORE"; do
  [ -e "$f" ] || { echo "ERROR: missing $f" >&2; exit 1; }
done

OUT="$HERE/build"
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"

MANIFEST="$HERE/AndroidManifest.xml"
if [ -n "$VERSION_CODE$VERSION_NAME" ]; then
  # Patch a copy, so the checked-in manifest stays the source of truth.
  MANIFEST="$OUT/AndroidManifest.xml"
  cp "$HERE/AndroidManifest.xml" "$MANIFEST"
  [ -n "$VERSION_CODE" ] && sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" "$MANIFEST"
  [ -n "$VERSION_NAME" ] && sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" "$MANIFEST"
fi

echo "==> [1/5] aapt2 compile"
"$BUILD_TOOLS/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"

echo "==> [2/5] aapt2 link"
"$BUILD_TOOLS/aapt2" link \
  -I "$PLATFORM/android.jar" \
  --manifest "$MANIFEST" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --java "$OUT/gen" \
  -o "$OUT/base.apk" \
  "$OUT/res.zip"

echo "==> [3/5] kotlinc"
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
JAVA_HOME="$JAVA_HOME" "$KOTLINC" \
  -classpath "$PLATFORM/android.jar" \
  -jvm-target 17 \
  -nowarn \
  -d "$OUT/classes" \
  "$HERE/kotlin"

echo "==> [4/5] d8"
mapfile -t CLASSES < <(find "$OUT/classes" -name '*.class')
"$BUILD_TOOLS/d8" \
  --lib "$PLATFORM/android.jar" \
  --min-api 26 \
  --release \
  --output "$OUT/dex" \
  "${CLASSES[@]}" "$STDLIB"

echo "==> [5/5] package + sign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex )
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT/collmap.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$OUT/collmap.apk" >/dev/null

VC=$(grep -o 'versionCode="[0-9]*"' "$MANIFEST" | head -1 | tr -dc 0-9)
VN=$(grep -o 'versionName="[^"]*"' "$MANIFEST" | head -1 | cut -d'"' -f2)
SIZE=$(stat -c%s "$OUT/collmap.apk")
SHA=$(sha256sum "$OUT/collmap.apk" | cut -d' ' -f1)

# The OTA manifest the app fetches from /api/app/latest.
mkdir -p "$HERE/../dist"
cp "$OUT/collmap.apk" "$HERE/../dist/collmap.apk"
cat > "$HERE/../dist/latest.json" <<EOF
{
 "version_code": $VC,
 "version_name": "$VN",
 "url": "/api/app/download",
 "sha256": "$SHA",
 "size": $SIZE,
 "notes": ""
}
EOF

echo
echo "DONE  dist/collmap.apk  ${VN} (code ${VC})  $((SIZE/1024)) KB"
echo "      sha256 $SHA"
}

main "$@"
