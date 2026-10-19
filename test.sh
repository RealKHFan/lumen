#!/usr/bin/env bash
# Runs the unit tests for the app's core logic on a plain JVM.
# The core package is compiled WITHOUT android.jar, which also proves it has no Android dependency.
set -euo pipefail
cd "$(dirname "$0")"

TC="${LUMEN_TOOLCHAIN:-/opt/androidtc}"
KOTLINC="$TC/kotlinc/bin/kotlinc"
STDLIB="$TC/kotlinc/lib/kotlin-stdlib.jar"
JUNIT=/usr/share/java/junit4.jar
HAMCREST=/usr/share/java/hamcrest-core.jar
OUT=build/test
rm -rf "$OUT" && mkdir -p "$OUT"

mapfile -t CORE < <(find app/src/main/kotlin -path '*/core/*.kt' | sort)
mapfile -t TESTS < <(find app/src/test/kotlin -name '*.kt' | sort)

"$KOTLINC" -Werror -jvm-target 1.8 -cp "$JUNIT:$HAMCREST" -d "$OUT" "${CORE[@]}" "${TESTS[@]}" 2>&1 \
  | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
test -n "$(find "$OUT" -name '*Test.class' -print -quit)" || { echo "Test compile failed"; exit 1; }

CLASSES=$(cd "$OUT" && find . -name '*Test.class' -not -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
# shellcheck disable=SC2086
java -cp "$OUT:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore $CLASSES 2>&1 \
  | grep -v '^Picked up JAVA_TOOL_OPTIONS'
