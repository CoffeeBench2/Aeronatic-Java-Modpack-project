"""
Generates the Season 3 quest book (FTB Quests 2101, file version 13) as SNBT + a lang file.

Design rules (research: Obaa planning/quest-design-research.md, design: planning/quest-book-s3-design.md):

  1. One quest teaches one thing. The task is the item that IS the lesson, count 1 unless the count
     is itself the lesson. Quantity is never the difficulty.
  2. The description does the teaching, in our voice, short lines, with the practical gotcha.
  3. Rewards are XP. Items only where the next step needs a cheap input, never the machine the quest
     is about, never a tier material (precision mechanisms, sturdy sheets, netherite...). NO spurs:
     the server pays spurs only through the starter kit and voting (wiki economy.md).

The 09-05 S2 book was OP because gates AND payouts were both huge (addon chapters paid 150-280 items
over three quests). This book is small on both sides on purpose. Do not "raise the rewards" here.

🔑 Every id is checked against scripts/_questgen/s3_items.json, extracted from item MODELS in the S3
jars (D:/MC Project/S3-pack-source/mods). A missing item is invisible to a server boot — FTB renders
it client-side as a purple "?" — so the build fails loudly instead.

🔴 '&' is FTB's colour-code prefix. A bare '&' in any text breaks the line; text() rejects it.

Output: server-assets/ftbquests-s3/quests/  (deploys to the server's config/ftbquests/quests/).
"""
import hashlib
import json
import os
import shutil
import sys

OUT = "server-assets/ftbquests-s3/quests"
VALID = set(json.load(open("scripts/_questgen/s3_items.json")))
BANNED_REWARDS = {
    "create:precision_mechanism", "create:sturdy_sheet", "create:refined_radiance", "create:shadow_steel",
    "minecraft:nether_star", "minecraft:netherite_ingot", "minecraft:diamond", "numismatics:spur",
    "numismatics:bevel", "numismatics:sprocket", "numismatics:cog", "numismatics:crown", "numismatics:sun",
}
XP = {"intro": 10, "normal": 25, "milestone": 50, "finale": 100}

_used = set()
errors = []


def hid(*parts):
    """Stable 16-hex id from a semantic key. First digit kept 0-7 so it always fits a signed long."""
    h = hashlib.sha1("|".join(parts).encode()).hexdigest()[:16].upper()
    while h in _used:
        h = hashlib.sha1((h + "x").encode()).hexdigest()[:16].upper()
    h = "%X" % (int(h[0], 16) & 7) + h[1:]
    _used.add(h)
    return h


def text(s, where):
    if "&" in s:
        errors.append(f"{where}: bare '&' in text: {s!r}")
    return s


# ---------------------------------------------------------------------------------------------
# Content. Each quest: key, item (or "check"), and optional fields:
#   n=count, t=title, sub=subtitle, d=[description lines], deps=[keys, or "chapter.key" across
#   chapters], tier=intro|normal|milestone|finale, give=[(item, count)], shape, opt=True
# ---------------------------------------------------------------------------------------------

def Q(key, item, deps=(), d=(), **kw):
    return dict(key=key, item=item, d=d, deps=list(deps), **kw)


GROUPS = [("start", "Start Here"), ("create", "Create"), ("flight", "Flight"), ("industry", "Industry")]

CHAPTERS = []

# ---------------------------------------------------------------- START HERE
CHAPTERS.append(dict(key="welcome", group="start", title="Welcome to Aero SMP", icon="aeroclaims:claim_block",
  shape="circle", quests=[
    Q("hello", "check", t="Welcome, Engineer", tier="intro", shape="hexagon", size=1.5, d=[
        "This book is a guide, not a grind.",
        "Each quest teaches one thing. Most only ask you to make or hold one item.",
        "",
        "Rewards are mostly XP. The real reward is knowing how the machine works.",
        "Start here, then open the Create chapters."]),
    Q("mail", "check", ["hello"], t="Open Your Mail", sub="/mail", d=[
        "Type /mail. Your Season 3 starter kit is waiting: spurs, bread and saplings.",
        "",
        "Everything the server gives you arrives as mail: level rewards, /daily and votes.",
        "Nothing drops into your inventory by itself, so a full inventory never eats a reward."]),
    Q("land", "check", ["hello"], t="Claim Your Land", sub="Press M", d=[
        "Open the map with M and click chunks to claim them.",
        "",
        "You start with 5 chunks and get 2 more every level.",
        "Claims are full-height columns, bedrock to sky. Claim it before you build it."]),
    Q("bed", "check", ["land"], t="Somewhere to Come Back To", sub="/home", d=[
        "/home takes you to your bed or respawn point.",
        "There is no /tpa this season, so sleep where you actually live.",
        "",
        "Tick this once your bed is in your base."]),
    Q("daily", "check", ["mail"], t="Every Day Counts", sub="/daily and /vote", d=[
        "/daily pays a 7-day cycle of items and XP. Claim it every 20 hours, in the Overworld.",
        "Leave it longer than 40 hours and the streak resets.",
        "",
        "/vote gives you the link. Voting pays spurs and diamonds, and the reward grows with your streak."]),
    Q("level", "check", ["daily"], t="How You Level Up", sub="Advancements and playtime", d=[
        "Your level is on the sidebar. It grows from advancements (10 XP each) and hours played (6 XP each).",
        "",
        "Every level adds 2 claimable chunks and mails you a reward.",
        "Idle for 10 minutes and you are sent to the lobby. AFK time doesn't count."]),
    Q("death", "check", ["bed"], t="When Things Go Wrong", sub="/back", d=[
        "Died? /back returns you to where you fell, within 5 minutes.",
        "You arrive as a ghost for up to 60 seconds: nothing can hurt you while you reach your corpse.",
        "",
        "PvP is on. A hit tags you for 30 seconds. Log out while tagged and you die where you stood."]),
    Q("travel", "check", ["bed"], t="Getting Around", sub="/spawn, /rtp", d=[
        "/rtp drops you 1,500 to 10,000 blocks out. Stand perfectly still until you arrive.",
        "/spawn takes you back to spawn.",
        "",
        "That's all the teleporting there is. A railway or an airship is how you really travel here."]),
    Q("ship_rules", "check", ["land"], t="Ships Have Their Own Claims", tier="milestone", shape="hexagon", d=[
        "Land claims do not protect a ship. Ships use a Claim Block placed on the ship.",
        "",
        "Each Claim Block covers 250 ship blocks and spends one of your chunks.",
        "The order is Place, Claim, Name. Never pick up a claimed ship and put it back down:",
        "that gives it a new ID and orphans the claim.",
        "",
        "The Flight chapters walk you through it."]),
  ]))

