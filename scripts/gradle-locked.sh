#!/data/data/com.termux/files/usr/bin/bash
# Run ./gradlew under the device-wide Android build lock (Termux dev box only).
#
# Other projects on this device build through a guard script that takes
# $HOME/.cache/cleverkeys-build.lock and, while holding it, `pkill -9`s every
# GradleDaemon/Kotlin daemon on the device to clear leaks. An Embeddy build that
# runs outside that lock gets its daemon killed mid-task ("Gradle build daemon
# disappeared unexpectedly"). Holding the same lock makes the guard wait for us
# instead, and keeps two memory-heavy builds from overlapping.
#
# Usage: scripts/gradle-locked.sh <gradle args...>
# Env:   EMBEDDY_LOCK_WAIT  seconds to wait for the lock (default 7200)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="${EMBEDDY_BUILD_LOCK:-$HOME/.cache/cleverkeys-build.lock}"
WAIT="${EMBEDDY_LOCK_WAIT:-7200}"

mkdir -p "$(dirname "$LOCK")"
exec 9>"$LOCK"
# Poll with -n: `flock -w` silently ignores its timeout on Termux's util-linux.
deadline=$(( $(date +%s) + WAIT ))
until flock -n 9; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "gradle-locked: $LOCK still held after ${WAIT}s — giving up" >&2
        exit 75
    fi
    sleep 5
done

cd "$ROOT"
exec ./gradlew "$@" \
    -Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m" \
    --console=plain
