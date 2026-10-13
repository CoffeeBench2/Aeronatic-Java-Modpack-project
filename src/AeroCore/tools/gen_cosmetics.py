"""
Draws AeroCore's cosmetic textures (1.4.0): one wing per style, plus the aura models' textures.
Reproducible from source:  py src/AeroCore/tools/gen_cosmetics.py
Output: src/AeroCore/src/main/resources/assets/coffeesaerosmp_core/textures/cosmetics/*.png
        and a contact sheet in src/AeroCore/build/cosmetics-preview.png for review.

A wing texture is ONE wing (the renderer mirrors it for the other side): the root is on the left edge at
y = ROOT_Y (70% down, where the renderer puts the shoulder), the tip toward the right. Anything transparent is
not drawn, so the silhouette IS the wing shape.
"""
import math
import os
import random

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "coffeesaerosmp_core", "textures", "cosmetics")
PREVIEW = os.path.join(HERE, "..", "build", "cosmetics-preview.png")
W = H = 64
ROOT_Y = 45


def rgba(h, a=255):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, a)


def lerp_rgb(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(((a >> s) & 255) * (1 - t) + ((b >> s) & 255) * t) for s in (16, 8, 0))


def bezier(p0, p1, p2, t):
    u = 1 - t
    return (u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0], u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1])


EDGE = ((1, ROOT_Y - 4), (26, 12), (62, 3))          # the leading edge: shoulder, wrist, tip


def feather(d, base, angle_deg, length, width, fill_fn, outline):
    a = math.radians(angle_deg)
    dx, dy = math.cos(a), math.sin(a)
    px, py = -dy, dx
    steps = max(2, int(length))
    for i in range(steps):                                  # build the vane from many short slices: tapered + shaded
        t = i / (steps - 1)
        w = width * (1 - 0.65 * t ** 1.6)
        cx, cy = base[0] + dx * length * t, base[1] + dy * length * t
        col = fill_fn(t)
        d.polygon([(cx + px * w, cy + py * w), (cx - px * w, cy - py * w),
                   (cx - px * w + dx * 1.2, cy - py * w + dy * 1.2), (cx + px * w + dx * 1.2, cy + py * w + dy * 1.2)],
                  fill=col)
    tip = (base[0] + dx * length, base[1] + dy * length)
    d.line([base, tip], fill=outline, width=1)              # the shaft


def feathered(c_base, c_mid, c_tip, shaft, alpha=255, coverts=None, seed=1):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    n = 12
    for i in range(n - 1, -1, -1):                            # flight feathers, tip first so the root ones overlap
        t = i / (n - 1)
        b = bezier(*EDGE, 0.08 + 0.92 * t)
        length = 16 + 30 * t ** 1.2
        ang = 100 - 62 * t + rnd.uniform(-3, 3)

        def fill(u, t=t):
            return lerp_rgb(c_mid, c_tip, max(0, (u - 0.55) / 0.45)) + (alpha,) if u > 0.55 else lerp_rgb(c_base, c_mid, u / 0.55) + (alpha,)
        feather(d, b, ang, length, 3.2, fill, rgba(shaft, alpha))
    cv = coverts or c_base
    for i in range(n * 2 - 1, -1, -1):                        # coverts: a short, lighter layer along the edge
        t = i / (n * 2 - 1)
        b = bezier(*EDGE, 0.04 + 0.9 * t)
        feather(d, b, 104 - 60 * t, 7 + 6 * t, 2.6, lambda u: lerp_rgb(cv, c_base, u) + (alpha,), rgba(shaft, alpha))
    pts = [bezier(*EDGE, k / 40) for k in range(41)]          # the bone along the leading edge
    d.line(pts, fill=rgba(shaft, alpha), width=2)
    d.line([(x, y - 1) for x, y in pts], fill=lerp_rgb(cv, 0xFFFFFF, 0.5) + (alpha,), width=1)
    return im


def bat(c_membrane, c_vein, c_bone):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    root, wrist = (1, ROOT_Y - 3), (28, 12)
    tips = [(63, 4), (60, 30), (46, 50), (26, 60), (8, 58)]
    poly = [root, wrist]
    for k, tp in enumerate(tips):
        poly.append(tp)
        if k + 1 < len(tips):                                # scallop: the membrane sags toward the wrist between fingers
            nx = (tp[0] + tips[k + 1][0]) / 2
            ny = (tp[1] + tips[k + 1][1]) / 2
            poly.append((nx + (wrist[0] - nx) * 0.28, ny + (wrist[1] - ny) * 0.28))
    poly.append((1, ROOT_Y + 6))
    d.polygon(poly, fill=rgba(c_membrane))
    for y in range(H):                                       # a darker band toward the trailing edge
        for x in range(W):
            if im.getpixel((x, y))[3] and (x + y * 1.4) % 9 < 1:
                im.putpixel((x, y), rgba(c_vein))
    for tp in tips:
        d.line([wrist, tp], fill=rgba(c_bone), width=2)
    d.line([root, wrist], fill=rgba(c_bone), width=3)
    d.ellipse([wrist[0] - 2, wrist[1] - 2, wrist[0] + 2, wrist[1] + 2], fill=rgba(c_bone))
    d.polygon([(wrist[0] - 1, wrist[1] - 2), (wrist[0] - 4, wrist[1] - 8), (wrist[0] + 1, wrist[1] - 3)], fill=rgba(0xD8C8A8))  # claw
    return im


