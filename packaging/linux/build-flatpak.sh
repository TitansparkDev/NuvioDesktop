#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
# Runtime/SDK must already be installed from Flathub (org.gnome.Platform//50 and org.gnome.Sdk//50).
[[ -f local.properties ]] || touch local.properties
./gradlew :composeApp:desktopTest :composeApp:createReleaseDistributable
mkdir -p composeApp/build/linux-jni
cp -a "$JAVA_HOME/include/." composeApp/build/linux-jni/
cache="${XDG_CACHE_HOME:-$HOME/.cache}/nuvio-flatpak"
mkdir -p "$cache" release-assets
flatpak-builder --user --force-clean --state-dir="$cache/state" --repo="$cache/repo" \
  "$cache/build" packaging/linux/flatpak/com.titanspark.NuvioHTPC.yml
flatpak build-bundle --runtime-repo=https://flathub.org/repo/flathub.flatpakrepo \
  "$cache/repo" release-assets/Nuvio-HTPC-Linux-SteamDeck-x86_64.flatpak com.titanspark.NuvioHTPC
sha256sum release-assets/*.flatpak > release-assets/SHA256SUMS
