#!/usr/bin/env bash
# Installs everything build.sh needs on Ubuntu 24.04 / Debian. No Gradle, no Android Studio.
#
#   aapt2, dx, zipalign, apksigner, junit4, ProGuard -> Ubuntu packages
#   Kotlin compiler                         -> JetBrains GitHub release
#   android.jar (API 31, 34, 36)            -> official SDK jars mirrored in Sable/android-platforms
#
# Usage: sudo tools/setup-toolchain.sh [install-dir]   (default /opt/androidtc)
set -euo pipefail

TC="${1:-${LUMEN_TOOLCHAIN:-/opt/androidtc}}"
# Kotlin 2.0.21: newer standard libraries use more Java 8 "invokedynamic" lambdas, which the
# Ubuntu dexer can't rewrite for Android. build.sh strips and checks the rest.
KOTLIN_VERSION=2.0.21
KOTLIN_SHA=0352c0a45bd22f80f6b26e485cd04da8047baa5de54865281fb9f89a4a7bcf2a
declare -A JAR_SHA=(
  [31]=d53c940da56e816deb259c3b43b667b43d82d8615029ee10fa5bff641ef0123d
  [34]=6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad
  [36]=d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a
)

if command -v apt-get >/dev/null; then
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q \
    aapt dalvik-exchange apksigner zipalign junit4 proguard-cli zip unzip python3 openjdk-21-jdk-headless >/dev/null
fi

mkdir -p "$TC/platforms"
cd "$TC"

check() { echo "$2  $1" | sha256sum -c --quiet - || { echo "Checksum mismatch: $1" >&2; exit 1; }; }

for api in 31 34 36; do
  jar="platforms/android-$api.jar"
  if [ ! -f "$jar" ]; then
    curl -fsSL -o "$jar" "https://raw.githubusercontent.com/Sable/android-platforms/master/android-$api/android.jar"
  fi
  check "$jar" "${JAR_SHA[$api]}"
done

if ! grep -qs "^$KOTLIN_VERSION" kotlinc/build.txt; then
  curl -fsSL -o kotlinc.zip "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  check kotlinc.zip "$KOTLIN_SHA"
  rm -rf kotlinc kotlin-stdlib-dx.jar && unzip -q kotlinc.zip && rm kotlinc.zip
fi

# dx (the dexer available as an Ubuntu package) can't read Java 9 module descriptors,
# so strip them from the Kotlin standard library once.
if [ ! -f kotlin-stdlib-dx.jar ]; then
  work=$(mktemp -d)
  (cd "$work" && unzip -q "$TC/kotlinc/lib/kotlin-stdlib.jar" \
     && rm -rf META-INF/versions && find . -name module-info.class -delete \
     && zip -qr "$TC/kotlin-stdlib-dx.jar" .)
  rm -rf "$work"
fi

echo "Toolchain ready in $TC"