def butterfly():
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    fore = [(1, ROOT_Y - 4), (14, 14), (34, 3), (60, 2), (63, 12), (50, 30), (24, ROOT_Y - 2)]
    hind = [(1, ROOT_Y - 1), (26, ROOT_Y - 4), (40, 52), (32, 62), (12, 60), (2, ROOT_Y + 8)]
    for poly in (hind, fore):
        d.polygon(poly, fill=rgba(0x1A1210))                  # black border
        cx = sum(p[0] for p in poly) / len(poly)
        cy = sum(p[1] for p in poly) / len(poly)
        inner = [(cx + (x - cx) * 0.82, cy + (y - cy) * 0.82) for x, y in poly]
        d.polygon(inner, fill=rgba(0xF28C28))
        for x, y in poly[1:-1]:                               # veins from the root
            d.line([(1, ROOT_Y - 2), (cx + (x - cx) * 0.8, cy + (y - cy) * 0.8)], fill=rgba(0x2A1A12), width=1)
    for (x, y) in [(58, 6), (52, 4), (61, 11), (45, 3), (30, 60), (24, 61), (36, 55)]:
        d.point((x, y), fill=rgba(0xFFFFFF))                  # white dots on the border
    d.ellipse([40, 8, 47, 14], fill=rgba(0xFFB65C))           # a light patch
    return im


def mechanical():
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    spar = [bezier(*EDGE, k / 30) for k in range(31)]
    ribs = []
    for k, t in enumerate([0.18, 0.4, 0.62, 0.82, 1.0]):
        top = bezier(*EDGE, t)
        foot = (top[0] - 6 + t * 10, top[1] + 18 + 20 * (1 - t) * 0.5 + 8 * t)
        ribs.append((top, foot))
    panels = []
    prev = ((2, ROOT_Y - 4), (2, ROOT_Y + 8))
    for top, foot in ribs:                                   # canvas panels between ribs, stitched
        panels.append([prev[0], top, foot, prev[1]])
        prev = (top, foot)
    for k, poly in enumerate(panels):
        d.polygon(poly, fill=rgba(0xE8DCC0 if k % 2 == 0 else 0xD9C9A6))
        d.line([((poly[0][0] + poly[3][0]) / 2, (poly[0][1] + poly[3][1]) / 2),
                ((poly[1][0] + poly[2][0]) / 2, (poly[1][1] + poly[2][1]) / 2)], fill=rgba(0xBFAE88), width=1)
    for top, foot in ribs:
        d.line([top, foot], fill=rgba(0x8A6420), width=2)
        d.line([top, foot], fill=rgba(0xC9973B), width=1)
        d.point(foot, fill=rgba(0xFFE7A0))
    d.line(spar, fill=rgba(0x8A6420), width=3)
    d.line([(x, y - 1) for x, y in spar], fill=rgba(0xF0C05A), width=1)
    for t in (0.18, 0.4, 0.62, 0.82):                         # rivets on the spar
        x, y = bezier(*EDGE, t)
        d.point((x, y), fill=rgba(0xFFF3C4))
    return im


def crystal(seed):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    for i in range(14, -1, -1):
        t = i / 14
        b = bezier(*EDGE, 0.05 + 0.95 * t)
        length = 14 + 30 * t
        a = math.radians(100 - 60 * t + rnd.uniform(-4, 4))
        dx, dy = math.cos(a), math.sin(a)
        px, py = -dy, dx
        w = 4.5 - 1.5 * t
        tip = (b[0] + dx * length, b[1] + dy * length)
        mid = (b[0] + dx * length * 0.55, b[1] + dy * length * 0.55)
        shade = 225 + int(30 * ((i % 3) / 2))
        d.polygon([b, (mid[0] + px * w, mid[1] + py * w), tip], fill=(shade, shade, 255, 205))
        d.polygon([b, (mid[0] - px * w, mid[1] - py * w), tip], fill=(shade - 30, shade - 25, 255, 205))
        d.line([b, tip], fill=(255, 255, 255, 235), width=1)
    pts = [bezier(*EDGE, k / 30) for k in range(31)]
    d.line(pts, fill=(255, 255, 255, 240), width=2)
    return im


def sail():
    """A Create windmill sail blade: wooden frame, canvas, cross battens. 16 x 48."""
    im = Image.new("RGBA", (16, 48), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 47], fill=rgba(0x6B4A2A))
    d.rectangle([2, 2, 13, 45], fill=rgba(0xEFE8DA))
    for y in range(2, 46, 2):
        d.line([2, y, 13, y], fill=rgba(0xE2D9C6))
    for y in (12, 24, 36):
        d.rectangle([0, y, 15, y + 1], fill=rgba(0x8A6440))
    d.line([7, 0, 7, 47], fill=rgba(0x5A3C20))
    for (x, y) in [(1, 1), (14, 1), (1, 46), (14, 46)]:
        d.point((x, y), fill=rgba(0xC9973B))
    return im


