"""
Builds the Coffee's Aero SMP server resource pack (required on S3): the shop window skins, the fitting-room
stage, the mini airship model and the effect sprites used by the server-side previews.

Everything is generated here (pixel art drawn with PIL, models and fonts written as JSON), so the pack is
reproducible from source:  py src/ShopResourcePack/build_rp.py
Output: src/ShopResourcePack/build/CoffeesAeroSMP-RP-<version>.zip and its SHA-1 (printed and written to .sha1).

Custom model data numbers on minecraft:paper are a CONTRACT with the auth mod (cosmetics/RpModels.java).
Add new numbers; never renumber.
"""
import hashlib
import json
import math
import os
import random
import zipfile

from PIL import Image, ImageDraw

VERSION = "1"
PACK_FORMAT = 34                     # Minecraft 1.21.1
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "build")
NS = "aerosmp"

# ── custom model data contract (mirror: auth cosmetics/RpModels.java) ─────────
CMD = {
    "airship": 9001,
    "stage": 9002,
    "backdrop": 9003,
    "cog": 9010,
    "cloud": 9011,
    "snowflake": 9012,
    "petal": 9013,
    "steam": 9014,
    "bean": 9015,
    "flame": 9016,
    "clock_face": 9017,
    "clock_hand": 9018,
    "ribbon_aqua": 9019,
    "ribbon_violet": 9020,
    "ribbon_green": 9021,
    "ember": 9022,
    "vapour": 9023,
    "crema": 9024,
    "spark": 9025,
}

files = {}   # path in zip -> bytes


def png(img):
    import io
    b = io.BytesIO()
    img.save(b, "PNG")
    return b.getvalue()


def put_json(path, obj):
    files[path] = json.dumps(obj, indent=1).encode()


def tex(name, img):
    files[f"assets/{NS}/textures/item/{name}.png"] = png(img)


def rgba(h, a=255):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, a)


# ── sprites (16x16, transparent background) ───────────────────────────────────

def sprite_cog():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for k in range(8):                                    # teeth
        a = k * math.pi / 4
        cx, cy = 7.5 + math.cos(a) * 6, 7.5 + math.sin(a) * 6
        d.rectangle([cx - 1, cy - 1, cx + 1, cy + 1], fill=rgba(0xC9973B))
    d.ellipse([2, 2, 13, 13], fill=rgba(0xF0C05A), outline=rgba(0x8A6420))
    d.ellipse([5, 5, 10, 10], fill=(0, 0, 0, 0), outline=rgba(0x8A6420))
    d.point((4, 4), fill=rgba(0xFFF3C4))
    return im


def soft_blob(col, edge, size=16, seed=1):
    rnd = random.Random(seed)
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = im.load()
    c = (size - 1) / 2
    for y in range(size):
        for x in range(size):
            r = math.hypot(x - c, y - c) / (size / 2)
            r += rnd.uniform(-0.12, 0.12)
            if r < 0.95:
                a = 255 if r < 0.65 else int(255 * (0.95 - r) / 0.3)
                base = col if r < 0.55 else edge
                px[x, y] = rgba(base, max(0, min(255, a)))
    return im


def sprite_cloud():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for (cx, cy, r) in [(5, 9, 4.2), (10, 8, 4.8), (8, 6, 4.0), (12, 10, 3.2)]:
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - cx, y - cy)
                if d < r:
                    shade = 0xFFFFFF if y < cy else 0xDCE6F0
                    im.putpixel((x, y), rgba(shade))
    return im


def sprite_snowflake():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for k in range(3):
        a = k * math.pi / 3
        dx, dy = math.cos(a) * 6, math.sin(a) * 6
        d.line([7.5 - dx, 7.5 - dy, 7.5 + dx, 7.5 + dy], fill=rgba(0xF2FAFF), width=1)
    d.point((7, 7), fill=rgba(0xFFFFFF))
    for k in range(6):
        a = k * math.pi / 3
        d.point((round(7.5 + math.cos(a) * 4), round(7.5 + math.sin(a) * 4)), fill=rgba(0xBFE3FF))
    return im


def sprite_petal():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse([3, 4, 12, 11], fill=rgba(0xFFC4DD), outline=rgba(0xE88AB0))
    d.line([4, 10, 11, 5], fill=rgba(0xF7A8C8))
    d.point((6, 6), fill=rgba(0xFFE6F0))
    return im


def sprite_flame():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.polygon([(8, 1), (13, 9), (11, 14), (5, 14), (3, 9)], fill=rgba(0xFF6A1A))
    d.polygon([(8, 5), (11, 10), (10, 13), (6, 13), (5, 10)], fill=rgba(0xFFC447))
    d.polygon([(8, 9), (9, 12), (7, 12)], fill=rgba(0xFFF4C4))
    return im


