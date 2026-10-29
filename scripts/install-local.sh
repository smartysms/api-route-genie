#!/usr/bin/env bash
# Builds the plugin and installs it into an IDE's user plugin folder, replacing the previous version.
# Usage:  ROUTEGENIE_PLUGINS_DIR="<IDE config dir>/plugins" scripts/install-local.sh
# (ROUTEGENIE_IDE and ROUTEGENIE_LOWMEM are passed on to scripts/gw.sh.) Restart the IDE afterwards.
# A running IDE usually keeps the plugin jar open on Windows, so the replacement can fail: close the IDE first, or install
# the zip from Settings | Plugins | gear | Install Plugin from Disk, which stages the update for the next start.
set -eu
cd "$(dirname "$0")/.."
: "${ROUTEGENIE_PLUGINS_DIR:?set ROUTEGENIE_PLUGINS_DIR to the plugins folder of the IDE}"

scripts/gw.sh buildPlugin

name=$(grep '^artifactName=' gradle.properties | cut -d= -f2)
zip=$(ls -t build/distributions/"$name"-*.zip | head -1)
staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT
unzip -q "$zip" -d "$staging"

if ! rm -rf "${ROUTEGENIE_PLUGINS_DIR:?}/$name" 2>/dev/null || [ -e "$ROUTEGENIE_PLUGINS_DIR/$name" ]; then
  echo "Could not replace $ROUTEGENIE_PLUGINS_DIR/$name (the IDE probably has it open)."
  echo "Close the IDE and run this again, or install $zip via Install Plugin from Disk."
  exit 1
fi
mv "$staging/$name" "$ROUTEGENIE_PLUGINS_DIR/$name"
echo "Installed $zip. Restart the IDE to load it."
