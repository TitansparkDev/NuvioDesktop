"""Silver edge for the taskbar: a thin, slightly lit metallic rim straddling the glyph's outline.

Dark-on-dark is what made the night-sky icon vanish on the Windows taskbar. The band is drawn
around the *silhouette* (not the play mark), half outside the old edge and half over the blue
rim light, so the shape gets a hard bright contour without growing much.
"""
from PIL import Image
import numpy as np


def _grow(mask, steps):
    """Iteration index at which each pixel is first reached by dilating `mask`; `steps` where never.

    Alternating 8- and 4-neighbourhood dilation approximates a Euclidean disk (octagonal).
    """
    dist = np.full(mask.shape, steps, np.int32)
    dist[mask] = 0
    cur = mask.copy()
    for k in range(1, steps + 1):
        nxt = cur.copy()
        nxt[1:, :] |= cur[:-1, :]
        nxt[:-1, :] |= cur[1:, :]
        nxt[:, 1:] |= cur[:, :-1]
        nxt[:, :-1] |= cur[:, 1:]
        if k % 2 == 0:
            nxt[1:, 1:] |= cur[:-1, :-1]
            nxt[1:, :-1] |= cur[:-1, 1:]
            nxt[:-1, 1:] |= cur[1:, :-1]
            nxt[:-1, :-1] |= cur[1:, 1:]
        new = nxt & ~cur
        dist[new] = k
        cur = nxt
        if not new.any():
            break
    return dist


def signed_distance(alpha, reach):
    """+ outside the silhouette, - inside, in px (capped at ±reach)."""
    inside = alpha > 0.5
    d_out = _grow(inside, reach)
    d_in = _grow(~inside, reach)
    return np.where(inside, -d_in, d_out).astype(np.float32)


def silver_border(img, width, outside=0.6, strength=0.92, sdf=None):
    """Returns (bordered RGBA image, signed distance field).

    width   : total band thickness in px; `outside` of it lies beyond the old edge.
    strength: band opacity (its lit top-left is brighter than the shaded bottom-right).
    """
    arr = np.asarray(img).astype(np.float32) / 255.0
    a = arr[..., 3]
    h, w = a.shape
    if sdf is None:
        sdf = signed_distance(a, int(width) + 2)
    lo = -width * (1.0 - outside)
    hi = width * outside
    band = np.clip(np.minimum(sdf - lo, hi - sdf) + 0.5, 0, 1)  # 1 inside the band, AA at both edges
    # glyph pixels outside the band would otherwise poke through where alpha < 0.5
    band *= strength

    yy, xx = np.mgrid[0:h, 0:w]
    light = 1.0 - ((xx / w) * 0.55 + (yy / h) * 0.55)          # 1 top-left → -0.1 bottom-right
    light = np.clip(light, 0.0, 1.0)
    bright = np.array([0.90, 0.92, 0.96], np.float32)
    shade = np.array([0.58, 0.61, 0.68], np.float32)
    silver = shade + (bright - shade) * light[..., None]

    # silver band OVER the glyph (straight-alpha "over": colour weighted by each layer's coverage)
    out_a = band + a * (1 - band)
    glyph_w = (a * (1 - band))[..., None]
    out_rgb = (silver * band[..., None] + arr[..., :3] * glyph_w) / np.maximum(out_a, 1e-6)[..., None]
    res = np.concatenate([np.clip(out_rgb, 0, 1), np.clip(out_a, 0, 1)[..., None]], axis=2)
    return Image.fromarray((res * 255 + 0.5).astype(np.uint8), "RGBA"), sdf