# ---------------------------------------------------------------- CREATE: ANDESITE AGE
CHAPTERS.append(dict(key="andesite", group="create", title="Andesite Age", icon="create:cogwheel",
  shape="circle", quests=[
    Q("start", "check", t="Rotation Is Everything", tier="intro", shape="hexagon", size=1.5, d=[
        "Create runs on rotation. Something spins, shafts and cogs carry the spin, machines use it.",
        "",
        "Every machine has a Stress Impact. Every source has a Stress Capacity.",
        "Ask for more than you supply and the whole network stops. That's the one rule.",
        "",
        "Hold W on any Create item (or press the Ponder key) to watch it work."]),
    Q("alloy", "create:andesite_alloy", ["start"], give=[("minecraft:andesite", 4)], d=[
        "Andesite Alloy is the material your first machines are made of.",
        "Craft it from andesite and iron nuggets (zinc nuggets work too).",
        "",
        "Make a stack early. You'll use it for everything in this chapter."]),
    Q("shaft", "create:shaft", ["alloy"], d=[
        "A Shaft carries rotation in a straight line, in both directions.",
        "Place shafts end to end to move power across your base."]),
    Q("cog", "create:cogwheel", ["shaft"], d=[
        "A Cogwheel passes rotation to cogwheels beside it.",
        "Neighbouring cogs spin in opposite directions."]),
    Q("bigcog", "create:large_cogwheel", ["cog"], d=[
        "A Large Cogwheel meshes with small cogs diagonally.",
        "Small to large halves the speed. Large to small doubles it.",
        "This is how you change RPM."]),
    Q("crank", "create:hand_crank", ["shaft"], d=[
        "Your first power source: you.",
        "Hold right-click on the Hand Crank to turn it. Good for testing, terrible for a factory."]),
    Q("wheel", "create:water_wheel", ["crank"], tier="milestone", shape="hexagon", d=[
        "A Water Wheel turns forever when water flows past it.",
        "Put it in a stream or pour water over it. One flowing side is enough; more sides add nothing.",
        "",
        "A Large Water Wheel gives more stress capacity but spins slower."]),
    Q("windmill", "create:windmill_bearing", ["wheel"], d=[
        "A Windmill Bearing turns a sail structure into a power source.",
        "Build sails on the front, Super Glue them together, then right-click the bearing to start it.",
        "",
        "More sails make it spin faster. Right-click again any time to stop it and edit."]),
    Q("sail", "create:white_sail", ["windmill"], n=8, t="Sails", d=[
        "A windmill needs at least 8 sail blocks to count as one.",
        "A simple cross of sails, glued to the bearing, is the easy first windmill."]),
    Q("goggles", "create:goggles", ["start"], d=[
        "Engineer's Goggles show the stress numbers when you look at a machine.",
        "Wear them. Most 'it stopped working' problems are visible through them in one second."]),
    Q("wrench", "create:wrench", ["start"], d=[
        "The Wrench rotates a block when you right-click it.",
        "Shift-right-click picks the block up instantly. You'll use it constantly."]),
    Q("casing", "create:andesite_casing", ["alloy"], d=[
        "Right-click a stripped log or stripped wood with Andesite Alloy to make Andesite Casing.",
        "",
        "Casing is what tidies shafts and cogs into neat boxes, and it's in most andesite recipes."]),
    Q("gearbox", "create:gearbox", ["casing"], d=[
        "A Gearbox turns rotation 90 degrees, out of all four sides around it.",
        "Use it when a shaft needs to turn a corner."]),
    Q("belt", "create:belt_connector", ["casing"], d=[
        "A Mechanical Belt links two shafts. It carries rotation AND items.",
        "Right-click one shaft, then the other. One belt item covers any length."]),
    Q("depot", "create:depot", ["casing"], d=[
        "A Depot holds one stack for a machine above it to work on.",
        "Put it under a press or a fan. It's the simplest workbench in Create."]),
    Q("press", "create:mechanical_press", ["depot", "belt"], tier="finale", shape="hexagon", size=1.25, d=[
        "The Mechanical Press flattens ingots on a depot or belt below it into sheets.",
        "Iron Sheets come next, and they're in a lot of recipes.",
        "",
        "That's the Andesite Age done. Processing is next."]),
  ]))