def sprite_bean():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse([3, 4, 12, 12], fill=rgba(0x5A3620), outline=rgba(0x3A2112))
    d.arc([5, 4, 11, 12], 280, 80, fill=rgba(0x2A160B))
    d.point((5, 6), fill=rgba(0x8A5A38))
    return im


def sprite_clock_face():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse([0, 0, 15, 15], fill=rgba(0x2A1C10, 210), outline=rgba(0xC9973B))
    d.ellipse([1, 1, 14, 14], outline=rgba(0x8A6420))
    for k in range(12):
        a = k * math.pi / 6
        x, y = 7.5 + math.cos(a) * 6, 7.5 + math.sin(a) * 6
        d.point((round(x), round(y)), fill=rgba(0xF0C05A if k % 3 == 0 else 0xC9973B))
    return im


def sprite_clock_hand():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.line([7, 8, 7, 1], fill=rgba(0xF0C05A), width=1)
    d.line([8, 8, 8, 2], fill=rgba(0xC9973B), width=1)
    d.rectangle([6, 7, 9, 9], fill=rgba(0x8A6420))
    return im


def sprite_ribbon(c1, c2):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        t = y / 15
        r = int(((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t)
        g = int(((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t)
        b = int((c1 & 255) * (1 - t) + (c2 & 255) * t)
        for x in range(4, 12):
            edge = abs(x - 7.5) / 4
            a = int(235 * (1 - edge ** 3))
            im.putpixel((x, y), (r, g, b, a))
    return im


def sprite_glow(col, edge, seed):
    return soft_blob(col, edge, 16, seed)


def sprite_vapour():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for x in range(16):
        for y in range(5, 11):
            a = int(200 * (1 - abs(y - 7.5) / 3.5) * (0.5 + 0.5 * x / 15))
            im.putpixel((x, y), (255, 255, 255, max(0, a)))
    return im


def sprite_spark():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.line([7.5, 2, 7.5, 13], fill=rgba(0xFFF6D0))
    d.line([2, 7.5, 13, 7.5], fill=rgba(0xFFF6D0))
    d.line([5, 5, 10, 10], fill=rgba(0xF0C05A))
    d.line([10, 5, 5, 10], fill=rgba(0xF0C05A))
    return im


# ── stage, backdrop, balloon (bigger textures) ────────────────────────────────

def stage_top():
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = im.load()
    for y in range(64):
        for x in range(64):
            r = math.hypot(x - 31.5, y - 31.5)
            if r < 31:
                plank = ((x // 8) % 2)
                base = 0x3A2715 if plank else 0x33220F
                if r > 27:
                    base = 0xC9973B if r < 29.5 else 0x8A6420
                elif 26 < r <= 27:
                    base = 0x1A1108
                px[x, y] = rgba(base)
    return im


def backdrop():
    im = Image.new("RGBA", (64, 64))
    px = im.load()
    rnd = random.Random(7)
    for y in range(64):
        t = y / 63
        r = int(0x1C * (1 - t) + 0x4A * t)
        g = int(0x2A * (1 - t) + 0x3A * t)
        b = int(0x4A * (1 - t) + 0x2E * t)
        for x in range(64):
            px[x, y] = (r, g, b, 255)
    for _ in range(40):                                   # a few stars up top
        x, y = rnd.randrange(64), rnd.randrange(30)
        px[x, y] = (255, 240, 200, 255) if rnd.random() < 0.3 else (190, 200, 230, 255)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 63, 63], outline=rgba(0xC9973B))
    d.rectangle([1, 1, 62, 62], outline=rgba(0x8A6420))
    return im


def balloon():
    im = Image.new("RGBA", (32, 32))
    px = im.load()
    for y in range(32):
        for x in range(32):
            stripe = (x // 4) % 2
            base = 0xF3EBDD if stripe else 0xC23A30
            shade = 1 - 0.25 * (y / 31)
            r, g, b = (base >> 16) & 255, (base >> 8) & 255, base & 255
            px[x, y] = (int(r * shade), int(g * shade), int(b * shade), 255)
    return im


# ── models ────────────────────────────────────────────────────────────────────

def sprite_model(name):
    put_json(f"assets/{NS}/models/item/{name}.json",
             {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{name}"}})


def flat_model(name, texture, size=16, both=True):
    faces = {"north": {"uv": [0, 0, 16, 16], "texture": "#t"}}
    if both:
        faces["south"] = {"uv": [16, 0, 0, 16], "texture": "#t"}
    put_json(f"assets/{NS}/models/item/{name}.json", {
        "textures": {"t": texture, "particle": texture},
        "elements": [{"from": [0, 0, 8], "to": [16, 16, 8.01], "faces": faces}],
    })


def box(frm, to, tex_all, faces=("north", "south", "east", "west", "up", "down")):
    return {"from": frm, "to": to, "faces": {f: {"texture": tex_all} for f in faces}}


def airship_model():
    t = {
        "hull": "minecraft:block/spruce_planks", "keel": "minecraft:block/dark_oak_planks",
        "cabin": "minecraft:block/oak_planks", "balloon": f"{NS}:item/balloon",
        "fin": "minecraft:block/red_wool", "rope": "minecraft:block/chain", "prop": "minecraft:block/iron_block",
        "brass": "minecraft:block/gold_block", "particle": "minecraft:block/spruce_planks",
    }
    els = [
        box([2, 1, 5], [14, 4, 11], "#hull"),
        box([4, 0, 6], [12, 1, 10], "#keel"),
        box([14, 1.5, 6], [16, 3.5, 10], "#hull"),
        box([5, 4, 6.5], [9, 6.5, 9.5], "#cabin"),
        box([1, 6.5, 4], [15, 11.5, 12], "#balloon"),
        box([0, 7.5, 5], [1, 10.5, 11], "#balloon"),
        box([15, 7.5, 5], [16, 10.5, 11], "#balloon"),
        box([4, 11.5, 5], [12, 12.5, 11], "#balloon"),
        box([0, 9.5, 7.5], [2.5, 13.5, 8.5], "#fin"),
        box([0, 8.7, 5], [2.5, 9.3, 11], "#fin"),
        box([3.8, 4, 6.8], [4.2, 6.5, 7.2], "#rope"),
        box([11.8, 4, 6.8], [12.2, 6.5, 7.2], "#rope"),
        box([3.8, 4, 8.8], [4.2, 6.5, 9.2], "#rope"),
        box([11.8, 4, 8.8], [12.2, 6.5, 9.2], "#rope"),
        box([0.5, 1.5, 7.5], [1.5, 3.5, 8.5], "#prop"),
        box([0.8, 0, 7.8], [1.2, 5, 8.2], "#brass"),
        box([2, 4, 5], [14, 4.4, 5.3], "#brass"),
        box([2, 4, 10.7], [14, 4.4, 11], "#brass"),
    ]
    put_json(f"assets/{NS}/models/item/airship.json", {"textures": t, "elements": els})


def stage_model():
    put_json(f"assets/{NS}/models/item/stage.json", {
        "textures": {"top": f"{NS}:item/stage_top", "side": "minecraft:block/dark_oak_planks", "particle": "minecraft:block/dark_oak_planks"},
        "elements": [{"from": [0, 0, 0], "to": [16, 1, 16], "faces": {
            "up": {"texture": "#top", "uv": [0, 0, 16, 16]},
        }}],
    })


def backdrop_model():
    flat_model("backdrop", f"{NS}:item/backdrop", both=False)


# ── shop window skins (font glyphs) ───────────────────────────────────────────

WOOD_A, WOOD_B, INSET, EDGE_HI = 0x2A1C10, 0x1D130A, 0x120B05, 0x5C4026


def wood(im, box):
    x0, y0, x1, y1 = box
    px = im.load()
    rnd = random.Random(3)
    for y in range(y0, y1):
        for x in range(x0, x1):
            plank = ((y - y0) // 6) % 2
            base = WOOD_A if plank else WOOD_B
            n = rnd.randint(-6, 6)
            r, g, b = (base >> 16) & 255, (base >> 8) & 255, base & 255
            px[x, y] = (max(0, r + n), max(0, g + n), max(0, b + n), 255)


def slot(d, x, y):
    # Recessed like vanilla's: shadow on the top/left rims, light on the bottom/right, and a soft inner shadow
    # under the top edge so the hole reads as depth rather than a flat dark square.
    d.rectangle([x, y, x + 17, y + 17], fill=rgba(INSET))
    d.line([x, y, x + 16, y], fill=rgba(0x060302))
    d.line([x, y, x, y + 16], fill=rgba(0x060302))
    d.line([x + 1, y + 1, x + 16, y + 1], fill=rgba(0x0C0704))
    d.line([x + 1, y + 1, x + 1, y + 16], fill=rgba(0x0C0704))
    d.line([x + 2, y + 16, x + 16, y + 16], fill=rgba(0x1E140B))
    d.line([x + 16, y + 2, x + 16, y + 16], fill=rgba(0x1E140B))
    d.line([x, y + 17, x + 17, y + 17], fill=rgba(EDGE_HI))
    d.line([x + 17, y, x + 17, y + 17], fill=rgba(EDGE_HI))
    d.point((x + 17, y), fill=rgba(0x3A2816))
    d.point((x, y + 17), fill=rgba(0x3A2816))


def frame(d, w, h):
    d.rectangle([0, 0, w - 1, h - 1], outline=rgba(0x120B05))
    d.rectangle([1, 1, w - 2, h - 2], outline=rgba(0xC9973B))
    d.rectangle([2, 2, w - 3, h - 3], outline=rgba(0x8A6420))
    for (cx, cy) in [(4, 4), (w - 5, 4), (4, h - 5), (w - 5, h - 5)]:
        d.rectangle([cx - 1, cy - 1, cx + 1, cy + 1], fill=rgba(0xF0C05A))


def window(rows):
    w, h = 176, 114 + rows * 18
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    wood(im, (0, 0, w, h))
    d = ImageDraw.Draw(im)
    frame(d, w, h)
    d.rectangle([6, 3, w - 7, 14], fill=rgba(0x1A1108))           # title plate
    d.line([6, 14, w - 7, 14], fill=rgba(0x8A6420))
    for r in range(rows):
        for c in range(9):
            slot(d, 7 + c * 18, 17 + r * 18)
    inv_label = 17 + rows * 18 + 3
    d.line([6, inv_label + 9, w - 7, inv_label + 9], fill=rgba(0x8A6420))
    top = 17 + rows * 18 + 13
    for r in range(3):
        for c in range(9):
            slot(d, 7 + c * 18, top + r * 18)
    for c in range(9):
        slot(d, 7 + c * 18, top + 58)
    # "the preview is over here" — a brass arrow on the left edge
    d.polygon([(0, h // 2), (5, h // 2 - 5), (5, h // 2 + 5)], fill=rgba(0xF0C05A))
    return im


def fonts():
    files[f"assets/{NS}/textures/gui/shop6.png"] = png(window(6))
    files[f"assets/{NS}/textures/gui/shop3.png"] = png(window(3))
    put_json(f"assets/{NS}/font/shop.json", {"providers": [
        {"type": "bitmap", "file": f"{NS}:gui/shop6.png", "ascent": 13, "height": 222, "chars": [""]},
        {"type": "bitmap", "file": f"{NS}:gui/shop3.png", "ascent": 13, "height": 168, "chars": [""]},
        {"type": "space", "advances": {"": -8, "": -169}},
    ]})


# ── assemble ──────────────────────────────────────────────────────────────────

def build():
    put_json("pack.mcmeta", {"pack": {"pack_format": PACK_FORMAT,
                                      "description": "§6Coffee's Aero SMP §7shop, previews and effects"}})
    sprites = {
        "cog": sprite_cog(), "cloud": sprite_cloud(), "snowflake": sprite_snowflake(), "petal": sprite_petal(),
        "steam": sprite_glow(0xF4F4F4, 0xC8C8C8, 2), "bean": sprite_bean(), "flame": sprite_flame(),
        "clock_face": sprite_clock_face(), "clock_hand": sprite_clock_hand(),
        "ribbon_aqua": sprite_ribbon(0x2EE6D6, 0x8A4FFF), "ribbon_violet": sprite_ribbon(0x8A4FFF, 0x4CFF9A),
        "ribbon_green": sprite_ribbon(0x4CFF9A, 0x2EE6D6), "ember": sprite_glow(0xFFB347, 0xFF5A1F, 4),
        "vapour": sprite_vapour(), "crema": sprite_glow(0xF3E5D0, 0x9C6A44, 5), "spark": sprite_spark(),
    }
    for name, img in sprites.items():
        tex(name, img)
        sprite_model(name)
    tex("stage_top", stage_top())
    tex("backdrop", backdrop())
    tex("balloon", balloon())
    airship_model()
    stage_model()
    backdrop_model()
    fonts()
    overrides = [{"predicate": {"custom_model_data": n}, "model": f"{NS}:item/{name}"}
                 for name, n in sorted(CMD.items(), key=lambda kv: kv[1])]
    put_json("assets/minecraft/models/item/paper.json",
             {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/paper"}, "overrides": overrides})

    os.makedirs(OUT, exist_ok=True)
    name = f"CoffeesAeroSMP-RP-{VERSION}.zip"
    path = os.path.join(OUT, name)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(files):                        # sorted + fixed date = the same bytes every build
            info = zipfile.ZipInfo(p, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[p])
    sha1 = hashlib.sha1(open(path, "rb").read()).hexdigest()
    open(path + ".sha1", "w").write(sha1)
    print(path, os.path.getsize(path), "bytes", "sha1", sha1)
    # previews for review
    prev = os.path.join(OUT, "preview")
    os.makedirs(prev, exist_ok=True)
    for n, img in sprites.items():
        img.resize((64, 64), Image.NEAREST).save(os.path.join(prev, n + ".png"))
    window(6).resize((352, 444), Image.NEAREST).save(os.path.join(prev, "shop6.png"))
    balloon().save(os.path.join(prev, "balloon.png"))


if __name__ == "__main__":
    build()
