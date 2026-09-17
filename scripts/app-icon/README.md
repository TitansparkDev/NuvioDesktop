# App icon

`composeApp/src/desktopMain/resources/icons/nuvio-app-icon.{ico,png,icns}` are generated
from the official Nuvio glyph, reskinned as a night sky so the HTPC build is not mistaken
for the official app (whose icon picker also re-icons any shortcut named `Nuvio.lnk`).

Source: upstream's 1024px iOS master, `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png`
on `upstream/Dev` (flat black background; `extract.py` recovers the alpha).

```
git show upstream/Dev:iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png > up_ios_1024.png
python extract.py     # -> official_1024_rgba.png
python export_g.py    # -> final/nuvio-app-icon.{ico,png,icns}
```

Needs Pillow + numpy. `reskin.py` / `reskin2.py` hold the earlier colour variants (A–I);
the shipped icon is variant G (dark body, stars across the whole glyph, rim 0.55) with the
silver edge from `border.py` — a lit metallic band straddling the silhouette so the dark glyph
still has a contour on a dark taskbar. The band is re-cut per ICO frame (`edge_for`) so it is
about one device pixel at 16–48px and a thin 2.4% rim on the large layers; the 1080 PNG, which
the app only ever shows at 16–64px (tray + window/taskbar icon, see `DesktopTrayIconImage.kt`),
carries a 3.2% band for the same reason.