# ---------------------------------------------------------------- CREATE: PROCESSING
CHAPTERS.append(dict(key="processing", group="create", title="Processing", icon="create:encased_fan",
  shape="circle", quests=[
    Q("start", "check", ["andesite.press"], t="Machines That Make Things", tier="intro", shape="hexagon", size=1.5, d=[
        "Create's real trick is processing: crushing, washing, mixing and baking in bulk.",
        "Every machine here replaces something you'd otherwise do by hand."]),
    Q("sheet", "create:iron_sheet", ["start"], d=[
        "Press an iron ingot into an Iron Sheet.",
        "Sheets go into fans, drills, saws, deployers and most of what follows."]),
    Q("millstone", "create:millstone", ["start"], d=[
        "The Millstone grinds what you drop in the top: ores, flowers, gravel.",
        "Slow but simple. Crushing Wheels are the upgrade."]),
    Q("crushing", "create:crushing_wheel", ["millstone"], n=2, t="Crushing Wheels", tier="milestone", shape="hexagon", d=[
        "Two Crushing Wheels, side by side, spinning toward each other.",
        "Drop items into the gap from above.",
        "",
        "Crushed ores wash into more nuggets than smelting gives you ingots."]),
    Q("fan", "create:encased_fan", ["sheet"], d=[
        "An Encased Fan blows items. Put something in its airflow and it processes everything passing through:",
        "  Water in front: washing.",
        "  Fire or lava in front: smelting.",
        "  Soul fire in front: haunting."]),
    Q("washing", "create:copper_nugget", ["fan", "crushing"], t="Washing Ores", d=[
        "Blow crushed copper ore through water to wash it into Copper Nuggets.",
        "This is the start of every ore line in your base."]),
    Q("basin", "create:basin", ["sheet"], d=[
        "A Basin holds items and fluids for a machine above it.",
        "Pair it with a Press to compact, or a Mixer to mix."]),
    Q("mixer", "create:mechanical_mixer", ["basin"], d=[
        "The Mechanical Mixer goes over a basin and mixes what's inside.",
        "Some recipes need heat. That's the next quest."]),
    Q("burner", "create:empty_blaze_burner", ["mixer"], d=[
        "An Empty Blaze Burner. Right-click a blaze with it to catch one.",
        "Blazes live in Nether fortresses. Bring fire resistance."]),
    Q("blaze", "create:blaze_burner", ["burner"], tier="milestone", shape="hexagon", d=[
        "A lit Blaze Burner under a basin heats it.",
        "Feed it fuel to keep it burning. Heated mixing is how you make brass."]),
    Q("pipe", "create:fluid_pipe", ["basin"], d=[
        "Fluid Pipes move liquids. They need a Mechanical Pump somewhere on the line."]),
    Q("pump", "create:mechanical_pump", ["pipe"], d=[
        "The Mechanical Pump pushes fluid along pipes in the direction of its arrow.",
        "Reverse the rotation to reverse the flow."]),
    Q("tank", "create:fluid_tank", ["pump"], d=[
        "Fluid Tanks stack and merge into one big tank.",
        "You'll need one under a steam engine later."]),
    Q("spout", "create:spout", ["tank"], d=[
        "A Spout fills items below it: buckets, bottles, and some recipes that need a fluid poured on."]),
    Q("deployer", "create:deployer", ["sheet"], tier="finale", shape="hexagon", size=1.25, d=[
        "The Deployer is a robot hand. It uses whatever item you give it, on whatever is in front of it.",
        "",
        "It's how Create automates crafting steps that need a player.",
        "You'll need it for brass parts, so this chapter ends here."]),
  ]))

