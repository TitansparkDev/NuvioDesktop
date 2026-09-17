"""Night-sky reskins of the official Nuvio glyph.

The body is a hue gradient cyan (~185°) → blue → purple → magenta (~320°) with a
lighter overlay band. Body pixels get their hue position t∈[0,1] along that
range remapped onto a new (hue, sat×, val×) ramp; the black inner triangle and
white play mark are left untouched (they have ~no saturation).
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import numpy as np
import os, random

D = os.path.dirname(__file__)
base = Image.open(os.path.join(D, "official_1024_rgba.png")).convert("RGBA")
arr = np.asarray(base).astype(np.float32) / 255.0
rgb, a = arr[..., :3], arr[..., 3]


def rgb_to_hsv(c):
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    mx = c.max(axis=2); mn = c.min(axis=2); d = mx - mn
    h = np.zeros_like(mx)
    nz = d > 1e-6
    rm = nz & (mx == r); gm = nz & (mx == g) & ~rm; bm = nz & ~rm & ~gm
    h[rm] = ((g - b)[rm] / d[rm]) % 6
    h[gm] = (b - r)[gm] / d[gm] + 2
    h[bm] = (r - g)[bm] / d[bm] + 4
    h = h * 60
    s = np.where(mx > 1e-6, d / np.maximum(mx, 1e-6), 0)
    return h, s, mx


def hsv_to_rgb(h, s, v):
    h = h % 360
    c = v * s; x = c * (1 - np.abs((h / 60) % 2 - 1)); m = v - c
    z = np.zeros_like(h)
    i = (h // 60).astype(int)
    r = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [c, x, z, z, x, c])
    g = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [x, c, c, x, z, z])
    b = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [z, z, x, c, c, x])
    return np.stack([r + m, g + m, b + m], axis=2)


H, S, V = rgb_to_hsv(rgb)
body = (S > 0.25) & (a > 0.01)
t = np.clip((H - 185.0) / (320.0 - 185.0), 0, 1)


def ramp(stops, t):
    """stops: list of (t, value) → piecewise-linear value at t."""
    ts = np.array([s[0] for s in stops]); vs = np.array([s[1] for s in stops])
    return np.interp(t, ts, vs)


def reskin(hue_stops, sat_stops, val_stops):
    h2 = ramp(hue_stops, t); s2 = S * ramp(sat_stops, t); v2 = V * ramp(val_stops, t)
    out = rgb.copy()
    new = hsv_to_rgb(h2, np.clip(s2, 0, 1), np.clip(v2, 0, 1))
    out[body] = new[body]
    res = np.concatenate([out, a[..., None]], axis=2)
    return Image.fromarray((np.clip(res, 0, 1) * 255).astype(np.uint8), "RGBA")


def add_stars(img, region_mask, n, seed, size_range=(2.0, 6.0), sparkle_every=5):
    """Soft white stars scattered inside region_mask (bool HxW), kept clear of edges."""
    rng = random.Random(seed)
    m = Image.fromarray((region_mask * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(41))
    ok = np.asarray(m) > 0
    ys, xs = np.nonzero(ok)
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    placed = []
    tries = 0
    while len(placed) < n and tries < 5000:
        tries += 1
        i = rng.randrange(len(xs)); x, y = int(xs[i]), int(ys[i])
        if any((x - px) ** 2 + (y - py) ** 2 < 70 ** 2 for px, py in placed):
            continue
        placed.append((x, y))
        r = rng.uniform(*size_range)
        # glow
        g = Image.new("RGBA", img.size, (0, 0, 0, 0))
        gd = ImageDraw.Draw(g)
        gd.ellipse((x - r * 3, y - r * 3, x + r * 3, y + r * 3), fill=(220, 235, 255, 90))
        g = g.filter(ImageFilter.GaussianBlur(r * 1.5))
        layer.alpha_composite(g)
        d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 235))
        if len(placed) % sparkle_every == 0:
            L = r * 4
            d.line((x - L, y, x + L, y), fill=(255, 255, 255, 160), width=max(1, int(r / 2)))
            d.line((x, y - L, x, y + L), fill=(255, 255, 255, 160), width=max(1, int(r / 2)))
    out = img.copy()
    out.alpha_composite(layer)
    return out


inner_black = (V < 0.12) & (a > 0.5)   # the dark rounded triangle

variants = {}
# A. Midnight — compress cyan..magenta onto steel-blue..indigo, pull brightness down evenly.
variants["A_midnight"] = reskin(
    hue_stops=[(0, 208), (0.35, 228), (0.7, 252), (1, 270)],
    sat_stops=[(0, 0.95), (1, 1.0)],
    val_stops=[(0, 0.78), (1, 0.66)],
)
# B. Twilight — keep a brighter, slightly teal "horizon glow" where the cyan was, deep violet below.
variants["B_twilight"] = reskin(
    hue_stops=[(0, 198), (0.3, 222), (0.65, 250), (1, 272)],
    sat_stops=[(0, 0.85), (0.4, 1.0), (1, 1.0)],
    val_stops=[(0, 0.92), (0.4, 0.74), (1, 0.62)],
)
# C. Deep space — darkest; indigo body with only a faint blue lift at the top.
variants["C_deepspace"] = reskin(
    hue_stops=[(0, 218), (0.4, 238), (0.75, 258), (1, 268)],
    sat_stops=[(0, 1.0), (1, 1.05)],
    val_stops=[(0, 0.68), (1, 0.52)],
)
# D. Twilight + stars on the body.
variants["D_twilight_stars"] = add_stars(variants["B_twilight"], body & ~inner_black, n=14, seed=7)
# E. Twilight + stars inside the black triangle (a window onto the night sky).
variants["E_twilight_window"] = add_stars(variants["B_twilight"], inner_black, n=9, seed=3, size_range=(1.5, 4.5))

for k, im in variants.items():
    im.save(os.path.join(D, f"variant_{k}.png"))

# ---- preview sheet ---------------------------------------------------------
TASKBAR = (32, 32, 32, 255); LIGHT = (243, 243, 243, 255)
cols = ["official"] + list(variants.keys())
imgs = [base] + list(variants.values())
sizes = [256, 64, 32, 16]
cell_w = 300
sheet_h = 40 + 300 + 20 + 110 + 20 + 110
sheet = Image.new("RGBA", (cell_w * len(cols), sheet_h), TASKBAR)
d = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype("segoeui.ttf", 20)
except Exception:
    font = ImageFont.load_default()
for i, (name, im) in enumerate(zip(cols, imgs)):
    x0 = i * cell_w
    d.text((x0 + 20, 10), name.replace("_", " "), fill=(230, 230, 230, 255), font=font)
    big = im.resize((256, 256), Image.LANCZOS)
    sheet.alpha_composite(big, (x0 + 22, 44))
    # small sizes on dark
    xs = x0 + 22
    for s in sizes[1:]:
        sm = im.resize((s, s), Image.LANCZOS)
        sheet.alpha_composite(sm, (xs, 320 + (64 - s) // 2))
        xs += s + 16
    # small sizes on light
    light = Image.new("RGBA", (cell_w - 20, 110), LIGHT)
    sheet.paste(light, (x0 + 10, 450))
    xs = x0 + 22
    for s in sizes:
        if s == 256:
            continue
        sm = im.resize((s, s), Image.LANCZOS)
        sheet.alpha_composite(sm, (xs, 450 + (110 - s) // 2))
        xs += s + 16
    sm = im.resize((96, 96), Image.LANCZOS)
    sheet.alpha_composite(sm, (x0 + 170, 457))
sheet.convert("RGB").save(os.path.join(D, "sheet.png"))
print("ok", list(variants))
