"""
Coffees Aero SMP — Asset Generator
Generates:
  - 16x16 block textures for FE Kinetic Importer & Kinetic FE Exporter
  - 6x 512x512 panorama images (cubemap) for the main menu background
  - Resource pack structure

Run from the project root:
    pip install Pillow
    python scripts/generate-assets.py
"""

import os
import math
import random
from PIL import Image, ImageDraw, ImageFilter

# ── Resolve project root (script lives in /scripts/) ─────────────────────────
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

BLOCK_TEX  = os.path.join(ROOT, "src", "PowerBridge", "src", "main", "resources",
                           "assets", "coffees_power_bridge", "textures", "block")
PANORAMA   = os.path.join(ROOT, "overrides", "resourcepacks",
                           "CoffeesAeroSMP-Core-RP", "assets", "minecraft",
                           "textures", "gui", "title", "background")

for d in [BLOCK_TEX, PANORAMA]:
    os.makedirs(d, exist_ok=True)

# ── Palette ───────────────────────────────────────────────────────────────────
BRASS_L   = (198, 168,  75)
BRASS_M   = (184, 148,  58)
BRASS_D   = (160, 122,  32)
COPPER_L  = (200, 121,  65)
COPPER_M  = (181, 107,  53)
COPPER_D  = (157,  92,  41)
STEEL_L   = ( 96,  96,  96)
STEEL_M   = ( 74,  74,  74)
STEEL_D   = ( 44,  44,  44)
ELEC_B    = ( 64, 164, 255)   # FE-input blue
ELEC_W    = (200, 220, 255)   # electric white highlight
OUT_G     = ( 64, 200,  64)   # FE-output green
OUT_GL    = (160, 230, 160)   # output highlight
SHAFT     = ( 20,  20,  20)

S = 16   # texture size

def img16():
    return Image.new("RGBA", (S, S), (0, 0, 0, 255))

def px(im, x, y, c):
    if 0 <= x < S and 0 <= y < S:
        im.putpixel((x, y), c + (255,) if len(c) == 3 else c)

# ── Helper: fill rect ─────────────────────────────────────────────────────────
def fill(im, x0, y0, x1, y1, c):
    for y in range(y0, y1+1):
        for x in range(x0, x1+1):
            px(im, x, y, c)

# ─────────────────────────────────────────────────────────────────────────────
# BLOCK TEXTURES
# ─────────────────────────────────────────────────────────────────────────────

def make_importer_side():
    """
    FE Kinetic Importer — side face.
    Shows copper coil windings + blue FE-input indicator in centre.
    """
    im = img16()
    # Base steel
    fill(im, 0, 0, 15, 15, STEEL_D)
    # Brass border (outer ring)
    for i in range(16):
        px(im, i,  0, BRASS_M);  px(im, i, 15, BRASS_M)
        px(im, 0,  i, BRASS_M);  px(im,15,  i, BRASS_M)
    # Brass corner highlights
    for (x,y) in [(0,0),(15,0),(0,15),(15,15)]:
        px(im, x, y, BRASS_L)
    # Steel inner frame
    for i in range(1, 15):
        px(im, i,  1, STEEL_L);  px(im, i, 14, STEEL_L)
        px(im, 1,  i, STEEL_L);  px(im,14,  i, STEEL_L)
    # Copper coil stripes (horizontal bands = wire winding)
    coil = [(3, COPPER_L), (5, COPPER_M), (7, COPPER_D),
            (9, COPPER_M), (11, COPPER_L)]
    for (y, c) in coil:
        for x in range(2, 14):
            px(im, x, y, c)
    # Blue FE-input rectangle in centre
    fill(im, 5,  5,  10, 10, ELEC_B)
    # Inner dark cavity
    fill(im, 6,  6,   9,  9, STEEL_D)
    # Bright centre spark
    fill(im, 7,  7,   8,  8, ELEC_W)
    return im

def make_importer_end():
    """
    FE Kinetic Importer — top/bottom face.
    Shows shaft hole with copper ring + blue corner dots.
    """
    im = img16()
    fill(im, 0, 0, 15, 15, STEEL_M)
    # Brass border
    for i in range(16):
        px(im, i,  0, BRASS_M);  px(im, i, 15, BRASS_M)
        px(im, 0,  i, BRASS_M);  px(im,15,  i, BRASS_M)
    # Copper outer ring
    ring = [
        (4,3),(5,3),(6,3),(7,3),(8,3),(9,3),(10,3),(11,3),
        (3,4),(12,4),(3,5),(12,5),(3,6),(12,6),(3,7),(12,7),
        (3,8),(12,8),(3,9),(12,9),(3,10),(12,10),(3,11),(12,11),
        (4,12),(5,12),(6,12),(7,12),(8,12),(9,12),(10,12),(11,12),
    ]
    for (x,y) in ring: px(im, x, y, COPPER_L)
    # Copper inner ring (shadow)
    inner = [
        (5,4),(6,4),(7,4),(8,4),(9,4),(10,4),
        (4,5),(11,5),(4,6),(11,6),(4,7),(11,7),
        (4,8),(11,8),(4,9),(11,9),(4,10),(11,10),
        (5,11),(6,11),(7,11),(8,11),(9,11),(10,11),
    ]
    for (x,y) in inner: px(im, x, y, COPPER_D)
    # Shaft hole
    fill(im, 5, 5, 10, 10, SHAFT)
    # Blue corner indicators
    for (x,y) in [(5,5),(10,5),(5,10),(10,10)]:
        px(im, x, y, ELEC_B)
    return im