# ---------------------------------------------------------------- CREATE: BRASS AGE
CHAPTERS.append(dict(key="brass", group="create", title="Brass Age", icon="create:brass_ingot",
  shape="circle", quests=[
    Q("start", "check", ["processing.blaze"], t="The Brass Age", tier="intro", shape="hexagon", size=1.5, d=[
        "Brass is where Create gets clever: smart routing, precise parts and the big machines."]),
    Q("zinc", "create:zinc_ingot", ["start"], d=[
        "Zinc ore spawns underground. Crush it, wash it, smelt it.",
        "You need as much zinc as copper for brass."]),
    Q("ingot", "create:brass_ingot", ["zinc"], tier="milestone", shape="hexagon", d=[
        "Mix copper and zinc in a HEATED basin to make brass.",
        "Mixer on top, Blaze Burner underneath."]),
    Q("quartz", "create:polished_rose_quartz", ["start"], d=[
        "Rose Quartz is crafted from quartz and redstone.",
        "Polish it with sandpaper (or a deployer holding sandpaper) to get Polished Rose Quartz."]),
    Q("tube", "create:electron_tube", ["quartz"], d=[
        "Electron Tubes are in every smart brass component.",
        "Polished rose quartz plus an iron sheet."]),
    Q("casing", "create:brass_casing", ["ingot"], d=[
        "Right-click stripped wood with a brass ingot. Same as andesite casing, one tier up."]),
    Q("funnel", "create:brass_funnel", ["casing"], d=[
        "A Brass Funnel pulls items in or out of inventories and belts.",
        "Unlike the andesite one, it can filter and pull exact amounts."]),
    Q("chute", "create:smart_chute", ["casing"], d=[
        "A Smart Chute is a vertical item path with a filter.",
        "Great for sorting a single item out of a stream."]),
    Q("arm", "create:mechanical_arm", ["tube", "casing"], tier="milestone", shape="hexagon", d=[
        "The Mechanical Arm moves items between nearby blocks.",
        "BEFORE placing it, right-click its inputs (blue) and outputs (orange) while holding it.",
        "Placing it first and then wondering why it does nothing is a rite of passage."]),
    Q("precision", "create:precision_mechanism", ["arm"], tier="finale", shape="hexagon", size=1.25, d=[
        "The Precision Mechanism is made by Sequenced Assembly:",
        "start with a golden sheet, then a deployer adds a cogwheel, a large cogwheel and an iron nugget,",
        "and the whole sequence repeats several times. Some attempts fail and give scrap.",
        "",
        "Build a loop of belts and deployers. Making these by hand is a punishment."]),
    Q("crafter", "create:mechanical_crafter", ["precision"], d=[
        "Mechanical Crafters chain together into a crafting grid of any size.",
        "Use a wrench to connect them, then feed items in."]),
    Q("rsc", "create:rotation_speed_controller", ["precision"], d=[
        "Set an exact RPM. Put it next to a large cogwheel."]),
    Q("steam", "create:steam_engine", ["precision", "processing.tank"], tier="finale", shape="hexagon", size=1.25, d=[
        "The Steam Engine is Create's biggest power source.",
        "Mount it on a Fluid Tank, heat the tank with Blaze Burners and pump water in.",
        "Bigger tank plus more heat means a higher boiler level and more engines it can run.",
        "",
        "Goggles on the tank show the boiler level and what's holding it back."]),
  ]))

# ---------------------------------------------------------------- CREATE: LOGISTICS
CHAPTERS.append(dict(key="logistics", group="create", title="Logistics", icon="create:package_frogport",
  shape="circle", quests=[
    Q("start", "check", ["brass.casing"], t="Moving Things Around", tier="intro", shape="hexagon", size=1.5, d=[
        "Once you have more than a few machines, moving items becomes the real work.",
        "This chapter goes from simple funnels to Create's package network."]),
    Q("afunnel", "create:andesite_funnel", ["start"], d=[
        "An Andesite Funnel puts items into, or takes them out of, whatever it faces."]),
    Q("chute", "create:chute", ["start"], d=[
        "A Chute drops items straight down. Put a fan under it and it lifts them instead."]),
    Q("vault", "create:item_vault", ["start"], d=[
        "An Item Vault is a big multiblock store. Place vaults side by side and they merge."]),
    Q("packager", "create:packager", ["vault"], tier="milestone", shape="hexagon", d=[
        "A Packager sits against an inventory and wraps its items into a package.",
        "Put a sign on the packager to give its packages an address."]),
    Q("frogport", "create:package_frogport", ["packager"], d=[
        "A Frogport sends and catches packages on a Chain Conveyor.",
        "Packages find their own way to a frog with a matching address."]),
    Q("link", "create:stock_link", ["packager"], d=[
        "A Stock Link on a packager connects that inventory to a logistics network.",
        "To join an existing network, right-click a placed Stock Link with the new one before placing it."]),
    Q("ticker", "create:stock_ticker", ["link"], tier="finale", shape="hexagon", size=1.25, d=[
        "The Stock Ticker shows everything in the network and lets you order from it.",
        "Pick what you want, give an address, and a package turns up.",
        "",
        "That's a whole base you can request from one screen."]),
    Q("gauge", "create:factory_gauge", ["ticker"], opt=True, shape="diamond", d=[
        "Optional, and a big one. A Factory Gauge keeps a stock level topped up",
        "by sending ingredients to a machine whenever the network runs low.",
        "Watch its ponder scene before building one."]),
    Q("display", "create:display_link", ["start"], opt=True, shape="diamond", d=[
        "Optional. A Display Link reads a block and writes what it sees onto a sign or Display Board."]),
  ]))

# ---------------------------------------------------------------- CREATE: TRAINS
CHAPTERS.append(dict(key="trains", group="create", title="Trains", icon="create:track_station",
  shape="circle", quests=[
    Q("start", "check", ["brass.casing"], t="All Aboard", tier="intro", shape="hexagon", size=1.5, d=[
        "No /tpa this season. A railway is the cheapest way to connect your base to the world."]),
    Q("track", "create:track", ["start"], n=16, t="Lay Some Track", d=[
        "Create Track curves smoothly: right-click one end, then where it should go.",
        "Count is the lesson here. Any real line needs a lot of it."]),
    Q("station", "create:track_station", ["track"], tier="milestone", shape="hexagon", d=[
        "Right-click a track with the Station to place it beside the line.",
        "The station is where trains are built, named and stop."]),
    Q("rcasing", "create:railway_casing", ["start"], d=[
        "Railway Casing is made from brass casing and a sturdy sheet.",
        "Right-click a track with it to place a bogey: the wheels of your train."]),
    Q("controls", "create:controls", ["rcasing"], d=[
        "Train Controls are where you drive from. Every train needs one.",
        "A second set facing the other way lets it leave stations in both directions."]),
    Q("build", "check", ["controls", "station"], t="Assemble a Train", tier="milestone", shape="hexagon", d=[
        "Open the station screen and switch to Assembly Mode.",
        "Place bogeys on the track, build the body on top, add the controls and Super Glue it all together.",
        "Then confirm in the station screen.",
        "",
        "Fuel in a chest or barrel on board makes it faster.",
        "",
        "Tick this when your train rolls."]),
    Q("signal", "create:track_signal", ["build"], d=[
        "Signals split a line into blocks so two trains never share one.",
        "You need them as soon as a second train runs on the same track."]),
    Q("schedule", "create:schedule", ["build"], tier="finale", shape="hexagon", size=1.25, d=[
        "A Schedule makes a train drive itself: go here, wait, go there.",
        "Hand it to the train's conductor, a mob seated at the controls, and it runs on its own."]),
  ]))

