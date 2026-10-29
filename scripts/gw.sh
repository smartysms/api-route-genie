#!/usr/bin/env bash
# Runs ./gradlew with a lock, because two Gradle builds in the same project at once corrupt build/ and the Kotlin caches.
# Usage:  ROUTEGENIE_IDE="<path of an installed PyCharm>" scripts/gw.sh test --rerun
# ROUTEGENIE_IDE is optional: with it, the build uses that IDE (and its bundled JBR as JAVA_HOME) instead of downloading one.
# ROUTEGENIE_LOWMEM=1 keeps memory small for machines that are busy: no resident daemon (all memory is released when the
# build ends), a 1.5 GB heap cap, Kotlin compiled inside the build JVM and a single worker. Builds are slower.
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
if [ -n "${ROUTEGENIE_LOWMEM:-}" ]; then
  args+=(--no-daemon --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8" "-Pkotlin.compiler.execution.strategy=in-process")
fi
./gradlew "${args[@]}" "$@"