def make_exporter_side():
    """
    Kinetic FE Exporter — side face.
    Same coil pattern but green output indicator (arrow pointing out).
    """
    im = img16()
    fill(im, 0, 0, 15, 15, STEEL_D)
    for i in range(16):
        px(im, i,  0, BRASS_M);  px(im, i, 15, BRASS_M)
        px(im, 0,  i, BRASS_M);  px(im,15,  i, BRASS_M)
    for (x,y) in [(0,0),(15,0),(0,15),(15,15)]: px(im, x, y, BRASS_L)
    for i in range(1, 15):
        px(im, i,  1, STEEL_L);  px(im, i, 14, STEEL_L)
        px(im, 1,  i, STEEL_L);  px(im,14,  i, STEEL_L)
    coil = [(3, COPPER_D), (5, COPPER_L), (7, COPPER_M),
            (9, COPPER_L), (11, COPPER_D)]
    for (y, c) in coil:
        for x in range(2, 14): px(im, x, y, c)
    # Green output indicator: arrow pointing right
    arrow = [
        (4,7),(5,7),(6,7),(7,7),(8,7),(9,7),(10,7),  # stem
        (8,5),(9,5),(8,9),(9,9),                       # upper/lower barb
        (9,6),(10,6),(9,8),(10,8),                     # arrow flanks
        (11,7),                                        # tip
    ]
    for (x,y) in arrow: px(im, x, y, OUT_G)
    px(im, 11, 7, OUT_GL)   # bright tip
    return im

def make_exporter_end():
    """Kinetic FE Exporter — top/bottom face (same shaft, green indicators)."""
    im = make_importer_end()
    for (x,y) in [(5,5),(10,5),(5,10),(10,10)]: px(im, x, y, OUT_G)
    return im

# Save block textures
textures = {
    "fe_kinetic_importer_side": make_importer_side(),
    "fe_kinetic_importer_end":  make_importer_end(),
    "kinetic_fe_exporter_side": make_exporter_side(),
    "kinetic_fe_exporter_end":  make_exporter_end(),
}
for name, im in textures.items():
    path = os.path.join(BLOCK_TEX, f"{name}.png")
    im.save(path)
    print(f"  OK  {path}")

# ─────────────────────────────────────────────────────────────────────────────
# PANORAMA — 6-image cubemap (512×512 each)
# Create: Aeronautics sky theme
# ─────────────────────────────────────────────────────────────────────────────

PAN = 512   # panorama resolution

def sky_gradient(im, horizon_col, zenith_col):
    """Fill image with vertical sky gradient (bottom=horizon, top=zenith)."""
    w, h = im.size
    hr, hg, hb = horizon_col
    zr, zg, zb = zenith_col
    for y in range(h):
        t = y / (h - 1)        # 0 = top (zenith), 1 = bottom (horizon)
        r = int(zr + t * (hr - zr))
        g = int(zg + t * (hg - zg))
        b = int(zb + t * (hb - zb))
        for x in range(w):
            im.putpixel((x, y), (r, g, b))

def draw_cloud(draw, cx, cy, rx, ry, alpha=220):
    """Draw a soft cloud ellipse."""
    draw.ellipse([cx-rx, cy-ry, cx+rx, cy+ry], fill=(255,255,255,alpha))