# ---------------------------------------------------------------- FLIGHT: FIRST AIRSHIP
CHAPTERS.append(dict(key="airship", group="flight", title="Your First Airship", icon="aeronautics:white_envelope",
  shape="rsquare", quests=[
    Q("start", "check", ["andesite.casing"], t="Why We're All Here", tier="intro", shape="hexagon", size=1.5, d=[
        "Create: Aeronautics is the reason this server exists.",
        "You build a ship on the ground as a normal structure, then assemble it",
        "and it becomes a real physics object that flies.",
        "",
        "Rule one: test small. Learn on a tiny balloon before you sink hours into a big one."]),
    Q("assembler", "simulated:physics_assembler", ["start"], tier="milestone", shape="hexagon", d=[
        "The Physics Assembler turns glued blocks into a Simulated Contraption.",
        "Glue the blocks you want, then hold right-click on the assembler and pull its lever.",
        "",
        "Once assembled, any Physics Assembler can disassemble it again."]),
    Q("glue", "simulated:honey_glue", ["assembler"], d=[
        "Honey Glue marks what belongs to the ship. Click two corners to make a glued area,",
        "then Ctrl+Scroll to grow or shrink it. Hold ALT to place a corner in mid-air.",
        "",
        "Honey Glue only sticks to Simulated Contraptions, so it won't fight your other Create machines."]),
    Q("envelope", "aeronautics:white_envelope", ["assembler"], n=8, t="A Balloon", d=[
        "Hot Air Envelope blocks enclose a space that makes lift when it fills with hot air.",
        "The balloon has to be airtight, and blocks inside it take up volume.",
        "",
        "Dye them by right-clicking with any dye."]),
    Q("burner", "aeronautics:adjustable_burner", ["envelope"], tier="milestone", shape="hexagon", d=[
        "The Hot Air Burner fills the balloon above it, from the top down.",
        "It only burns while it has a redstone signal.",
        "",
        "Its panel sets the maximum output. A weaker redstone signal scales it down.",
        "Several burners can feed one balloon."]),
    Q("throttle", "simulated:throttle_lever", ["burner"], d=[
        "The Throttle Lever gives an exact analog redstone signal: click and drag to set it.",
        "Wire it to your burner and you have an altitude control."]),
    Q("lift", "check", ["throttle", "glue"], t="Lift Off", tier="milestone", shape="hexagon", d=[
        "Build a small platform, a balloon over a burner, glue it, assemble, and turn the throttle up.",
        "",
        "Lift gets weaker the higher you go, so a ship settles where lift equals its weight.",
        "Change the throttle and it settles at a new height. That is how you steer up and down.",
        "",
        "Tick this when your first balloon leaves the ground."]),
    Q("prop", "aeronautics:wooden_propeller", ["lift"], d=[
        "A propeller pushes the ship along. Give it rotation and it makes thrust.",
        "Reverse the rotation to reverse the thrust."]),
    Q("bearing", "aeronautics:propeller_bearing", ["prop"], d=[
        "The Propeller Bearing spins any structure in front of it as a propeller.",
        "It needs at least 2 sail-like blocks. More sails push harder.",
        "",
        "A wrench flips the thrust without touching the rotation.",
        "Moving propellers push less, because the air is already moving past them."]),
    Q("engine", "simulated:white_portable_engine", ["prop"], d=[
        "A Portable Engine burns fuel for rotation, and runs in either direction.",
        "It's the simplest way to power propellers on a ship. A Blaze Cake superheats it."]),
    Q("altimeter", "simulated:altitude_sensor", ["lift"], opt=True, shape="diamond", d=[
        "Optional. The Altitude Sensor gives a redstone signal that changes with height.",
        "Goggles on it show the exact altitude and air pressure."]),
    Q("goggles", "aeronautics:aviators_goggles", ["lift"], opt=True, shape="diamond", d=[
        "Optional, but stylish. Aviator's Goggles do everything Engineer's Goggles do."]),
    Q("claim", "aeroclaims:claim_block", ["bearing", "engine", "welcome.ship_rules"], t="Claim Your Ship",
      tier="finale", shape="hexagon", size=1.25, give=[("aeroclaims:claim_block", 1)], d=[
        "Your ship is NOT protected until you place a Claim Block on it.",
        "Each block covers 250 ship blocks and uses one of your chunks.",
        "",
        "Place, Claim, Name. In that order.",
        "Never pick up a claimed ship and place it again. It gets a new ID and the claim is lost.",
        "",
        "Here's a spare Claim Block for the next one."]),
    Q("name", "check", ["claim"], t="Name It", sub="/shipname", d=[
        "Stand on your ship and type /shipname <name>.",
        "Do it now. When you own three ships all called 'ship' you'll wish you had."]),
    Q("merge", "minecraft:slime_ball", ["lift"], opt=True, shape="diamond", t="Splits and Merges", d=[
        "Optional. If part of a ship gets cut off, it splits into its own contraption.",
        "Right-click with a Slime Ball to merge two contraptions back into one."]),
    Q("levitite", "aeronautics:levitite", ["lift"], opt=True, shape="diamond", d=[
        "Optional. Levitite keeps a ship floating with no balloon at all,",
        "if there's enough of it. It can't climb on its own, so you still need thrust.",
        "",
        "It's made by heating Levitite Blend. Once it crystallizes you can't get the blend back."]),
  ]))

