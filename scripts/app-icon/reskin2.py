"""Darker 'real night sky' reskins: near-black navy body, the glyph's own overlay
band reused as a faint milky-way haze, and a dense field of small stars.
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import numpy as np
import os, random
from reskin import rgb_to_hsv, hsv_to_rgb, base, rgb, a, H, S, V, body, inner_black, t

D = os.path.dirname(__file__)
Hh, Ww = a.shape


def dark_sky(v_lo, v_hi, hue=(226, 240), sat=(0.55, 0.45), haze_gain=1.0):
    """Body V is remapped from its own [min,max] into [v_lo, v_hi] so the overlay
    band keeps its contrast even when everything is nearly black."""
    vb = V[body]
    vmin, vmax = np.percentile(vb, 2), np.percentile(vb, 98)
    vn = np.clip((V - vmin) / (vmax - vmin), 0, 1) ** (1.0 / haze_gain)
    v2 = v_lo + vn * (v_hi - v_lo)
    h2 = hue[0] + t * (hue[1] - hue[0])
    s2 = sat[0] + t * (sat[1] - sat[0])
    out = rgb.copy()
    new = hsv_to_rgb(h2, np.clip(s2, 0, 1), np.clip(v2, 0, 1))
    out[body] = new[body]
    return out


def rim_light(out, strength):
    """Thin lighter edge along the outer silhouette so the shape reads on dark UI."""
    if strength <= 0:
        return out
    am = Image.fromarray((a * 255).astype(np.uint8))
    inner = np.asarray(am.filter(ImageFilter.MinFilter(9))).astype(np.float32) / 255
    edge = np.clip(a - inner, 0, 1) * body
    # softer on the bottom-right, brighter top-left, like the original's lighting
    yy, xx = np.mgrid[0:Hh, 0:Ww]
    light = np.clip(1.0 - ((xx / Ww) * 0.6 + (yy / Hh) * 0.6), 0.25, 1.0)
    glow = np.asarray(Image.fromarray((edge * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2))).astype(np.float32) / 255
    k = (glow * light * strength)[..., None]
    tint = np.array([0.55, 0.65, 0.9], np.float32)
    return np.clip(out + k * tint, 0, 1)


def star_field(size, region_mask, weight, n, seed, scale=1.0):
    """Many small stars. Mostly faint pinpoints, a few bright ones with glow,
    density following `weight` (the haze band gets more)."""
    rng = np.random.default_rng(seed)
    m = Image.fromarray((region_mask * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(15))
    ok = np.asarray(m) > 0
    ys, xs = np.nonzero(ok)
    w = weight[ys, xs]; w = w / w.sum()
    idx = rng.choice(len(xs), size=n, replace=False, p=w)
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    glow = Image.new("RGBA", size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in idx:
        x, y = float(xs[i]) + rng.uniform(-0.5, 0.5), float(ys[i]) + rng.uniform(-0.5, 0.5)
        u = rng.random()
        if u < 0.62:
            r, al = rng.uniform(0.9, 1.5) * scale, int(rng.uniform(70, 150))
        elif u < 0.90:
            r, al = rng.uniform(1.5, 2.4) * scale, int(rng.uniform(140, 220))
        else:
            r, al = rng.uniform(2.4, 3.6) * scale, 255
            gd.ellipse((x - r * 4, y - r * 4, x + r * 4, y + r * 4), fill=(190, 210, 255, 70))
        c = rng.random()
        col = (215, 228, 255) if c < 0.6 else ((255, 250, 235) if c < 0.85 else (255, 232, 210))
        d.ellipse((x - r, y - r, x + r, y + r), fill=col + (al,))
    glow = glow.filter(ImageFilter.GaussianBlur(6 * scale))
    layer.alpha_composite(glow)
    return layer


def compose(out, stars_layers):
    img = Image.fromarray((np.clip(np.concatenate([out, a[..., None]], axis=2), 0, 1) * 255).astype(np.uint8), "RGBA")
    for L in stars_layers:
        img.alpha_composite(L)
    return img


weight = 0.35 + V  # denser where the glyph's lighter band is → milky way
size = (Ww, Hh)
variants = {}

# F — darkest: body 4–14% brightness, stars on the body only, triangle stays pure black.
o = rim_light(dark_sky(0.05, 0.16), 0.35)
variants["F_dark"] = compose(o, [star_field(size, body & ~inner_black, weight, 170, 11)])

# G — same darkness, stars across the whole glyph (triangle included) so it's one sky.
o = rim_light(dark_sky(0.05, 0.16), 0.35)
variants["G_dark_full"] = compose(o, [star_field(size, body & ~inner_black, weight, 170, 11),
                                      star_field(size, inner_black, np.ones_like(V), 28, 5, scale=0.8)])

# H — a step lighter (7–24%) for taskbar legibility, same field.
o = rim_light(dark_sky(0.07, 0.24), 0.3)
variants["H_dusk"] = compose(o, [star_field(size, body & ~inner_black, weight, 170, 11)])

# I — darkest with a stronger haze band and slightly more/brighter stars.
o = rim_light(dark_sky(0.04, 0.18, haze_gain=1.6), 0.4)
variants["I_dark_haze"] = compose(o, [star_field(size, body & ~inner_black, weight ** 2, 220, 23, scale=1.1)])

for k, im in variants.items():
    im.save(os.path.join(D, f"variant_{k}.png"))

# ---- sheet ----
TASKBAR = (32, 32, 32, 255); LIGHT = (243, 243, 243, 255)
cols = ["official"] + list(variants.keys())
imgs = [base] + list(variants.values())
cell_w = 330
sheet = Image.new("RGBA", (cell_w * len(cols), 620), TASKBAR)
d = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype("segoeui.ttf", 20)
except Exception:
    font = ImageFont.load_default()
for i, (name, im) in enumerate(zip(cols, imgs)):
    x0 = i * cell_w
    d.text((x0 + 20, 10), name.replace("_", " "), fill=(230, 230, 230, 255), font=font)
    sheet.alpha_composite(im.resize((288, 288), Image.LANCZOS), (x0 + 20, 40))
    xs = x0 + 20
    for s in (64, 48, 32, 24, 16):
        sheet.alpha_composite(im.resize((s, s), Image.LANCZOS), (xs, 345 + (64 - s) // 2))
        xs += s + 14
    light = Image.new("RGBA", (cell_w - 20, 150), LIGHT)
    sheet.paste(light, (x0 + 10, 450))
    xs = x0 + 20
    for s in (64, 48, 32, 24, 16):
        sheet.alpha_composite(im.resize((s, s), Image.LANCZOS), (xs, 450 + (150 - s) // 2))
        xs += s + 14
    sheet.alpha_composite(im.resize((120, 120), Image.LANCZOS), (x0 + 200, 465))
sheet.convert("RGB").save(os.path.join(D, "sheet2.png"))
sheet.crop((0, 40, sheet.width, 335)).convert("RGB").save(os.path.join(D, "sheet2_big.png"))
print("ok", list(variants))