def draw_clouds(im, seed=0):
    """Scatter cloud puffs across the lower half of the image."""
    rng = random.Random(seed)
    overlay = Image.new("RGBA", im.size, (0,0,0,0))
    draw = ImageDraw.Draw(overlay)
    for _ in range(14):
        cx = rng.randint(0, PAN)
        cy = rng.randint(PAN//2, PAN - 30)
        rx = rng.randint(30, 90)
        ry = rng.randint(15, 35)
        a  = rng.randint(160, 230)
        draw_cloud(draw, cx, cy, rx, ry, a)
        # Extra puffs per cloud
        for _ in range(3):
            ox = rng.randint(-rx//2, rx//2)
            oy = rng.randint(-ry//2, ry//2)
            draw_cloud(draw, cx+ox, cy+oy, rx*2//3, ry*2//3, a-30)
    # Slight blur for softness
    overlay = overlay.filter(ImageFilter.GaussianBlur(radius=4))
    im.paste(overlay, mask=overlay)

def draw_airship(im, cx, cy, scale=1.0):
    """
    Draw a simple Create-style airship silhouette.
    Dark hull with brass-coloured window strip.
    """
    draw = ImageDraw.Draw(im)
    s = scale
    # Main hull (elongated ellipse)
    hw = int(110 * s); hh = int(32 * s)
    draw.ellipse([cx-hw, cy-hh, cx+hw, cy+hh], fill=(35, 30, 25))
    # Gondola / nacelle under the hull
    gw = int(55 * s); gh = int(18 * s)
    draw.ellipse([cx-gw, cy+hh-4, cx+gw, cy+hh+gh], fill=(50, 42, 30))
    # Brass window strip
    for i in range(-2, 3):
        wx = cx + int(i * 35 * s)
        wy = cy - int(4 * s)
        wr = int(9 * s)
        draw.ellipse([wx-wr, wy-wr, wx+wr, wy+wr], fill=BRASS_L)
        draw.ellipse([wx-wr+2, wy-wr+2, wx+wr-2, wy+wr-2], fill=(30,25,15))
    # Propeller strut (right side)
    px0 = cx + hw - int(20*s); py0 = cy + int(6*s)
    draw.rectangle([px0, py0, px0+int(6*s), py0+int(20*s)], fill=(55,45,30))
    # Propeller blades
    for angle in [0, 90, 180, 270]:
        rad = math.radians(angle)
        bx = px0 + int(3*s) + int(math.cos(rad)*22*s)
        by = py0 + int(20*s) + int(math.sin(rad)*22*s)
        bx2 = px0 + int(3*s) + int(math.cos(rad+0.4)*14*s)
        by2 = py0 + int(20*s) + int(math.sin(rad+0.4)*14*s)
        draw.line([(px0+int(3*s), py0+int(20*s)), (bx, by)], fill=(80,65,40), width=max(2,int(4*s)))

def make_side_panorama(seed, show_airship=False, airship_x=None, airship_y=None, airship_scale=1.0):
    """Side view: sky gradient with clouds, optional airship."""
    im = Image.new("RGB", (PAN, PAN))
    sky_gradient(im, horizon_col=(180, 210, 240), zenith_col=(55, 100, 180))
    draw_clouds(im, seed=seed)
    if show_airship:
        ax = airship_x if airship_x is not None else PAN // 2
        ay = airship_y if airship_y is not None else PAN // 3
        draw_airship(im, ax, ay, scale=airship_scale)
    return im

def make_top_panorama():
    """Top view: deep blue zenith."""
    im = Image.new("RGB", (PAN, PAN))
    sky_gradient(im, horizon_col=(90, 140, 200), zenith_col=(30, 60, 140))
    # Sparse high-altitude wispy clouds
    overlay = Image.new("RGBA", (PAN, PAN), (0,0,0,0))
    draw = ImageDraw.Draw(overlay)
    rng = random.Random(42)
    for _ in range(6):
        cx = rng.randint(50, PAN-50); cy = rng.randint(50, PAN-50)
        draw.ellipse([cx-60, cy-12, cx+60, cy+12], fill=(255,255,255,80))
    overlay = overlay.filter(ImageFilter.GaussianBlur(3))
    im.paste(overlay, mask=overlay)
    return im

def make_bottom_panorama():
    """Bottom view: looking down through haze at distant land."""
    im = Image.new("RGB", (PAN, PAN))
    sky_gradient(im, horizon_col=(160, 195, 220), zenith_col=(200, 220, 235))
    draw = ImageDraw.Draw(im)
    # Hazy distant terrain
    draw.ellipse([80, 300, 430, 480], fill=(120, 140, 100))   # land mass
    draw.ellipse([200, 350, 500, 510], fill=(100, 125, 85))
    draw.ellipse([0,   370, 200, 510], fill=(90,  110, 75))
    # River/water glint
    draw.ellipse([180, 420, 320, 460], fill=(140, 170, 200))
    # Haze overlay
    haze = Image.new("RGBA", (PAN, PAN), (200, 215, 230, 90))
    im.paste(haze, mask=haze)
    return im

# Generate all 6 panorama faces
panoramas = [
    # 0: front — main view players see first, large airship visible
    make_side_panorama(seed=11, show_airship=True, airship_x=300, airship_y=190, airship_scale=1.3),
    # 1: left — light clouds
    make_side_panorama(seed=22),
    # 2: back — distant second airship (smaller)
    make_side_panorama(seed=33, show_airship=True, airship_x=160, airship_y=230, airship_scale=0.5),
    # 3: right — clear sky with heavy clouds near horizon
    make_side_panorama(seed=44),
    # 4: top — deep blue zenith
    make_top_panorama(),
    # 5: bottom — looking down at distant land
    make_bottom_panorama(),
]

for i, im in enumerate(panoramas):
    path = os.path.join(PANORAMA, f"panorama_{i}.png")
    im.save(path)
    print(f"  OK  {path}")

print("\nAll assets generated successfully.")
print("Block textures : src/PowerBridge/.../textures/block/")
print("Panorama       : overrides/resourcepacks/CoffeesAeroSMP-Core-RP/.../background/")