# ---------------------------------------------------------------- FLIGHT: GROUND VEHICLES
CHAPTERS.append(dict(key="vehicles", group="flight", title="Ground Vehicles", icon="offroad:tire",
  shape="rsquare", quests=[
    Q("start", "check", ["airship.assembler"], t="Wheels First", tier="intro", shape="hexagon", size=1.5, d=[
        "Not everything has to fly. A car uses the same Physics Assembler as a ship,",
        "and it's a cheaper way to learn how contraptions handle."]),
    Q("mount", "offroad:wheel_mount", ["start"], n=4, t="Wheel Mounts", d=[
        "A Wheel Mount holds a tire and drives it from the rotation it gets.",
        "Four of them for a basic car."]),
    Q("tire", "offroad:tire", ["mount"], n=4, t="Tires", d=[
        "Right-click a wheel mount with a tire to fit it.",
        "They come in small, normal, large and monstrous sizes."]),
    Q("engine", "simulated:white_portable_engine", ["start"], d=[
        "Something has to turn the wheels. A Portable Engine burns fuel and spins either way."]),
    Q("steer", "create:redstone_link", ["tire"], t="Steering", d=[
        "Power a wheel mount from the SIDE and the wheel steers toward that side.",
        "Redstone Links on the front mounts let you steer without wires."]),
    Q("brake", "check", ["tire"], t="Brakes", d=[
        "Power a wheel mount from the TOP and it brakes, adding friction.",
        "Build brakes before you build speed. Tick this when your car can stop."]),
    Q("controller", "create:linked_controller", ["steer"], tier="milestone", shape="hexagon", d=[
        "The Linked Controller binds your keys to redstone link frequencies.",
        "Bind forward, back, left, right and brake, and you're driving."]),
    Q("drive", "check", ["controller", "brake", "engine"], t="First Drive", tier="milestone", shape="hexagon", d=[
        "Glue it, assemble it, and drive it somewhere.",
        "Tick this when you've driven a car you built."]),
    Q("wheel", "simulated:steering_wheel", ["drive"], opt=True, shape="diamond", d=[
        "Optional. The Steering Wheel gives exact rotation: hold right-click and move the mouse.",
        "Sneak to snap to 45 degrees."]),
    Q("handle", "simulated:iron_handle", ["drive"], opt=True, shape="diamond", d=[
        "Optional. A Handle lets you grab a contraption and move it by hand.",
        "Handy when your car is stuck in a ditch."]),
    Q("drill", "offroad:borehead_bearing", ["drive"], tier="finale", shape="hexagon", size=1.25, d=[
        "A Borehead Bearing with Rock Cutting Wheels is a tunnel-boring drill.",
        "It needs a storage container on the contraption to hold what it digs.",
        "",
        "It runs at a quarter of the speed you give it, and slows down the more it tries to break at once."]),
  ]))

# ---------------------------------------------------------------- FLIGHT: ADVANCED
CHAPTERS.append(dict(key="advflight", group="flight", title="Advanced Flight", icon="aeronautics:gyroscopic_propeller_bearing",
  shape="rsquare", quests=[
    Q("start", "check", ["airship.lift", "brass.precision"], t="Beyond the Balloon", tier="intro", shape="hexagon", size=1.5, d=[
        "Balloons are easy. Helicopters, steam lift and remote-controlled ships take a bit more."]),
    Q("gyro", "aeronautics:gyroscopic_propeller_bearing", ["start"], tier="milestone", shape="hexagon", d=[
        "Normal propeller bearings make wobbly helicopters.",
        "The Gyroscopic Propeller Bearing tries to keep its propeller upright.",
        "A redstone signal resets its tilt to zero."]),
    Q("vent", "aeronautics:steam_vent", ["start"], d=[
        "A Steam Vent lifts with steam instead of hot air.",
        "Like a steam engine, it needs heat, water and enough boiler space."]),
    Q("smart", "aeronautics:smart_propeller", ["start"], d=[
        "Right-click a Smart Propeller with a wrench to flip which way it pushes."]),
    Q("sail", "simulated:white_symmetric_sail", ["start"], d=[
        "Regular sails make lift when they move. Symmetric Sails only make drag.",
        "Angle them against the airflow to turn or steady the ship."]),
    Q("gimbal", "simulated:gimbal_sensor", ["gyro"], d=[
        "The Gimbal Sensor outputs redstone based on how the ship tilts.",
        "Wire it to your thrust and the ship can level itself."]),
    Q("velocity", "simulated:velocity_sensor", ["gimbal"], opt=True, shape="diamond", d=[
        "Optional. Outputs redstone based on how fast the contraption is moving."]),
    Q("rope", "simulated:rope_winch", ["start"], d=[
        "A Rope Winch reels a rope in and out when it's given rotation.",
        "Connect it to a Rope Connector with a Rope Coupling. Cargo hooks, tow lines, anchors."]),
    Q("dock", "simulated:docking_connector", ["rope"], d=[
        "Docking Connectors lock two contraptions rigidly together.",
        "Once locked, items and fluids can pass through in both directions."]),
    Q("nav", "simulated:navigation_table", ["gimbal"], d=[
        "The Navigation Table points a redstone signal toward a destination,",
        "set by the navigation item you put in it. The signal is strongest when you face it."]),
    Q("wire", "drivebywire:controller_hub", ["nav"], tier="finale", shape="hexagon", size=1.25, d=[
        "Drive-By-Wire runs Cables between your controls and the rest of the ship.",
        "Bind your controller to a Controller Hub and you can fly the ship from the cockpit,",
        "or from far away."]),
  ]))

