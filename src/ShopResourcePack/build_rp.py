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

VERSION = "2"
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



# ── pack Wardrobe: a full-screen menu drawn through the chest window's TITLE ───
#
# The title is drawn after the slots, but items still sit in front of it (their z is higher), so glyphs in the
# title can paint panels anywhere on screen while the icons stay visible. Text is positioned with:
#   * "aerosmp:sp"    space glyphs that move the pen left/right by powers of two
#   * "aerosmp:t<Y>"  vanilla's ascii.png at an ascent that puts the glyph top at window-y Y (5..245)
#   * "aerosmp:b<Y>"  the same at double size, for the few big lines
# A glyph's top lands at  titleY(6) + 7 - ascent,  so  ascent = 13 - Y.  ascent may not exceed height, which is
# why plain text can't go above y=5 (the art can: its glyphs are tall).
# The auth mod lays text out with the metrics written into its resources (glyph widths + this layout).

ASCII_JAR = os.environ.get("MC_CLIENT_JAR", os.path.expanduser(
    "~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar"))
METRICS = os.path.join(HERE, "..", "CoffeesAeroAuth", "src", "main", "resources", "assets", "coffees_aero_auth",
                       "rp", "metrics.json")

LAYOUT = {
    # The whole canvas fits a 426x240 GUI screen (auto GUI scale at 1280x720, 2560x1440 and 3840x2160): the
    # 176x222 chest window is centred, so 125 px either side and 9 px above/below are all there is.
    "canvasLeft": -125, "canvasTop": 0, "canvasW": 426, "canvasH": 231,
    "left": [-125, -3], "window": [0, 176], "right": [179, 301],
    "headerY": 6,
    "viewport": [19, 181],
    "tabY": 18,
    "rows": [36, 54, 72, 90, 108, 139, 157, 175],
    "hotbarY": 197,
    "textYmin": 5, "textYmax": 245,
    "bigYs": [22, 24, 190, 192],
}
GOLD, GOLD_D, INK, PANEL, PANEL_2, ROW_A, ROW_B = 0xC9973B, 0x8A6420, 0x120B05, 0x23180C, 0x1B1209, 0x2B1E10, 0x261A0E
ICONS = [chr(c) for c in range(0xE200, 0xE207)]      # star check bean cross lock arrow dot


def _panel(d, x0, y0, x1, y1, header=True):
    d.rectangle([x0, y0, x1, y1], fill=rgba(PANEL))
    d.rectangle([x0, y0, x1, y1], outline=rgba(INK))
    d.rectangle([x0 + 1, y0 + 1, x1 - 1, y1 - 1], outline=rgba(GOLD))
    d.rectangle([x0 + 2, y0 + 2, x1 - 2, y1 - 2], outline=rgba(GOLD_D))
    if header:
        d.rectangle([x0 + 3, y0 + 3, x1 - 3, y0 + 15], fill=rgba(PANEL_2))
        d.line([x0 + 3, y0 + 16, x1 - 3, y0 + 16], fill=rgba(GOLD_D))


def _cell(d, x, y):
    d.rectangle([x, y, x + 17, y + 17], fill=rgba(0x140D06))
    d.line([x, y, x + 16, y], fill=rgba(0x060302))
    d.line([x, y, x, y + 16], fill=rgba(0x060302))
    d.line([x, y + 17, x + 17, y + 17], fill=rgba(0x5C4026))
    d.line([x + 17, y, x + 17, y + 17], fill=rgba(0x5C4026))


