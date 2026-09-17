"""Final: variant G with a stronger rim and a silver edge → .ico / .png (1080) / .icns."""
from PIL import Image, ImageDraw
import numpy as np
import os
from reskin import base, V, a, body, inner_black
from reskin2 import dark_sky, rim_light, star_field, compose, weight
from border import silver_border, signed_distance

D = os.path.dirname(__file__)
OUT = os.path.join(D, "final")
os.makedirs(OUT, exist_ok=True)
Hh, Ww = a.shape
size = (Ww, Hh)

o = rim_light(dark_sky(0.05, 0.16), 0.55)
img = compose(o, [
    star_field(size, body & ~inner_black, weight, 170, 11),
    star_field(size, inner_black, np.ones_like(V), 28, 5, scale=0.8),
])
img.save(os.path.join(OUT, "master_1024_rgba.png"))

# Silver edge, sized per output. Widths are fractions of the glyph's height in the master (759px)
# so a frame that Windows shows at N px gets a contour of about one device pixel, while the big
# sizes keep a thinner band that reads as a rim rather than a frame.
glyph_h = img.getbbox()[3] - img.getbbox()[1]
sdf = signed_distance(a, 90)
_bordered = {}


def bordered(frac):
    w = round(glyph_h * frac)
    if w not in _bordered:
        _bordered[w] = silver_border(img, w, sdf=sdf)[0]
    return _bordered[w]


LARGE_EDGE = 0.024    # 64px+ frames, the 256 ICO layer, ICNS
RUNTIME_EDGE = 0.032  # the 1080 PNG: only ever shown at 16-64px (tray, window/taskbar icon)


def edge_for(frame_px, fill):
    """One device pixel of edge at small sizes, never thinner than LARGE_EDGE."""
    return max(LARGE_EDGE, 1.0 / (frame_px * fill))


bordered(LARGE_EDGE).save(os.path.join(OUT, "master_1024_silver.png"))


def framed(img, canvas, fill):
    """Crop to the glyph and centre it on a square canvas so its longer side spans `fill`."""
    g = img.crop(img.getbbox())
    scale = canvas * fill / max(g.size)
    g = g.resize((max(1, round(g.width * scale)), max(1, round(g.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.alpha_composite(g, ((canvas - g.width) // 2, (canvas - g.height) // 2))
    return out


# PNG master for the tray/window icon: same framing as the fork's existing 1080 (glyph ~79% tall).
# The app crops it back to the glyph at runtime (DesktopAppIconImages.kt) before resampling.
png = framed(bordered(RUNTIME_EDGE), 1080, 0.79)
png.save(os.path.join(OUT, "nuvio-app-icon.png"), optimize=True)

# ICO: Windows shows these directly, so frame tight like upstream's .ico (~98% tall).
ico_sizes = [256, 128, 64, 48, 40, 32, 24, 20, 16]
frames = [framed(bordered(edge_for(s, 0.98)), 1024, 0.98).resize((s, s), Image.LANCZOS) for s in ico_sizes]
frames[0].save(
    os.path.join(OUT, "nuvio-app-icon.ico"),
    format="ICO",
    sizes=[(s, s) for s in ico_sizes],
    append_images=frames[1:],
)

# ICNS (kept only so the macOS block in build.gradle.kts still resolves a file).
icns_src = framed(bordered(LARGE_EDGE), 1024, 0.86)
icns_src.save(os.path.join(OUT, "nuvio-app-icon.icns"), format="ICNS",
              sizes=[(s, s) for s in (16, 32, 64, 128, 256, 512, 1024)])

# Preview: 256 + taskbar sizes on dark and light.
ico_frame = dict(zip(ico_sizes, frames))
prev = Image.new("RGBA", (700, 330), (32, 32, 32, 255))
prev.alpha_composite(ico_frame[256], (20, 20))
x = 300
for s in (64, 48, 32, 24, 16):
    prev.alpha_composite(ico_frame[s], (x, 40 + (64 - s) // 2)); x += s + 16
light = Image.new("RGBA", (380, 120), (243, 243, 243, 255)); prev.paste(light, (300, 150))
x = 316
for s in (64, 48, 32, 24, 16):
    prev.alpha_composite(ico_frame[s], (x, 150 + (120 - s) // 2)); x += s + 16
prev.convert("RGB").save(os.path.join(OUT, "preview.png"))

ico = Image.open(os.path.join(OUT, "nuvio-app-icon.ico"))
print("ico sizes", sorted(ico.info["sizes"]))
print("png", png.size, png.getbbox())
for f in os.listdir(OUT):
    print(f, os.path.getsize(os.path.join(OUT, f)))
