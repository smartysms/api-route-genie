#!/usr/bin/env bash
# Runs ./gradlew with a lock, because two Gradle builds in the same project at once corrupt build/ and the Kotlin caches.
# Usage:  ROUTEGENIE_IDE="<path of an installed PyCharm>" scripts/gw.sh test --rerun
# ROUTEGENIE_IDE is optional: with it, the build uses that IDE (and its bundled JBR as JAVA_HOME) instead of downloading one.
set -u
cd "$(dirname "$0")/.."
LOCK=.gradle-lock
STALE_MINUTES=30

while ! mkdir "$LOCK" 2>/dev/null; do
  # A lock left behind by a crashed run must not block everyone forever.
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +"$STALE_MINUTES" 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null; fi
  sleep 3
done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

args=(--console=plain)
if [ -n "${ROUTEGENIE_IDE:-}" ]; then
  export JAVA_HOME="$ROUTEGENIE_IDE/jbr"
  args+=("-PplatformPath=$ROUTEGENIE_IDE")
fi
./gradlew "${args[@]}" "$@"
