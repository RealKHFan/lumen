#!/usr/bin/env bash
# Builds Lumen into a signed APK without Gradle.
#
#   1. aapt2 compiles and links resources and the manifest
#   2. javac compiles the generated R class
#   3. kotlinc compiles the app (warnings are errors)
#   4. API check: everything outside compat/ must compile against Android 12 (API 31)
#   5. ProGuard drops the unused parts of the Kotlin standard library
#   6. dx converts the bytecode to Android's dex format
#   7. tools/check-dex.py: no Java 8 lambdas Android can't run, and every call exists on Android
#   8. zipalign + apksigner produce the final APK, which is then verified
#
# Toolchain: tools/setup-toolchain.sh. Signing: set LUMEN_KEYSTORE, LUMEN_KEY_ALIAS and
# LUMEN_KS_PASS, otherwise a throwaway debug key is generated in build/.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_NAME=1.0.6
# Lumen is installed as com.google.android.apps.photos. A version code above anything the real
# Google Photos uses means the camera never sees an "outdated Photos", and the Play Store never
# offers a Google Photos "update" it couldn't install anyway. Format: 20MMmmpp00 -> 1.0.6 = 2000010600.
VERSION_CODE=2000010600
MIN_SDK=31
TARGET_SDK=36

TC="${LUMEN_TOOLCHAIN:-/opt/androidtc}"
JAR_LINK="$TC/platforms/android-34.jar"     # resource tables: Ubuntu's aapt2 can't read 35+
JAR_COMPILE="$TC/platforms/android-36.jar"
JAR_MIN="$TC/platforms/android-$MIN_SDK.jar"
KOTLINC="$TC/kotlinc/bin/kotlinc"
STDLIB="$TC/kotlin-stdlib-dx.jar"

SRC=app/src/main
PKG_PATH=io/github/realkhfan/lumen
OUT=build
KFLAGS=(-no-jdk -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline -no-reflect -Werror)

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
quiet() { grep -v '^Picked up JAVA_TOOL_OPTIONS' || true; }

rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes,classes-r,classes-compat,apicheck}

step "Resources"
aapt2 compile --dir "$SRC/res" -o "$OUT/res.zip"
aapt2 link -I "$JAR_LINK" --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  --custom-package io.github.realkhfan.lumen --java "$OUT/gen" \
  -A "$SRC/assets" --no-auto-version --warn-manifest-validation \
  -o "$OUT/base.apk" "$OUT/res.zip"

step "R class"
javac --release 8 -Xlint:-options -nowarn -d "$OUT/classes-r" "$OUT/gen/$PKG_PATH/R.java" 2>&1 | quiet

step "Kotlin"
mapfile -t ALL_KT < <(find "$SRC/kotlin" -name '*.kt' | sort)
mapfile -t COMPAT_KT < <(find "$SRC/kotlin/$PKG_PATH/compat" -name '*.kt' | sort)
mapfile -t MIN_KT < <(find "$SRC/kotlin" -name '*.kt' -not -path '*/compat/*' | sort)
"$KOTLINC" "${KFLAGS[@]}" -cp "$JAR_COMPILE:$OUT/classes-r" -d "$OUT/classes" "${ALL_KT[@]}" 2>&1 | quiet
test -n "$(find "$OUT/classes" -name '*.class' -print -quit)" || { echo "Kotlin compile failed"; exit 1; }

step "API check (everything outside compat/ must work on Android $MIN_SDK)"
"$KOTLINC" "${KFLAGS[@]}" -cp "$JAR_COMPILE:$OUT/classes-r" -d "$OUT/classes-compat" "${COMPAT_KT[@]}" 2>&1 | quiet
"$KOTLINC" "${KFLAGS[@]}" -cp "$JAR_MIN:$OUT/classes-r:$OUT/classes-compat" -d "$OUT/apicheck" "${MIN_KT[@]}" 2>&1 | quiet
test -n "$(find "$OUT/apicheck" -name '*.class' -print -quit)" || { echo "API check failed"; exit 1; }

step "Shrink the Kotlin standard library"
proguard -injars "$OUT/classes" -injars "$OUT/classes-r" -injars "$STDLIB(!META-INF/**)" \
  -outjars "$OUT/app.jar" -libraryjars "$JAR_COMPILE" @tools/proguard.pro 2>&1 | quiet \
  | grep -vE '^(ProGuard, version|Reading|Preparing|Copying|Note:|Writing)' || true
test -f "$OUT/app.jar"

step "Dex"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/app.jar" 2>&1 | quiet
test -f "$OUT/classes.dex"

step "Package and sign"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT" && zip -qj unaligned.apk classes.dex)
zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
python3 tools/check-dex.py "$OUT/aligned.apk" "$JAR_COMPILE"

if [ -z "${LUMEN_KEYSTORE:-}" ]; then
  echo "LUMEN_KEYSTORE not set: signing with a throwaway debug key"
  keytool -genkeypair -keystore "$OUT/debug.jks" -storepass android -keypass android \
    -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Lumen debug" >/dev/null 2>&1
  LUMEN_KEYSTORE="$OUT/debug.jks"; LUMEN_KEY_ALIAS=debug; LUMEN_KS_PASS=android
fi
APK="$OUT/Lumen-$VERSION_NAME.apk"
KS_PASS="$LUMEN_KS_PASS" apksigner sign --ks "$LUMEN_KEYSTORE" --ks-key-alias "${LUMEN_KEY_ALIAS:-lumen}" \
  --ks-pass env:KS_PASS --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk" 2>&1 | quiet

step "Verify"
apksigner verify --verbose "$APK" 2>&1 | quiet | grep -E '^Verified|^Number of signers'
zipalign -c -p 4 "$APK" && echo "zipalign: ok"
aapt2 dump badging "$APK" | grep -E "^package|dkVersion|application-label:|launchable"
ls -l "$APK"
echo "Built $APK"