# ---------------------------------------------------------------- INDUSTRY: STEEL
CHAPTERS.append(dict(key="steel", group="industry", title="Steel", icon="tfmg:steel_ingot",
  shape="circle", quests=[
    Q("start", "check", ["processing.mixer"], t="Heavy Industry", tier="intro", shape="hexagon", size=1.5, d=[
        "TFMG adds real industry on top of Create: coke, blast furnaces and steel.",
        "It's a long ladder. Each step unlocks the next."]),
    Q("oven", "tfmg:coke_oven", ["start"], d=[
        "The Coke Oven slowly bakes coal into Coal Coke.",
        "It's slow, so build a row of them.",
        "Coal goes in anywhere. Creosote comes out the sides, and the coke falls out when it's done."]),
    Q("coke", "tfmg:coal_coke", ["oven"], d=[
        "Coal Coke burns hotter than coal. It's the fuel your blast furnace runs on."]),
    Q("creosote", "tfmg:creosote_bucket", ["oven"], opt=True, shape="diamond", d=[
        "Optional. The Coke Oven's by-product. Don't waste it, some recipes want it."]),
    Q("fireclay", "tfmg:fireclay_ball", ["start"], d=[
        "Fireclay is found near the surface. It's what fireproof bricks are made from."]),
    Q("brick", "tfmg:fireproof_bricks", ["fireclay"], d=[
        "Fireproof Bricks are the walls of your blast furnace."]),
    Q("output", "tfmg:blast_furnace_output", ["brick", "coke"], tier="milestone", shape="hexagon", d=[
        "The Blast Furnace Output is the heart of the furnace.",
        "Build a cylinder of fireproof bricks around it, with one Blast Furnace Hatch in the wall for hot air.",
        "Everything goes in through the hole at the top."]),
    Q("reinf", "tfmg:blast_furnace_reinforcement", ["output"], d=[
        "Blast furnaces need reinforcements around them to work, and more of them makes it faster."]),
    Q("steel", "tfmg:steel_ingot", ["reinf"], tier="finale", shape="hexagon", size=1.25, d=[
        "Steel. Crushed raw iron, limesand and coal coke dust go in the top, molten steel comes out to be cast.",
        "",
        "Almost everything in TFMG is built from it. Make a lot."]),
    Q("casing", "tfmg:steel_casing", ["steel"], d=[
        "Steel Casing: the next tier of casing, for steel-age machines."]),
    Q("mech", "tfmg:steel_mechanism", ["casing"], d=[
        "The Steel Mechanism is TFMG's version of the precision mechanism. Every big machine needs some."]),
  ]))


# ---------------------------------------------------------------------------------------------
def snbt(v, ind=0):
    p = "\t" * ind
    if isinstance(v, dict):
        if not v:
            return "{ }"
        lines = []
        for k in sorted(v):
            lines.append(f"{p}\t{k}: {snbt(v[k], ind + 1)}")
        return "{\n" + "\n".join(lines) + f"\n{p}}}"
    if isinstance(v, list):
        if not v:
            return "[ ]"
        if all(isinstance(x, str) for x in v):
            return "[" + ", ".join(snbt(x) for x in v) + "]"
        return "[\n" + "\n".join(f"{p}\t{snbt(x, ind + 1)}" for x in v) + f"\n{p}]"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return f"{v}d"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, Raw):
        return str(v)
    return '"' + str(v).replace("\\", "\\\\").replace('"', '\\"') + '"'


class Raw(str):
    pass


def check_item(i, where, reward=False):
    if i not in VALID:
        errors.append(f"{where}: unknown item {i}")
    if reward and i in BANNED_REWARDS:
        errors.append(f"{where}: banned reward {i}")


