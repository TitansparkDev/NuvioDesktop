"""Recover an RGBA glyph from the official 1024px icon that sits on flat black.

Background = the near-black region connected to the corners. The inner black
triangle is enclosed, so it stays opaque. Edge pixels were composited over
black (c = c_true * a), so alpha is estimated from brightness relative to the
nearby solid interior and the colour is un-premultiplied.
"""
from PIL import Image, ImageFilter
import numpy as np
from collections import deque
import os

D = os.path.dirname(__file__)
src = Image.open(os.path.join(D, "up_ios_1024.png")).convert("RGB")
rgb = np.asarray(src).astype(np.float32)
H, W, _ = rgb.shape
bright = rgb.max(axis=2)

# flood fill background from corners (tolerance for compression noise)
bg = np.zeros((H, W), bool)
thresh = 10.0
q = deque([(0, 0), (0, W - 1), (H - 1, 0), (H - 1, W - 1)])
for y, x in q:
    bg[y, x] = True
while q:
    y, x = q.popleft()
    for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
        if 0 <= ny < H and 0 <= nx < W and not bg[ny, nx] and bright[ny, nx] < thresh:
            bg[ny, nx] = True
            q.append((ny, nx))

fg = ~bg
fg_img = Image.fromarray((fg * 255).astype(np.uint8))
solid = np.asarray(fg_img.filter(ImageFilter.MinFilter(5))) > 0      # eroded interior
band = fg & ~solid                                                     # edge band

# reference colour for edge pixels: blurred weighted average of solid interior
solid_f = solid.astype(np.float32)
ref_num = np.stack([np.asarray(Image.fromarray((rgb[..., c] * solid_f).astype(np.uint8)).filter(ImageFilter.BoxBlur(4)), dtype=np.float32) for c in range(3)], axis=2)
ref_den = np.asarray(Image.fromarray((solid_f * 255).astype(np.uint8)).filter(ImageFilter.BoxBlur(4)), dtype=np.float32) / 255.0
ref = ref_num / np.maximum(ref_den, 1e-3)[..., None]

alpha = np.zeros((H, W), np.float32)
alpha[solid] = 1.0
ref_b = ref.max(axis=2)
a_edge = np.clip(bright / np.maximum(ref_b, 1.0), 0, 1)
alpha[band] = a_edge[band]

out = np.zeros((H, W, 4), np.float32)
out[..., :3] = rgb
m = band & (alpha > 0.02)
out[..., :3][m] = np.clip(rgb[m] / alpha[m][:, None], 0, 255)
out[..., 3] = alpha * 255
img = Image.fromarray(out.astype(np.uint8), "RGBA")
img.save(os.path.join(D, "official_1024_rgba.png"))
print("bbox", img.getbbox(), "band px", int(band.sum()))