def wardrobe_canvas():
    L = LAYOUT
    ox = -L["canvasLeft"]
    im = Image.new("RGBA", (L["canvasW"], L["canvasH"]), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # left: "You" panel with a see-through viewport where the mannequin stands in the world
    lx0, lx1 = L["left"][0] + ox, L["left"][1] + ox
    _panel(d, lx0, 0, lx1, L["canvasH"] - 1)
    v0, v1 = L["viewport"]
    d.rectangle([lx0 + 3, v0 - 2, lx1 - 3, v1 + 2], outline=rgba(INK))
    d.rectangle([lx0 + 4, v0 - 1, lx1 - 4, v1 + 1], outline=rgba(GOLD_D))
    rnd = random.Random(11)
    for y in range(v0, v1 + 1):
        t = (y - v0) / max(1, v1 - v0)
        col = (int(0x14 * (1 - t) + 0x3A * t), int(0x1C * (1 - t) + 0x2C * t), int(0x36 * (1 - t) + 0x22 * t), 255)
        d.line([lx0 + 5, y, lx1 - 5, y], fill=col)
    for _ in range(46):
        sx, sy = rnd.randrange(lx0 + 6, lx1 - 6), rnd.randrange(v0 + 2, v0 + (v1 - v0) * 2 // 3)
        d.point((sx, sy), fill=(255, 240, 200, 255) if rnd.random() < 0.3 else (170, 185, 225, 255))
    gy = v1 - 14                                                    # a lit floor for the doll to stand on
    d.ellipse([lx0 + 22, gy - 5, lx1 - 22, gy + 7], fill=rgba(0x3A2715), outline=rgba(0xC9973B))
    d.ellipse([lx0 + 30, gy - 3, lx1 - 30, gy + 5], outline=rgba(0x8A6420))
    for (cx, cy) in [(lx0 + 6, v0 + 1), (lx1 - 6, v0 + 1), (lx0 + 6, v1 - 1), (lx1 - 6, v1 - 1)]:
        d.rectangle([cx - 1, cy - 1, cx + 1, cy + 1], fill=rgba(0xF0C05A))
    # centre: the chest window itself
    wx0, wx1 = L["window"][0] + ox, L["window"][1] + ox - 1
    _panel(d, wx0, 0, wx1, 221)
    for k in range(9):                                            # tab row
        x = wx0 + 7 + k * 18
        d.rectangle([x, L["tabY"] - 1, x + 17, L["tabY"] + 16], fill=rgba(0x2E2112), outline=rgba(GOLD_D))
    for i, y in enumerate(L["rows"]):                             # list rows
        d.rectangle([wx0 + 7, y - 1, wx0 + 168, y + 16], fill=rgba(ROW_A if i % 2 == 0 else ROW_B))
        _cell(d, wx0 + 7, y - 1)
    d.line([wx0 + 7, 131, wx0 + 168, 131], fill=rgba(GOLD_D))      # the gap vanilla keeps for "Inventory"
    hy = L["hotbarY"]
    d.rectangle([wx0 + 7, hy - 1, wx0 + 168, hy + 16], fill=rgba(PANEL_2), outline=rgba(GOLD_D))
    for k in (0, 8):
        _cell(d, wx0 + 7 + k * 18, hy - 1)
    # right: details panel
    rx0, rx1 = L["right"][0] + ox, L["right"][1] + ox
    _panel(d, rx0, 0, rx1, L["canvasH"] - 1)
    d.line([rx0 + 6, 44, rx1 - 6, 44], fill=rgba(GOLD_D))
    d.line([rx0 + 6, 181, rx1 - 6, 181], fill=rgba(GOLD_D))
    return im


def wardrobe_overlays():
    tab = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    d = ImageDraw.Draw(tab)
    d.rectangle([0, 0, 17, 17], outline=rgba(0xF0C05A))
    d.line([1, 17, 16, 17], fill=rgba(0xFFE08A))
    d.line([1, 16, 16, 16], fill=rgba(0xF0C05A))
    row = Image.new("RGBA", (162, 18), (0, 0, 0, 0))
    d = ImageDraw.Draw(row)
    d.rectangle([0, 0, 161, 17], fill=(0xB8, 0x8A, 0x2E, 90))
    d.line([0, 0, 161, 0], fill=rgba(0xF0C05A))
    d.line([0, 17, 161, 17], fill=rgba(0xF0C05A))
    return tab, row


def font_icons():
    im = Image.new("RGBA", (8 * len(ICONS), 8), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = (255, 255, 255, 255)
    for (x, y) in [(3, 0), (3, 1), (2, 2), (3, 2), (4, 2), (0, 3), (1, 3), (2, 3), (3, 3), (4, 3), (5, 3), (6, 3),
                   (1, 4), (2, 4), (3, 4), (4, 4), (5, 4), (1, 5), (2, 5), (4, 5), (5, 5), (0, 6), (1, 6), (5, 6), (6, 6)]:
        d.point((x, y), fill=w)                                    # star
    for (x, y) in [(5, 1), (5, 2), (4, 3), (0, 4), (4, 4), (1, 5), (3, 5), (2, 6)]:
        d.point((8 + x, y), fill=w)                                # check
    d.ellipse([16, 1, 21, 6], fill=w)                              # bean
    d.line([18, 2, 19, 5], fill=(0, 0, 0, 0))
    for i in range(5):                                             # cross
        d.point((24 + 1 + i, 2 + i), fill=w)
        d.point((24 + 5 - i, 2 + i), fill=w)
    d.rectangle([33, 3, 37, 6], fill=w)                            # lock
    d.rectangle([34, 1, 36, 3], outline=w)
    for i in range(4):                                             # arrow
        d.line([40 + 1 + i, 1 + i, 40 + 1 + i, 7 - i], fill=w)
    d.rectangle([49, 3, 50, 4], fill=w)                            # dot
    return im


def glyph_width(img, x0, y0, cw, ch):
    px = img.load()
    for x in range(cw - 1, -1, -1):
        for y in range(ch):
            if px[x0 + x, y0 + y][3] != 0:
                return x + 1
    return 0


def wardrobe_fonts(sprites):
    import io as _io
    with zipfile.ZipFile(ASCII_JAR) as jar:
        ascii_png = Image.open(_io.BytesIO(jar.read("assets/minecraft/textures/font/ascii.png"))).convert("RGBA")
        vanilla = json.loads(jar.read("assets/minecraft/font/include/default.json"))
    rows = next(p for p in vanilla["providers"] if p.get("file") == "minecraft:font/ascii.png")["chars"]
    # Printable ASCII only: the rest of the sheet is dropped so nothing odd sneaks in.
    rows = ["".join(c if 32 < ord(c) < 127 else "\u0000" for c in r) for r in rows]
    cw = ascii_png.width // 16
    widths = {}
    for ry, r in enumerate(rows):
        for rx, c in enumerate(r):
            if c != "\u0000":
                widths[c] = glyph_width(ascii_png, rx * cw, ry * cw, cw, cw)
    icons = font_icons()
    files[f"assets/{NS}/textures/font/icons.png"] = png(icons)
    for i, c in enumerate(ICONS):
        widths[c] = glyph_width(icons, i * 8, 0, 8, 8)

    def text_font(ascent, height):
        return {"providers": [
            {"type": "space", "advances": {" ": 4 * height // 8}},
            {"type": "bitmap", "file": "minecraft:font/ascii.png", "ascent": ascent, "height": height, "chars": rows},
            {"type": "bitmap", "file": f"{NS}:font/icons.png", "ascent": ascent, "height": height, "chars": ["".join(ICONS)]},
        ]}
    L = LAYOUT
    for y in range(L["textYmin"], L["textYmax"] + 1):
        put_json(f"assets/{NS}/font/t{y}.json", text_font(13 - y, 8))
    for y in L["bigYs"]:
        put_json(f"assets/{NS}/font/b{y}.json", text_font(13 - y, 16))
    adv = {}
    for i in range(9):
        adv[chr(0xE101 + i)] = 1 << i
        adv[chr(0xE111 + i)] = -(1 << i)
    put_json(f"assets/{NS}/font/sp.json", {"providers": [{"type": "space", "advances": adv}]})

    canvas = wardrobe_canvas()
    half = L["canvasW"] // 2
    files[f"assets/{NS}/textures/gui/wardrobe_l.png"] = png(canvas.crop((0, 0, half, L["canvasH"])))
    files[f"assets/{NS}/textures/gui/wardrobe_r.png"] = png(canvas.crop((half, 0, L["canvasW"], L["canvasH"])))
    tab, row = wardrobe_overlays()
    files[f"assets/{NS}/textures/gui/wardrobe_tab.png"] = png(tab)
    files[f"assets/{NS}/textures/gui/wardrobe_row.png"] = png(row)
    provs = [
        {"type": "bitmap", "file": f"{NS}:gui/wardrobe_l.png", "ascent": 13 - L["canvasTop"], "height": L["canvasH"], "chars": [""]},
        {"type": "bitmap", "file": f"{NS}:gui/wardrobe_r.png", "ascent": 13 - L["canvasTop"], "height": L["canvasH"], "chars": [""]},
        {"type": "bitmap", "file": f"{NS}:gui/wardrobe_tab.png", "ascent": 13 - (L["tabY"] - 1), "height": 18, "chars": [""]},
    ]
    for k, y in enumerate(L["rows"]):
        provs.append({"type": "bitmap", "file": f"{NS}:gui/wardrobe_row.png", "ascent": 13 - (y - 1), "height": 18,
                      "chars": [chr(0xE030 + k)]})
    put_json(f"assets/{NS}/font/wd.json", {"providers": provs})

    _sc, scene_meta = doll_fonts(sprites)
    metrics = {"_note": "GENERATED by src/ShopResourcePack/build_rp.py - do not edit by hand",
               "doll": {"top": DOLL_TOP, "pixel": PIXEL, "pixelAdvance": PIXEL, "scenes": scene_meta},
               "widths": dict(sorted(widths.items())), "space": 4, "layout": L,
               "art": {"tileW": half, "tab": 18, "row": 162},
               # advance of each art glyph = its last opaque column + 1 (+1 spacing), exactly as the client measures
               "wdAdvance": {"": glyph_width(canvas, 0, 0, half, L["canvasH"]) + 1,
                             "": glyph_width(canvas, half, 0, L["canvasW"] - half, L["canvasH"]) + 1,
                             "": glyph_width(tab, 0, 0, 18, 18) + 1,
                             "row": glyph_width(row, 0, 0, 162, 18) + 1}}
    os.makedirs(os.path.dirname(METRICS), exist_ok=True)
    with open(METRICS, "w", encoding="utf-8", newline="\n") as f:
        json.dump(metrics, f, indent=1, ensure_ascii=True)
        f.write("\n")
    return canvas


# ── Wardrobe "You" panel: skin doll pixels + effect scenes ─────────────────────
#
# The doll is the player's real skin, drawn by the auth mod one 4x4 "pixel" at a time: a white 5x4 glyph (one
# column wider than the step, so neighbours overlap instead of leaving the client's 1px spacing gap) followed by a
# -2 space, net advance 4, tinted per run with the text colour. Font "aerosmp:p<Y>" puts a pixel row at window-y Y.
# Scenes are pre-composed pictures of each effect (sprites around the feet, or the airship and its wake), one glyph
# each, placed under/over the doll.

PIXEL = 4
DOLL_TOP = 40
SCENE_W = 112
SCENE_TOP = 56                               # window-y of a contrail scene's top
SX = 0.8                                     # trail scenes were drawn for a 130 px panel; spread scaled to fit


def _paste(dst, sprite, x, y, scale=1, alpha=1.0, rot=0):
    s = sprite
    if scale != 1:
        s = s.resize((max(1, int(s.width * scale)), max(1, int(s.height * scale))), Image.NEAREST)
    if rot:
        s = s.rotate(rot, resample=Image.NEAREST, expand=True)
    if alpha < 1:
        a = s.getchannel("A").point(lambda v: int(v * alpha))
        s = s.copy()
        s.putalpha(a)
    x0, y0 = int(x - s.width / 2), int(y - s.height / 2)
    if -x0 >= s.width or -y0 >= s.height:                # wholly off the canvas
        return
    if x0 < 0 or y0 < 0:                                  # clip at the canvas edge
        s = s.crop((max(0, -x0), max(0, -y0), s.width, s.height))
        x0, y0 = max(0, x0), max(0, y0)
    if s.width > 0 and s.height > 0:
        dst.alpha_composite(s, (x0, y0))


B = 5                                        # one block of the preview ship, in pixels
SHIP_W, SHIP_H = 74, 56
PROP_HUB = (6, 33)                           # where the wake leaves the ship (propeller hub), in ship pixels


def _block(d, x, y, base, w=B, h=B, seed=0, grain=None):
    """One shaded block: a light top-left rim, a dark bottom-right rim, a little texture noise."""
    rnd = random.Random(seed * 7919 + x * 31 + y)
    r0, g0, b0 = (base >> 16) & 255, (base >> 8) & 255, base & 255
    for yy in range(h):
        for xx in range(w):
            k = rnd.randint(-7, 7)
            if grain == "h" and yy == h - 2:
                k -= 14
            if grain == "v" and xx == w - 2:
                k -= 10
            if xx == 0 or yy == 0:
                k += 22
            if xx == w - 1 or yy == h - 1:
                k -= 30
            d.point((x + xx, y + yy), fill=(max(0, min(255, r0 + k)), max(0, min(255, g0 + k)), max(0, min(255, b0 + k)), 255))


def ship_side():
    """A Create: Aeronautics build seen from the side, bow to the right: an envelope of white blocks with a red
    band, chain rigging, a spruce hull with a cabin and a brass rail, and a propeller bearing on the stern turning
    a four-sail propeller. Effects pour out of the propeller hub (PROP_HUB), like the real contrail at the stern."""
    im = Image.new("RGBA", (SHIP_W, SHIP_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    X = 12                                   # block column 0 starts here; the propeller lives left of it
    # envelope: 4 rows, rounded by dropping the corner blocks
    for r, (c0, c1) in enumerate([(1, 10), (0, 11), (0, 11), (1, 10)]):
        for c in range(c0, c1 + 1):
            col = 0xC23A30 if r == 2 else 0xE9E4DA
            _block(d, X + c * B, 1 + r * B, col, seed=r * 20 + c)
    for c in range(1, 11):                   # envelope seams, like the block's stitched edge
        d.point((X + c * B, 1 + B), fill=(150, 140, 128, 255))
    d.line([X + 2, 3, X + 9 * B, 3], fill=(255, 255, 255, 120))                 # a sheen along the top
    # tail fin (red envelope blocks) on the stern end of the envelope
    _block(d, X - B + 1, 1 - 0 + 2, 0xC23A30, w=B, h=B + 3, seed=91)
    _block(d, X - B + 1, 1 + 3 * B - 1, 0xA8302A, w=B, h=B, seed=92)
    # rigging: chains from the envelope to the deck
    for cx in (X + 2 * B + 2, X + 6 * B + 2, X + 9 * B + 2):
        for yy in range(1 + 4 * B, 31):
            d.point((cx, yy), fill=rgba(0x3B4250 if yy % 2 else 0x6A7384))
    # cabin with lit windows
    for c in range(4, 7):
        _block(d, X + c * B, 26, 0x9C7A4A, seed=c + 40, grain="v")
    d.rectangle([X + 4 * B, 25, X + 7 * B - 1, 25], fill=rgba(0x5A3A1E))
    for c in (4, 6):
        d.rectangle([X + c * B + 1, 27, X + c * B + 3, 29], fill=rgba(0xFFD27A))
    # hull: spruce, tapering to a keel of dark oak, a pointed bow on the right
    for c in range(0, 12):
        _block(d, X + c * B, 31, 0x7A5432, seed=c + 60, grain="h")
    for c in range(1, 11):
        _block(d, X + c * B, 31 + B, 0x6B4728, seed=c + 70, grain="h")
    for c in range(2, 9):
        _block(d, X + c * B, 31 + 2 * B, 0x45301A, seed=c + 80)
    d.polygon([(X + 12 * B, 31), (X + 12 * B + 4, 31), (X + 12 * B, 31 + B + 2)], fill=rgba(0x6B4728))
    d.line([X, 30, X + 12 * B + 3, 30], fill=rgba(0xF0C05A))                    # brass rail
    d.line([X, 31, X + 12 * B, 31], fill=rgba(0x8A6420))
    for c in range(0, 12, 2):
        d.point((X + c * B + 2, 29), fill=rgba(0xC9973B))                       # rail posts
    # propeller bearing on the stern: andesite casing, brass ring, the shaft
    bx, by = X - B - 1, 31
    _block(d, bx, by, 0x8E9091, w=B + 1, h=B + 1, seed=5)
    d.rectangle([bx + 1, by + 1, bx + B - 1, by + B - 1], outline=rgba(0xC9973B))
    d.point((bx + 3, by + 3), fill=rgba(0x3A3A3A))
    d.rectangle([bx - 2, by + 2, bx - 1, by + 3], fill=rgba(0x5A5E64))           # shaft
    # the propeller, seen nearly edge-on: four sails on a frame, two up and two down, blurred where they sweep
    hx, hy = PROP_HUB
    for yy in range(hy - 15, hy + 16):
        if abs(yy - hy) <= 1:
            continue
        t = abs(yy - hy) / 15
        w = 2 if t < 0.85 else 1
        d.line([hx - w + 1, yy, hx + 1, yy], fill=rgba(0xF2EEE6 if (yy // 4) % 2 else 0xD9D3C7))
        d.point((hx + 2, yy), fill=rgba(0x8A6A40))                              # the sail frame's wooden spar
    for k in (-1, 1):                                                           # motion arcs
        for i in range(-12, 13):
            x = hx - 3 - int(2 * math.cos(i / 12 * math.pi / 2))
            d.point((x, hy + i), fill=(255, 255, 255, 60))
    d.rectangle([hx - 1, hy - 1, hx + 2, hy + 1], fill=rgba(0xC9973B))         # hub
    d.point((hx, hy), fill=rgba(0x8A6420))
    return im


def scenes(sp):
    """id -> (image, top Y in window coords). Trails sit at the doll's feet; contrails replace the doll."""
    feet = DOLL_TOP + 32 * PIXEL            # window-y of the soles
    out = {}
    W = SCENE_W
    cx = W // 2

    def trail(draw, h=40):
        im = Image.new("RGBA", (W, h), (0, 0, 0, 0))
        draw(im, h)
        return im

    out["trail:cloudwalk"] = (trail(lambda im, h: [
        _paste(im, sp["cloud"], cx + SX * (dx - 16), h - 10 + dy, 1.6 - i * 0.25, 1 - i * 0.2) for i, (dx, dy) in
        enumerate([(0, 0), (34, -2), (-30, 2), (52, -6), (-48, -4)])]), feet - 28)
    out["trail:brass"] = (trail(lambda im, h: (
        [_paste(im, sp["cog"], cx + 34 * math.cos(a), h - 14 + 10 * math.sin(a), 1.0, 1.0, int(a * 57)) for a in
         [i * math.pi / 3 + 0.3 for i in range(6)]],
        [_paste(im, sp["spark"], cx + SX * dx, h - 22 + dy, 0.8) for dx, dy in [(-26, -6), (30, -2), (4, 6)]])), feet - 26)
    def clock(im, h):
        face = sp["clock_face"].resize((84, 28), Image.NEAREST)
        im.alpha_composite(face, (cx - 42, h - 30))
        hand = sp["clock_hand"].resize((40, 14), Image.NEAREST)
        im.alpha_composite(hand, (cx - 6, h - 24))
    out["trail:clockwork"] = (trail(clock), feet - 22)
    out["trail:espresso"] = (trail(lambda im, h: (
        [_paste(im, sp["bean"], cx + SX * dx, h - 8 + dy, 0.9, 1.0, r) for dx, dy, r in
         [(-34, 0, 20), (-18, -4, 70), (22, -2, 140), (38, 2, 200), (-4, 4, 10), (50, -8, 90)]],
        [_paste(im, sp["steam"], cx + SX * dx, h - 26 + dy, 1.2, 0.7) for dx, dy in [(-44, -4), (44, -10)]])), feet - 28)
    out["trail:kettle"] = (trail(lambda im, h: [
        _paste(im, sp["steam"], cx + 30 * math.sin(i * 1.4), h - 8 - i * 9, 1.3 + i * 0.15, 0.9 - i * 0.12)
        for i in range(5)], h=56), feet - 46)
    out["trail:petal"] = (trail(lambda im, h: [
        _paste(im, sp["petal"], cx + SX * dx, h - 10 + dy, 0.9, 1.0, r) for dx, dy, r in
        [(-40, -20, 10), (-22, -2, 80), (-6, -14, 150), (14, 0, 30), (32, -18, 200), (48, -4, 120), (-50, 0, 260)]],
        h=44), feet - 34)
    out["trail:ember"] = (trail(lambda im, h: (
        [_paste(im, sp["flame"], cx + SX * dx, h - 12, 1.3) for dx in (-30, 0, 30)],
        [_paste(im, sp["ember"], cx + SX * dx, h - 30 + dy, 0.6) for dx, dy in [(-20, -6), (12, -14), (36, -4), (-40, -16)]])),
        feet - 30)

    def snow(im, h):
        _paste(im, sp["cloud"], cx - 14, 12, 2.2)
        _paste(im, sp["cloud"], cx + 16, 10, 1.9)
        for dx, dy, r in [(-30, 30, 0), (-8, 42, 30), (14, 34, 60), (32, 46, 15), (-20, 58, 45), (24, 64, 75)]:
            _paste(im, sp["snowflake"], cx + dx, dy, 0.8, 1.0, r)
    snow_im = Image.new("RGBA", (W, 72), (0, 0, 0, 0))
    snow(snow_im, 72)
    out["trail:snow"] = (snow_im, DOLL_TOP - 30)

    ship = ship_side()
    SHIP_X, SHIP_Y = W - SHIP_W - 1, 14

    def contrail(wake):
        im = Image.new("RGBA", (W, 84), (0, 0, 0, 0))
        wake(im)
        im.alpha_composite(ship, (SHIP_X, SHIP_Y))
        return im
    tail = (SHIP_X + PROP_HUB[0] - 3, SHIP_Y + PROP_HUB[1])    # just behind the propeller hub
    L = tail[0]                              # the wake runs from the hub to the panel's edge

    def wake(n, place):
        """n puffs from the hub (t=0) to the edge (t=1); place(im, t, i, x) draws one."""
        def draw(im):
            for i in range(n - 1, -1, -1):       # far ones first, so the fresh ones sit on top
                t = i / (n - 1)
                place(im, t, i, tail[0] - 2 - t * (L - 4))
        return draw

    out["contrail:vapour"] = (contrail(wake(9, lambda im, t, i, x: [
        _paste(im, sp["vapour"], x, tail[1] + oy * (1 + 1.5 * t), 0.7 + 0.5 * t, 1 - 0.7 * t)
        for oy in (-3, 3)])), SCENE_TOP)
    out["contrail:steam"] = (contrail(wake(7, lambda im, t, i, x:
        _paste(im, sp["steam"], x, tail[1] - 12 * t * t, 0.6 + 1.0 * t, 0.95 - 0.6 * t))), SCENE_TOP)
    out["contrail:espresso"] = (contrail(wake(8, lambda im, t, i, x:
        _paste(im, sp["bean" if i % 3 == 0 else "crema"], x, tail[1] + 4 * math.sin(i * 1.3) * t,
               0.6 + 0.5 * t, 1 - 0.45 * t, i * 40))), SCENE_TOP)
    out["contrail:sakura"] = (contrail(wake(11, lambda im, t, i, x:
        _paste(im, sp["petal"], x, tail[1] + 14 * t * math.sin(i * 1.9), 0.55 + 0.3 * t, 1 - 0.35 * t, i * 37))),
        SCENE_TOP)
    out["contrail:aurora"] = (contrail(wake(14, lambda im, t, i, x:
        _paste(im, [sp["ribbon_aqua"], sp["ribbon_violet"], sp["ribbon_green"]][(i // 5) % 3], x,
               tail[1] + 7 * t * math.sin(i * 0.7), 0.7 + 0.5 * t, 0.95 - 0.3 * t))), SCENE_TOP)
    return out


def doll_fonts(sp):
    """Pixel fonts, the scenes font, the 'You' panel backdrop and the doll silhouette placeholder."""
    files[f"assets/{NS}/textures/font/px.png"] = png(Image.new("RGBA", (5, 4), (255, 255, 255, 255)))
    L = LAYOUT
    for y in range(9, L["textYmax"] + 1):
        put_json(f"assets/{NS}/font/p{y}.json", {"providers": [
            {"type": "bitmap", "file": f"{NS}:font/px.png", "ascent": 13 - y, "height": 4, "chars": [""]},
            {"type": "space", "advances": {"": -2}},
        ]})
    sc = scenes(sp)
    provs, meta = [], {}
    for i, (cid, (img, top)) in enumerate(sorted(sc.items())):
        ch = chr(0xE400 + i)
        files[f"assets/{NS}/textures/gui/scene_{cid.replace(':', '_')}.png"] = png(img)
        provs.append({"type": "bitmap", "file": f"{NS}:gui/scene_{cid.replace(':', '_')}.png",
                      "ascent": 13 - top, "height": img.height, "chars": [ch]})
        meta[cid] = {"char": ch, "advance": glyph_width(img, 0, 0, img.width, img.height) + 1, "w": img.width}
    put_json(f"assets/{NS}/font/sc.json", {"providers": provs})
    return sc, meta

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
    canvas = wardrobe_fonts(sprites)
    # The pack Wardrobe paints over the whole window; vanilla's "Inventory" label would show through it.
    put_json("assets/minecraft/lang/en_us.json", {"container.inventory": ""})
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
    canvas.resize((940, 480), Image.NEAREST).save(os.path.join(prev, "wardrobe.png"))
    for cid, (img, _top) in scenes(sprites).items():
        img.resize((img.width * 3, img.height * 3), Image.NEAREST).save(os.path.join(prev, "scene_" + cid.replace(":", "_") + ".png"))


if __name__ == "__main__":
    build()
