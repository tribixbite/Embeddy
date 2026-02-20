#!/data/data/com.termux/files/usr/bin/bash
# Build Embeddy on Termux ARM64 and optionally install via adb
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_TASK="assembleDebug"
INSTALL=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --release) GRADLE_TASK="assembleRelease"; shift ;;
        --install) INSTALL=true; shift ;;
        *) echo "Usage: $0 [--release] [--install]"; exit 1 ;;
    esac
done

echo "=== Embeddy Build ==="
echo "  Task:    $GRADLE_TASK"
echo "  Java:    $(java -version 2>&1 | head -1)"

# Determine aapt2 binary — prefer bundled ARM64 static binary
AAPT2_BUNDLED="$PROJECT_DIR/tools/aapt2-arm64/aapt2"
if [ -x "$AAPT2_BUNDLED" ] && "$AAPT2_BUNDLED" version &>/dev/null; then
    AAPT2_BIN="$AAPT2_BUNDLED"
    echo "  AAPT2:   $AAPT2_BIN (native ARM64)"
elif command -v aapt2 &>/dev/null; then
    AAPT2_BIN="$(which aapt2)"
    echo "  AAPT2:   $AAPT2_BIN (Termux pkg)"
else
    echo "Error: No aapt2 found. Place a static ARM64 aapt2 at tools/aapt2-arm64/aapt2"
    exit 1
fi

# Repack gradle's cached aapt2 jar with ARM64 binary if needed
if [ "$(uname -m)" = "aarch64" ]; then
    AAPT2_JAR=$(find ~/.gradle/caches/modules-2 -path "*aapt2*linux.jar" -type f 2>/dev/null | head -1)
    if [ -n "$AAPT2_JAR" ]; then
        CACHED_ARCH=$(unzip -p "$AAPT2_JAR" aapt2 2>/dev/null | file - | grep -o "x86-64" || true)
        if [ -n "$CACHED_ARCH" ]; then
            echo "  Repacking gradle aapt2 jar with ARM64 binary..."
            REPACK_DIR=$(mktemp -d)
            (cd "$REPACK_DIR" && unzip -qo "$AAPT2_JAR" && cp "$AAPT2_BIN" aapt2 && chmod +x aapt2 && jar cf repacked.jar META-INF/MANIFEST.MF aapt2 NOTICE && cp repacked.jar "$AAPT2_JAR")
            rm -rf "$REPACK_DIR"
            # Clear stale transforms so they rebuild with ARM64 aapt2
            find ~/.gradle/caches -maxdepth 2 -name "transforms" -type d -exec rm -rf {} + 2>/dev/null || true
            echo "  Done — gradle aapt2 cache is now ARM64."
        fi
    fi
fi

echo ""
echo "Building..."

"$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" $GRADLE_TASK \
    -Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m" \
    -Pandroid.aapt2FromMavenOverride="$AAPT2_BIN" \
    --no-daemon \
    --warning-mode=none \
    --console=plain \
    --parallel \
    --build-cache

echo ""

# Find output APK
BUILD_TYPE=$(echo "$GRADLE_TASK" | sed 's/assemble//' | tr '[:upper:]' '[:lower:]')
APK_DIR="$PROJECT_DIR/app/build/outputs/apk/$BUILD_TYPE"
APK=$(find "$APK_DIR" -name "*universal*.apk" -o -name "*arm64*.apk" 2>/dev/null | head -1)
if [ -z "$APK" ]; then
    APK=$(find "$APK_DIR" -name "*.apk" 2>/dev/null | head -1)
fi

if [ -n "$APK" ]; then
    SIZE=$(du -h "$APK" | cut -f1)
    echo "APK: $APK ($SIZE)"

    if $INSTALL && command -v adb &>/dev/null; then
        echo "Installing via adb..."
        adb install -r "$APK"
        echo "Launching..."
        adb shell am start -n app.embeddy.debug/app.embeddy.MainActivity
    fi
else
    echo "No APK found in $APK_DIR"
fi
