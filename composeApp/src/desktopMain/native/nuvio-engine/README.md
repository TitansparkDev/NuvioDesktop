# Nuvio Engine on desktop

`nuvio_engine.dll` is built from a sibling checkout of [NuvioMedia/nuvio-engine](https://github.com/NuvioMedia/nuvio-engine)
at `../nuvio-engine` (override with `-Pnuvio.engine.sourceDir=`), by `:composeApp:buildWindowsNuvioEngine`.

Toolchain (dev box): MSYS2 UCRT64 GCC + static OpenSSL (`pacman -S mingw-w64-ucrt-x86_64-openssl`),
CMake from Visual Studio Build Tools, jni.h from the Gradle JDK. The task skips with a warning when any
of those is missing; `createDistributable` refuses to package without the DLL.

## Local engine changes

The checkout must carry `patches/*.patch` on top of the pinned upstream version (v0.1.1):

```
cd ../nuvio-engine
git checkout -b nuvio-desktop v0.1.1
git am ../NuvioDesktop/composeApp/src/desktopMain/native/nuvio-engine/patches/*.patch
```

`git config core.autocrlf false` in that checkout first — the engine's own libtorrent patches are applied
with `git apply` at configure time and fail on CRLF sources.

- `0001-desktop-peer-discovery.patch` — Stremio-parity swarm behaviour measured against Stremio on the
  same infohashes: DHT bootstrap routers (libtorrent ships one node), a 30 s re-announce to all
  trackers + DHT while under 40 peers, 10 s peer connect timeout (was 3), 8-strike failcount (was 3),
  TCP-only outgoing. Without it sparse swarms die inside 15 s and are never re-queried.

- `0002-desktop-download-window.patch` — rolling selection (download window ahead of the reader) ceiling
  15 MiB → 1 GiB. The effective window is min(ceiling, memory cache), and `nuvioEngineWindowBytes` sets the
  cache per playback buffer preset (Metered 64 / Low Data 128 / Balanced 256 / Resilient 512 MiB). At
  15 MiB a 16 MiB-piece release had less than one piece in flight: 1 MB/s with 13 seeds before, 5.6 MB/s
  with 3 seeds after (at 256 MiB).

- `0003-desktop-request-timeout.patch` — block request timeout 4 s → 15 s (4 s snubbed fast seeds under deep
  queues: 2 Mbps/seed vs 14; 60 s let a dead peer pin a block for a minute) and `prioritize_partial_pieces`.
- `0004-desktop-blocking-piece-hotswap.patch` — the big one. libtorrent never duplicates a block request outside
  end-game/"busy" mode, and busy mode is gated on an average piece time that is zero until a piece completes, so
  the piece the player waits on gets fully assigned to whichever slow peers exist first and fast seeds arriving
  later cannot touch it (free=0) — 38–170 s per 16 MiB piece measured. The bridge now runs a hotswap on the
  session thread via the internal torrent (as libtorrent's own cancel_non_critical_pieces does): force-cancels
  the blocking piece's blocks on any peer ≥4× slower than the fastest unchoked one and promotes the rest to the
  front of their holders' queues. Hole seeks: 1.4–3 s once a fast seed is connected. Also: completion via
  piece_finished_alert (NUVIO_ENGINE_PIECE_DEADLINES=0 switches to the plain picker), and a diagnostic dump
  (NUVIO_ENGINE_DEBUG_BLOCKING=<file>) of libtorrent's view of the blocking piece once a second.

Probes: `desktopTest --tests *NuvioEngineNetworkProbeTest -Pnuvio.p2pProbeHash=<hash> [-Pnuvio.p2pProbePreloadMb=64]`
(linear read from a fresh engine) and `*NuvioEngineSeekProbeTest -Pnuvio.p2pSeekProbeHash=<hash>
-Pnuvio.p2pSeekProbeOffsetsMb=0,tail,5000` (replays mpv-style seeks against the app's real engine state; close
Nuvio first). `-Pnuvio.engineEnv=NAME=value;NAME2=value` passes env to the engine. Always `--rerun`: the DLL is not a
test input.

`jni_bridge.cpp` here is the engine's Android bridge with one line changed (empty TLS bundle path
→ nullptr so Windows uses the system certificate store). Re-copy and re-apply on an engine bump.
