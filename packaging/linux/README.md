# Steam Deck / Linux TV build

Download `Nuvio-HTPC-Linux-SteamDeck-x86_64.flatpak` from this fork's GitHub release.
In Steam Deck Desktop Mode, install it with Discover or run:

```sh
flatpak install --user ./Nuvio-HTPC-Linux-SteamDeck-x86_64.flatpak
flatpak run com.titanspark.NuvioHTPC
```

If the GNOME runtime is unavailable, add Flathub and retry:

```sh
flatpak remote-add --user --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
```

Add Nuvio HTPC to Steam as a non-Steam game, then launch it in Game Mode. Use Steam's
Gamepad controller template: the app reads `/dev/input/js*` directly, so keyboard emulation
can produce duplicate input. TV mode and controller input default on for a fresh install.
Existing settings are preserved. Controller bindings are configurable in Settings.

This is a Linux port of the fork's TV interface, built on Compose Desktop with embedded mpv.
It is not the Android APK. Physical Steam Deck controls still require device verification.
Linux currently omits hero trailers, seek-thumbnail previews, Windows RTX/SVP processing,
and Windows media-session integration. The in-app updater is unavailable on Linux;
install later Flatpak releases manually.

Build on x86_64 Linux with JDK 21, the project's Gradle prerequisites, a C++ compiler,
pkg-config, libmpv/GTK 3/WebKitGTK 4.1/X11 development packages, and flatpak-builder.
Install `org.gnome.Platform//50` and `org.gnome.Sdk//50` from Flathub, then run:

```sh
./packaging/linux/build-flatpak.sh
```

The script runs controller tests, packages the JRE, and rebuilds the player bridge inside the
Flatpak SDK. Output and checksums are in `release-assets/`.
