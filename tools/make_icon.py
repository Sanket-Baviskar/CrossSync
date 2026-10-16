"""Renders the CrossSync app icon: two cyan sync arrows on a dark rounded square.

Usage: python tools/make_icon.py windows/src/ClipWindows/Assets/crosssync.ico docs/icon_preview.png
Writes the multi-size .ico, a size preview sheet and a 512px PNG (docs/icon_preview_512.png).
Requires Pillow.
"""
import math
import sys
from PIL import Image, ImageDraw

S = 1024  # master canvas; downscaled for each icon size


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def gradient(size, c1, c2):
    """Diagonal gradient, top-left c1 -> bottom-right c2."""
    g = Image.new("RGBA", (size, size))
    px = g.load()
    for y in range(size):
        for x in range(size):
            px[x, y] = lerp(c1, c2, (x + y) / (2 * (size - 1)))
    return g


def render(stroke, radius, head_len, head_w, margin, corner):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    # Background: dark rounded square with a subtle vertical gradient and a faint rim
    bg = gradient(S, (22, 32, 46, 255), (9, 13, 18, 255))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([margin, margin, S - margin, S - margin], corner, fill=255)
    img.paste(bg, (0, 0), mask)
    ImageDraw.Draw(img).rounded_rectangle(
        [margin, margin, S - margin, S - margin], corner, outline=(0, 212, 255, 60), width=max(4, S // 128))

    # Arrows: drawn as a white mask, then filled with the cyan -> blue accent gradient
    arrows = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(arrows)
    cx = cy = S / 2
    box = [cx - radius, cy - radius, cx + radius, cy + radius]
    gap = 62  # degrees left free at each arrow tip
    for start in (200, 20):  # top-left arc and bottom-right arc
        end = start + 180 - gap
        d.arc(box, start, end, fill=255, width=stroke)
        rmid = radius - stroke / 2
        # Arrowhead at the clockwise end, pointing along the tangent
        th = math.radians(end)
        px, py = cx + rmid * math.cos(th), cy + rmid * math.sin(th)
        tx_, ty_ = -math.sin(th), math.cos(th)      # clockwise tangent
        nx, ny = math.cos(th), math.sin(th)          # outward normal
        tip = (px + tx_ * head_len, py + ty_ * head_len)
        a = (px + nx * head_w, py + ny * head_w)
        b = (px - nx * head_w, py - ny * head_w)
        d.polygon([tip, a, b], fill=255)

    accent = gradient(S, (0, 212, 255, 255), (88, 166, 255, 255))
    img.paste(accent, (0, 0), arrows)
    return img


out_ico, out_png = sys.argv[1], sys.argv[2]
# Large sizes: refined proportions. Small sizes: heavier strokes and less margin so they stay legible.
big = render(stroke=96, radius=300, head_len=128, head_w=118, margin=48, corner=224)
small = render(stroke=150, radius=345, head_len=150, head_w=160, margin=16, corner=200)

frames = {}
for size in (256, 128, 64, 48):
    frames[size] = big.resize((size, size), Image.LANCZOS)
for size in (32, 24, 16):
    frames[size] = small.resize((size, size), Image.LANCZOS)

order = [256, 128, 64, 48, 32, 24, 16]
frames[256].save(out_ico, format="ICO", sizes=[(s, s) for s in order],
                 append_images=[frames[s] for s in order[1:]])

# Preview sheet on light and dark backgrounds, each size at 1:1 in a 270px/140px slot
sheet = Image.new("RGBA", (1300, 560), (255, 255, 255, 255))
sheet.paste(Image.new("RGBA", (1300, 280), (32, 32, 32, 255)), (0, 280))
x = 10
for s_ in order:
    for y0 in (12, 292):
        sheet.alpha_composite(frames[s_], (x, y0))
    x += s_ + 20
big.resize((512, 512), Image.LANCZOS).save(out_png.replace(".png", "_512.png"))
sheet.save(out_png)
print("ok")