def layout(quests):
    """Columns by dependency depth inside the chapter; rows centred per column."""
    keys = {q["key"]: q for q in quests}
    depth = {}

    def d(k, seen=()):
        if k in depth:
            return depth[k]
        if k in seen:
            errors.append(f"cycle at {k}")
            return 0
        local = [x for x in keys[k]["deps"] if "." not in x]
        depth[k] = 0 if not local else 1 + max(d(x, seen + (k,)) for x in local)
        return depth[k]

    cols = {}
    for q in quests:
        cols.setdefault(d(q["key"]), []).append(q["key"])
    pos = {}
    for c, ks in cols.items():
        for r, k in enumerate(ks):
            pos[k] = (c * 2.0, (r - (len(ks) - 1) / 2) * 1.75)
    return pos


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT + "/chapters")
    os.makedirs(OUT + "/lang")
    lang = {"file.0000000000000001.title": "Coffee's Aero SMP — Season 3 Guide"}
    group_ids = {}
    for g, title in GROUPS:
        group_ids[g] = hid("group", g)
        lang[f"chapter_group.{group_ids[g]}.title"] = title

    qids = {}
    for ch in CHAPTERS:
        for q in ch["quests"]:
            qids[f"{ch['key']}.{q['key']}"] = hid("quest", ch["key"], q["key"])

    total = 0
    in_group = {}
    for ch in CHAPTERS:
        order = in_group[ch["group"]] = in_group.get(ch["group"], -1) + 1  # FTB numbers per group
        cid = hid("chapter", ch["key"])
        lang[f"chapter.{cid}.title"] = text(ch["title"], ch["key"])
        check_item(ch["icon"], ch["key"] + " icon")
        pos = layout(ch["quests"])
        out_q = []
        for q in ch["quests"]:
            where = f"{ch['key']}.{q['key']}"
            qid = qids[where]
            deps = []
            for dk in q["deps"]:
                full = dk if "." in dk else f"{ch['key']}.{dk}"
                if full not in qids:
                    errors.append(f"{where}: dangling dependency {dk}")
                else:
                    deps.append(qids[full])
            if q["item"] == "check":
                task = {"id": hid("task", where), "type": "checkmark"}
            else:
                check_item(q["item"], where)
                task = {"id": hid("task", where), "type": "item", "item": {"count": 1, "id": q["item"]}}
                if q.get("n", 1) > 1:
                    task["count"] = Raw(f"{q['n']}L")
            tier = q.get("tier", "normal")
            rewards = [{"id": hid("xp", where), "type": "xp", "xp": XP[tier]}]
            for gi, (gid, gn) in enumerate(q.get("give", [])):
                check_item(gid, where + " reward", reward=True)
                if gn > 4:
                    errors.append(f"{where}: reward of {gn}x {gid} is over the 4-item cap")
                rewards.append({"id": hid("give", where, str(gi)), "type": "item", "count": gn,
                                "item": {"count": 1, "id": gid}})
            x, y = q.get("pos", pos[q["key"]])
            qd = {"id": qid, "tasks": [task], "rewards": rewards, "x": float(x), "y": float(y)}
            if deps:
                qd["dependencies"] = deps
            if q.get("shape"):
                qd["shape"] = q["shape"]
            if q.get("size"):
                qd["size"] = float(q["size"])
            if q.get("opt"):
                qd["optional"] = True
            if q["item"] == "check" and not q.get("icon"):
                qd["icon"] = {"id": ch["icon"]}
            out_q.append(qd)
            if q.get("t"):
                lang[f"quest.{qid}.title"] = text(q["t"], where)
            if q.get("sub"):
                lang[f"quest.{qid}.quest_subtitle"] = text(q["sub"], where)
            lang[f"quest.{qid}.quest_desc"] = [text(line, where) for line in q["d"]]
            total += 1
        chapter = {
            "default_hide_dependency_lines": False, "default_quest_shape": ch.get("shape", ""),
            "filename": ch["key"], "group": group_ids[ch["group"]], "icon": {"id": ch["icon"]},
            "id": cid, "images": [], "order_index": order, "quest_links": [], "quests": out_q,
        }
        with open(f"{OUT}/chapters/{ch['key']}.snbt", "w", encoding="utf-8", newline="\n") as f:
            f.write(snbt(chapter) + "\n")

    data = {
        "default_autoclaim_rewards": "disabled", "default_consume_items": False,
        "default_quest_disable_jei": False, "default_quest_shape": "circle", "default_reward_team": False,
        "detection_delay": 20, "disable_gui": False, "drop_book_on_death": False, "drop_loot_crates": False,
        "emergency_items_cooldown": 300, "fallback_locale": "en_us", "grid_scale": 0.5,
        "hide_excluded_quests": False, "icon": {"id": "create:cogwheel"}, "lock_message": "",
        "loot_crate_no_drop": {"boss": 0, "monster": 600, "passive": 4000}, "pause_game": False,
        "progression_mode": "linear", "show_lock_icons": True, "verify_on_load": False, "version": 13,
    }
    with open(f"{OUT}/data.snbt", "w", encoding="utf-8", newline="\n") as f:
        f.write(snbt(data) + "\n")
    with open(f"{OUT}/chapter_groups.snbt", "w", encoding="utf-8", newline="\n") as f:
        f.write(snbt({"chapter_groups": [{"id": group_ids[g]} for g, _ in GROUPS]}) + "\n")
    with open(f"{OUT}/lang/en_us.snbt", "w", encoding="utf-8", newline="\n") as f:
        f.write(snbt(lang) + "\n")

    if errors:
        print("\n".join("ERROR " + e for e in errors))
        sys.exit(1)
    print(f"ok: {len(CHAPTERS)} chapters, {total} quests -> {OUT}")


if __name__ == "__main__":
    main()