def cog(teeth=8, size=32):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = (size - 1) / 2
    r_out, r_in = size * 0.47, size * 0.36
    for k in range(teeth):
        a = k * 2 * math.pi / teeth
        pts = []
        for da, rr in [(-0.16, r_in), (-0.11, r_out), (0.11, r_out), (0.16, r_in)]:
            pts.append((c + math.cos(a + da) * rr, c + math.sin(a + da) * rr))
        d.polygon(pts, fill=rgba(0xC9973B), outline=rgba(0x8A6420))
    d.ellipse([c - r_in, c - r_in, c + r_in, c + r_in], fill=rgba(0xE3B04B), outline=rgba(0x8A6420))
    d.ellipse([c - r_in * 0.55, c - r_in * 0.55, c + r_in * 0.55, c + r_in * 0.55], fill=rgba(0x6B4A1A))
    d.ellipse([c - r_in * 0.25, c - r_in * 0.25, c + r_in * 0.25, c + r_in * 0.25], fill=rgba(0x3A2410))
    d.arc([c - r_in + 2, c - r_in + 2, c + r_in - 2, c + r_in - 2], 200, 290, fill=rgba(0xFFE7A0))
    return im


def halo():
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    px = im.load()
    for y in range(32):
        for x in range(32):
            r = math.hypot(x - 15.5, y - 15.5)
            if 9.5 <= r <= 14.5:
                core = 1 - abs(r - 12.0) / 2.5
                col = lerp_rgb(0xC9973B, 0xFFF3B0, core)
                px[x, y] = col + (int(150 + 105 * core),)
            elif 8.0 <= r < 9.5 or 14.5 < r <= 15.5:
                px[x, y] = (255, 230, 150, 60)
    return im


def propeller():
    """Top-down view of a two-blade Aeronautics wooden propeller with a brass hub."""
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for sgn in (-1, 1):
        d.polygon([(16, 15), (16 + sgn * 14, 12), (16 + sgn * 15, 15), (16 + sgn * 14, 19), (16, 17)],
                  fill=rgba(0x9C6B3A), outline=rgba(0x5A3A1E))
        d.line([(16, 16), (16 + sgn * 14, 15)], fill=rgba(0xC08A50))
    d.ellipse([12, 12, 19, 19], fill=rgba(0xC9973B), outline=rgba(0x8A6420))
    d.point((15, 15), fill=rgba(0xFFF3C4))
    return im


def cloud():
    im = Image.new("RGBA", (16, 16))
    rnd = random.Random(5)
    px = im.load()
    for y in range(16):
        for x in range(16):
            v = 236 - y * 3 + rnd.randint(-8, 8)
            px[x, y] = (v, v, min(255, v + 6), 235)
    return im


STYLES = {
    "seraph": lambda: feathered(0xFFFFFF, 0xF2EEE6, 0xF0D27A, 0xCFC6B4, coverts=0xFFFFFF, seed=3),
    "phoenix": lambda: feathered(0xFFE07A, 0xFF8A1F, 0xE0240C, 0xA8320A, coverts=0xFFF0A0, seed=5),
    "frost": lambda: feathered(0xEAF8FF, 0xB8E4FF, 0xFFFFFF, 0x7FB8E0, alpha=225, coverts=0xFFFFFF, seed=7),
    "wyvern": lambda: bat(0x5A1A22, 0x48141B, 0x24100F),
    "monarch": butterfly,
    "ornithopter": mechanical,
    "prism": lambda: crystal(11),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    made = {}
    for name, fn in STYLES.items():
        made["wings_" + name] = fn()
    made["sail"] = sail()
    made["cog"] = cog()
    made["halo"] = halo()
    made["propeller"] = propeller()
    made["cloud"] = cloud()
    for name, img in made.items():
        img.save(os.path.join(OUT, name + ".png"))
    # contact sheet (each 4x, on a sky colour, wings shown as a mirrored pair)
    sheet = Image.new("RGBA", (len(STYLES) * 2 * W * 2 + 40, H * 4 + 40), (110, 150, 200, 255))
    x = 10
    for name in STYLES:
        img = made["wings_" + name].resize((W * 2, H * 2), Image.NEAREST)
        sheet.alpha_composite(img.transpose(Image.FLIP_LEFT_RIGHT), (x, 10))
        sheet.alpha_composite(img, (x + W * 2, 10))
        x += W * 4 + 4
    x = 10
    for name in ("sail", "cog", "halo", "propeller", "cloud"):
        img = made[name]
        img = img.resize((img.width * 4, img.height * 4), Image.NEAREST)
        sheet.alpha_composite(img, (x, H * 2 + 20))
        x += img.width + 10
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    sheet.save(PREVIEW)
    print("wrote", len(made), "textures to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
