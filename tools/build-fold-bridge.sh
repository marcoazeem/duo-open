#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
sdk=${ANDROID_HOME:-/home/umbrel/android-sdk}
out="$root/build/fold-bridge"
mkdir -p "$out/classes" "$out/dex"
javac --release 17 -cp "$sdk/platforms/android-36/android.jar" -d "$out/classes" "$root/tools/FoldDisplayBridge.java"
java -cp "$out/classes:$sdk/platforms/android-36/android.jar" FoldDisplayBridge --self-test
"$sdk/build-tools/36.0.0/d8" --lib "$sdk/platforms/android-36/android.jar" --min-api 36 --output "$out/dex" "$out"/classes/*.class
(cd "$out/dex" && jar cf "$out/duo-fold-bridge.jar" classes.dex)
echo "$out/duo-fold-bridge.jar"
