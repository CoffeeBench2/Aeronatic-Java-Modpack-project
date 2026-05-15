"""
Generates 16x16 PNG textures for Coffees Power Bridge.

Palette inspired by Create's brass/copper/steel aesthetic:
  - FE Motor   : deep iron-grey housing, blue electromagnetic core indicator
  - Dynamo     : copper-tone housing, amber/gold output indicator
  - Items      : simple iconic sprites
"""

from PIL import Image, ImageDraw
import os

BLOCK_DIR = "src/main/resources/assets/coffees_power_bridge/textures/block"
ITEM_DIR  = "src/main/resources/assets/coffees_power_bridge/textures/item"
os.makedirs(BLOCK_DIR, exist_ok=True)
os.makedirs(ITEM_DIR,  exist_ok=True)


def save(img: Image.Image, path: str):
    img.save(path)
    print(f"  wrote {path}")


# ── Colour palette ────────────────────────────────────────────────────────────
IRON_DARK   = (55,  55,  60)
IRON_MID    = (90,  90,  95)
IRON_LIGHT  = (140, 140, 145)
COPPER_DARK = (120, 70,  40)
COPPER_MID  = (170, 100, 55)
COPPER_LITE = (210, 140, 80)
BLUE_CORE   = (40,  80,  210)
BLUE_GLOW   = (80,  140, 255)
AMBER_CORE  = (200, 140, 20)
AMBER_GLOW  = (255, 190, 60)
STEEL_RIVET = (200, 200, 205)
BLACK       = (10,  10,  10)


def iron_base(draw: ImageDraw.ImageDraw):
    """Fill with an iron-grey brushed-metal look."""
    for y in range(16):
        shade = IRON_DARK if (y % 4 < 2) else IRON_MID
        for x in range(16):
            draw.point((x, y), fill=shade)
    # Horizontal highlight band
    for x in range(16):
        draw.point((x, 7),  fill=IRON_LIGHT)
        draw.point((x, 8),  fill=IRON_LIGHT)


def copper_base(draw: ImageDraw.ImageDraw):
    """Fill with a copper-tone look."""
    for y in range(16):
        shade = COPPER_DARK if (y % 4 < 2) else COPPER_MID
        for x in range(16):
            draw.point((x, y), fill=shade)
    for x in range(16):
        draw.point((x, 7),  fill=COPPER_LITE)
        draw.point((x, 8),  fill=COPPER_LITE)


def rivets(draw: ImageDraw.ImageDraw):
    """Corner rivets shared by both blocks."""
    for pos in [(1, 1), (14, 1), (1, 14), (14, 14)]:
        draw.point(pos, fill=STEEL_RIVET)


# ── FE Motor side texture ─────────────────────────────────────────────────────
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
iron_base(draw)
rivets(draw)
# Cable port indicator (centre, right side)
for x, y in [(12, 6), (13, 6), (12, 7), (13, 7), (12, 8), (13, 8), (12, 9), (13, 9)]:
    draw.point((x, y), fill=BLUE_CORE)
save(img, f"{BLOCK_DIR}/fe_motor_side.png")

# ── FE Motor front texture (shaft face) ──────────────────────────────────────
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
iron_base(draw)
rivets(draw)
# Central shaft hole (dark circle)
shaft_pixels = [(7,6),(8,6),(6,7),(7,7),(8,7),(9,7),(6,8),(7,8),(8,8),(9,8),(7,9),(8,9)]
for x, y in shaft_pixels:
    draw.point((x, y), fill=BLACK)
# Blue electromagnetic glow ring around shaft
ring = [(6,6),(9,6),(5,7),(10,7),(5,8),(10,8),(6,9),(9,9)]
for x, y in ring:
    draw.point((x, y), fill=BLUE_GLOW)
# Corner cable connectors
for y in range(2, 5):
    draw.point((1, y), fill=BLUE_CORE)
    draw.point((2, y), fill=BLUE_CORE)
    draw.point((1, 11 + (y - 2)), fill=BLUE_CORE)
    draw.point((2, 11 + (y - 2)), fill=BLUE_CORE)
save(img, f"{BLOCK_DIR}/fe_motor_front.png")

# ── Kinetic Dynamo side texture ───────────────────────────────────────────────
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
copper_base(draw)
rivets(draw)
# Output port indicator (amber)
for x, y in [(12, 6), (13, 6), (12, 7), (13, 7), (12, 8), (13, 8), (12, 9), (13, 9)]:
    draw.point((x, y), fill=AMBER_CORE)
save(img, f"{BLOCK_DIR}/kinetic_dynamo_side.png")

# ── Kinetic Dynamo front texture ──────────────────────────────────────────────
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
copper_base(draw)
rivets(draw)
# Central shaft hole
for x, y in shaft_pixels:
    draw.point((x, y), fill=BLACK)
# Amber output glow ring
for x, y in ring:
    draw.point((x, y), fill=AMBER_GLOW)
# Copper coil winding lines
for x in range(3, 14):
    if x % 3 == 0:
        draw.point((x, 3),  fill=COPPER_LITE)
        draw.point((x, 12), fill=COPPER_LITE)
save(img, f"{BLOCK_DIR}/kinetic_dynamo_front.png")

# ── Item: Magnetized Steel ────────────────────────────────────────────────────
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
# Horseshoe magnet shape (simplified)
# Left pole (red)
for y in range(4, 14):
    draw.point((3, y), fill=(200, 40, 40))
    draw.point((4, y), fill=(200, 40, 40))
# Right pole (blue)
for y in range(4, 14):
    draw.point((11, y), fill=(40, 80, 200))
    draw.point((12, y), fill=(40, 80, 200))
# Top connecting bar (iron)
for x in range(3, 13):
    draw.point((x, 3), fill=IRON_MID)
    draw.point((x, 4), fill=IRON_MID)
# Magnetic field spark
draw.point((7, 8),  fill=STEEL_RIVET)
draw.point((8, 8),  fill=STEEL_RIVET)
draw.point((7, 7),  fill=STEEL_RIVET)
draw.point((8, 9),  fill=STEEL_RIVET)
save(img, f"{ITEM_DIR}/magnetized_steel.png")

# ── Item: Electromagnetic Coil ────────────────────────────────────────────────
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
# Coil rings (side view)
coil_y = [3, 5, 7, 9, 11, 13]
for y in coil_y:
    for x in range(2, 14):
        draw.point((x, y), fill=COPPER_MID)
    draw.point((2,  y), fill=COPPER_LITE)
    draw.point((13, y), fill=COPPER_LITE)
# Core (iron)
for y in range(4, 13):
    draw.point((7, y), fill=IRON_LIGHT)
    draw.point((8, y), fill=IRON_LIGHT)
# Blue current glow on core
draw.point((7, 7), fill=BLUE_GLOW)
draw.point((8, 7), fill=BLUE_GLOW)
draw.point((7, 8), fill=BLUE_GLOW)
draw.point((8, 8), fill=BLUE_GLOW)
save(img, f"{ITEM_DIR}/electromagnetic_coil.png")

print("\nAll textures generated.")
