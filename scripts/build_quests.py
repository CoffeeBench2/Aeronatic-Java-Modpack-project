"""
Generates the Coffee's Aero SMP questline as FTB Quests SNBT.

Why a generator and not hand-written SNBT: quest ids must be unique 16-hex strings, every quest
must reference its dependency by id, and a single malformed entry makes FTB Library drop the whole
chapter with one line in the log. Emitting from data means ids cannot collide, dependencies cannot
dangle, and the shape of every quest is identical.

🔑 EVERY item id used here is checked against `scripts/_questgen/valid_ids.json`, which is extracted
from the actual mod jars in overrides/mods. A typo'd Create id is not a visible error in game — the
quest simply cannot be completed — so the build fails loudly instead.

Progression is deliberately flat: gather -> andesite -> kinetics -> brass -> automation -> flight.
Rewards are materials the next chapter consumes, never gear that skips a step. Claim blocks are the
one "real" reward and are gated to chapter completions.
"""
import json
import os
import hashlib

ROOT = "server-assets/ftbquests"
# Every item id every jar in overrides/mods declares, keyed by namespace. Extracted from the jars
# themselves, so this cannot drift from what the pack actually ships.
_vocab = json.load(open("scripts/_questgen/vocab.json"))
VALID = {i for ids in _vocab.values() for i in ids}

# Vanilla ids used below.
#
# 🔴 This list is TRUSTED INPUT, so an id typed wrong here defeats the whole validator. It already
# did once: "minecraft:zinc_ore" was added by hand and sailed through check(), when zinc ore is
# create:zinc_ore and no such vanilla block exists. Anything mod-added belongs in the extracted
# vocabulary, never here. Keep this list to ids that are unambiguously vanilla.
VANILLA = {
    "minecraft:oak_log", "minecraft:cobblestone", "minecraft:andesite", "minecraft:iron_ingot",
    "minecraft:copper_ingot", "minecraft:raw_copper", "minecraft:coal",
    "minecraft:bread", "minecraft:iron_pickaxe", "minecraft:crafting_table", "minecraft:furnace",
    "minecraft:chest", "minecraft:water_bucket", "minecraft:redstone", "minecraft:gold_ingot",
    "minecraft:diamond", "minecraft:emerald", "minecraft:paper", "minecraft:book",
    "minecraft:stick", "minecraft:string", "minecraft:leather", "minecraft:wheat",
    "minecraft:dried_kelp", "minecraft:blaze_rod", "minecraft:obsidian", "minecraft:lapis_lazuli",
    "minecraft:bucket", "minecraft:glass", "minecraft:torch", "minecraft:cooked_beef",
    "minecraft:golden_carrot", "minecraft:experience_bottle", "minecraft:iron_block",
    "minecraft:oak_planks", "minecraft:sand", "minecraft:clay_ball", "minecraft:netherite_scrap",
}
VALID |= VANILLA

_used = set()


def qid(*parts):
    """Stable unique 16-hex id from a semantic key, so re-running never reshuffles ids."""
    h = hashlib.sha1("|".join(parts).encode()).hexdigest()[:16].upper()
    while h in _used:
        h = hashlib.sha1((h + "x").encode()).hexdigest()[:16].upper()
    _used.add(h)
    return h


RESTRICTED = set(json.load(open("scripts/_questgen/restricted.json")))


def check(item):
    if item in RESTRICTED:
        raise SystemExit(f"REFUSING TO BUILD: {item!r} is on the toolgun_restricted list")
    if item not in VALID:
        raise SystemExit(f"REFUSING TO BUILD: unknown item id {item!r} — not in any pack jar")
    return item


def task(key, item, count):
    return {"id": qid("task", key, item), "type": "item", "item": check(item), "count": count}


def r_item(key, item, count):
    return {"id": qid("rew", key, item), "type": "item", "item": check(item), "count": count}


def r_xp(key, amount):
    return {"id": qid("xp", key), "type": "xp", "xp": amount}


def snbt(v, indent=0):
    pad = "\t" * indent
    if isinstance(v, dict):
        if not v:
            return "{ }"
        out = "{\n"
        for k, val in v.items():
            out += f"{pad}\t{k}: {snbt(val, indent + 1)}\n"
        return out + pad + "}"
    if isinstance(v, list):
        if not v:
            return "[ ]"
        out = "[\n"
        for e in v:
            out += f"{pad}\t{snbt(e, indent + 1)}\n"
        return out + pad + "]"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, Long):
        return f"{int(v)}L"
    if isinstance(v, Double):
        return f"{float(v)}d"
    if isinstance(v, int):
        return str(v)
    return '"' + str(v).replace("\\", "\\\\").replace('"', '\\"') + '"'


class Long(int):
    pass


class Double(float):
    pass


class Chapter:
    def __init__(self, key, title, icon, order, subtitle):
        self.key, self.title, self.icon, self.order = key, title, icon, order
        self.subtitle = subtitle
        self.quests = []
        self.last = None
        self.group = ""

    def q(self, key, title, x, y, tasks, rewards, desc, deps=None, shape="", optional=False):
        i = qid("quest", self.key, key)
        entry = {
            "title": title,
            "x": Double(x),
            "y": Double(y),
            "description": desc,
            "id": i,
            "tasks": tasks,
            "rewards": rewards,
        }
        if shape:
            entry["shape"] = shape
        if optional:
            entry["optional"] = True
        if deps:
            entry["dependencies"] = deps
        self.quests.append(entry)
        self.last = i
        return i

    def render(self):
        return snbt({
            "id": qid("chapter", self.key),
            "group": self.group,
            "order_index": self.order,
            "filename": self.key,
            "title": self.title,
            "icon": check(self.icon),
            "subtitle": [self.subtitle],
            "default_quest_shape": "",
            "default_hide_dependency_lines": False,
            "quests": self.quests,
        })


chapters = []


# ── 1. First Steps ────────────────────────────────────────────────────────────────────────────
c = Chapter("first_steps", "First Steps", "minecraft:oak_log", 0,
            "Everything starts with a tree and a bad idea.")
chapters.append(c)
a = c.q("wood", "Punch a Tree", 0, 0,
        [task("wood", "minecraft:oak_log", Long(16))],
        [r_item("wood", "minecraft:oak_planks", 16), r_xp("wood", 5)],
        ["Every empire begins here. Get some wood."], shape="square")
b = c.q("bench", "A Place to Work", 0, 2,
        [task("bench", "minecraft:crafting_table", Long(1))],
        [r_item("bench", "minecraft:stick", 16)],
        ["A crafting table. You know how this goes."], [a])
d = c.q("stone", "Into the Ground", -2, 3,
        [task("stone", "minecraft:cobblestone", Long(32))],
        [r_item("stone", "minecraft:torch", 16)],
        ["Cobblestone is the whole tech tree in disguise."], [b])
e = c.q("furnace", "Fire It Up", 2, 3,
        [task("furnace", "minecraft:furnace", Long(1))],
        [r_item("furnace", "minecraft:coal", 8)],
        ["Smelting comes before automating smelting."], [b])
f = c.q("iron", "Iron Age", 0, 5,
        [task("iron", "minecraft:iron_ingot", Long(12))],
        [r_item("iron", "minecraft:iron_pickaxe", 1), r_xp("iron", 10)],
        ["Twelve iron. Create eats iron, so get comfortable mining it."], [d, e])
g = c.q("copper", "Copper Counts", -2, 6,
        [task("copper", "minecraft:copper_ingot", Long(16))],
        [r_item("copper", "minecraft:raw_copper", 8)],
        ["Copper does the plumbing later. Stock up."], [f])
h = c.q("zinc", "Zinc, Somewhere Down There", 2, 6,
        [task("zinc", "create:zinc_ore", Long(8))],
        [r_item("zinc", "minecraft:coal", 16)],
        ["Zinc spawns in stone, a little deeper than you'd like."], [f])
i1 = c.q("andesite", "Andesite Alloy", 0, 8,
         [task("andesite", "create:andesite_alloy", Long(8))],
         [r_item("andesite", "create:andesite_alloy", 8), r_xp("andesite", 15)],
         ["Andesite + iron. The single most important item in the pack.",
          "You will make hundreds of these. Sorry."], [g, h], shape="hexagon")
c.q("claim1", "Somewhere to Put It", 0, 10,
    [task("claim1", "create:andesite_alloy", Long(16))],
    [r_item("claim1", "aeroclaims:claim_block", 1), r_xp("claim1", 20)],
    ["A Claim Block marks land as yours. Place it and nobody else can build there.",
     "Take one. You have earned somewhere to stand."], [i1], shape="diamond")

# ── 2. The Andesite Age ───────────────────────────────────────────────────────────────────────
c = Chapter("andesite_age", "The Andesite Age", "create:andesite_casing", 1,
            "Rotation is power. Power is everything.")
chapters.append(c)
a = c.q("shaft", "Shafts", 0, 0,
        [task("shaft", "create:shaft", Long(8))],
        [r_item("shaft", "create:andesite_alloy", 4)],
        ["Rotation has to travel somehow."], shape="square")
b = c.q("cog", "Cogwheels", -2, 2,
        [task("cog", "create:cogwheel", Long(8))],
        [r_item("cog", "create:cogwheel", 4)],
        ["Small cogs mesh with large cogs to change speed. Learn this now."], [a])
d = c.q("largecog", "Large Cogwheels", 2, 2,
        [task("largecog", "create:large_cogwheel", Long(4))],
        [r_item("largecog", "create:large_cogwheel", 2)],
        ["Large into small speeds up. Small into large slows down and adds stress capacity."], [a])
e = c.q("casing", "Andesite Casing", 0, 4,
        [task("casing", "create:andesite_casing", Long(8))],
        [r_item("casing", "create:andesite_casing", 8)],
        ["Casing turns raw machinery into something that looks deliberate."], [b, d])
f = c.q("waterwheel", "Free Power", -3, 5,
        [task("waterwheel", "create:water_wheel", Long(1))],
        [r_item("waterwheel", "create:water_wheel", 1), r_xp("waterwheel", 10)],
        ["A water wheel in flowing water is the cheapest power you will ever get.",
         "It is slow. That is fine — gear it up."], [e])
g = c.q("millstone", "Grind It", 0, 6,
        [task("millstone", "create:millstone", Long(1))],
        [r_item("millstone", "minecraft:wheat", 16)],
        ["Your first processing machine. Ore into dust, wheat into flour."], [e])
h = c.q("press", "Mechanical Press", 3, 5,
        [task("press", "create:mechanical_press", Long(1))],
        [r_item("press", "create:andesite_alloy", 8)],
        ["Presses make sheets. Sheets make almost everything else."], [e])
i2 = c.q("fan", "Encased Fan", 0, 8,
         [task("fan", "create:encased_fan", Long(1))],
         [r_item("fan", "minecraft:coal", 32)],
         ["A fan over fire smelts. Over water it washes. Over ice it freezes.",
          "One block, four recipes."], [g, h])
c.q("belt", "Belts", -3, 8,
    [task("belt", "create:belt_connector", Long(1))],
    [r_item("belt", "create:andesite_alloy", 8)],
    ["Two shafts and a belt item. Items ride, players ride, everything moves."], [f, g])
c.q("claim2", "Room to Grow", 0, 10,
    [task("claim2", "create:andesite_casing", Long(16))],
    [r_item("claim2", "aeroclaims:claim_block", 2), r_xp("claim2", 25)],
    ["Two more Claim Blocks. Your first factory deserves a fence."], [i2], shape="diamond")

# ── 3. Kinetics ───────────────────────────────────────────────────────────────────────────────
c = Chapter("kinetics", "Kinetics", "create:gearbox", 2,
            "Speed, stress, and the art of not overloading everything.")
chapters.append(c)
a = c.q("gearbox", "Gearbox", 0, 0,
        [task("gearbox", "create:gearbox", Long(2))],
        [r_item("gearbox", "create:shaft", 8)],
        ["Turn a corner without a mess of cogs."], shape="square")
b = c.q("gearshift", "Gearshift", -2, 2,
        [task("gearshift", "create:gearshift", Long(1))],
        [r_item("gearshift", "minecraft:redstone", 16)],
        ["Redstone reverses rotation. Useful for doors, pistons and regret."], [a])
d = c.q("clutch", "Clutch", 2, 2,
        [task("clutch", "create:clutch", Long(1))],
        [r_item("clutch", "minecraft:redstone", 16)],
        ["Cut rotation without breaking the line."], [a])
e = c.q("speed", "Rotation Speed Controller", 0, 4,
        [task("speed", "create:rotation_speed_controller", Long(1))],
        [r_item("speed", "create:brass_ingot", 4)],
        ["Set an exact RPM instead of stacking gear ratios and hoping."], [b, d])
f = c.q("stress", "Read the Numbers", -2, 5,
        [task("stress", "create:stressometer", Long(1))],
        [r_item("stress", "create:goggles", 1), r_xp("stress", 10)],
        ["A stressometer plus goggles tells you why everything stopped.",
         "It is almost always stress."], [a])
g = c.q("drill", "Mechanical Drill", 2, 5,
        [task("drill", "create:mechanical_drill", Long(1))],
        [r_item("drill", "create:andesite_alloy", 8)],
        ["Breaks blocks. Put it on a contraption and it becomes a mining machine."], [e])
h = c.q("saw", "Mechanical Saw", 0, 6,
        [task("saw", "create:mechanical_saw", Long(1))],
        [r_item("saw", "minecraft:oak_log", 32)],
        ["Logs to planks at a better ratio, and it cuts trees down on its own."], [e])
c.q("depot", "Depot & Chute", -2, 7,
    [task("depot", "create:depot", Long(2)), task("chute", "create:chute", Long(4))],
    [r_item("depot", "create:andesite_alloy", 8)],
    ["A depot holds one item still so a machine can work on it.",
     "A chute moves items vertically without a belt."], [h])
i3 = c.q("funnel", "Funnels", 2, 7,
         [task("funnel", "create:andesite_funnel", Long(4))],
         [r_item("funnel", "create:andesite_funnel", 4)],
         ["Funnels move items between inventories and belts. The whole logistics game."], [g])
c.q("claim3", "Industrial Estate", 0, 9,
    [task("claim3", "create:cogwheel", Long(32))],
    [r_item("claim3", "aeroclaims:claim_block", 2), r_xp("claim3", 30)],
    ["Machines sprawl. Claim the sprawl."], [i3], shape="diamond")

# ── 4. The Brass Age ──────────────────────────────────────────────────────────────────────────
c = Chapter("brass_age", "The Brass Age", "create:brass_casing", 3,
            "Zinc and copper, and suddenly everything is smarter.")
chapters.append(c)
a = c.q("zingot", "Zinc Ingots", 0, 0,
        [task("zingot", "create:zinc_ingot", Long(16))],
        [r_item("zingot", "minecraft:coal", 16)],
        ["Smelt that zinc ore. You need a lot."], shape="square")
b = c.q("mixer", "Mechanical Mixer", -2, 2,
        [task("mixer", "create:mechanical_mixer", Long(1)),
         task("basin", "create:basin", Long(1))],
        [r_item("mixer", "create:andesite_alloy", 8)],
        ["Mixer over basin is Create's crafting bench. Almost everything alloys here."], [a])
d = c.q("burner", "Blaze Burner", 2, 2,
        [task("burner", "create:blaze_burner", Long(1))],
        [r_item("burner", "minecraft:blaze_rod", 2)],
        ["Heat under a basin unlocks the recipes that need it.",
         "Feed it — a starving burner does nothing."], [a])
e = c.q("brass", "Brass", 0, 4,
        [task("brass", "create:brass_ingot", Long(16))],
        [r_item("brass", "create:brass_ingot", 8), r_xp("brass", 20)],
        ["Copper and zinc in a heated basin. Brass is the second age of this pack."],
        [b, d], shape="hexagon")
f = c.q("brasscasing", "Brass Casing", -2, 6,
        [task("brasscasing", "create:brass_casing", Long(8))],
        [r_item("brasscasing", "create:brass_casing", 4)],
        ["The smarter machines all want brass casing."], [e])
g = c.q("deployer", "Deployer", 2, 6,
        [task("deployer", "create:deployer", Long(2))],
        [r_item("deployer", "create:andesite_alloy", 16)],
        ["A deployer uses an item the way a player would. This is how you automate crafting."], [e])
h = c.q("brassfunnel", "Brass Funnels", -3, 8,
        [task("brassfunnel", "create:brass_funnel", Long(4))],
        [r_item("brassfunnel", "create:brass_funnel", 4)],
        ["Brass funnels filter. Andesite ones do not."], [f])
i4 = c.q("mech", "Precision Mechanism", 0, 8,
         [task("mech", "create:precision_mechanism", Long(4))],
         [r_item("mech", "create:precision_mechanism", 2), r_xp("mech", 30)],
         ["The gate to everything advanced. Sequenced assembly — get it right in order.",
          "Golden sheets, cogwheels, iron nuggets, a lot of patience."], [f, g], shape="hexagon")
c.q("claim4", "Brass Works", 0, 10,
    [task("claim4", "create:brass_casing", Long(16))],
    [r_item("claim4", "aeroclaims:claim_block", 3), r_xp("claim4", 35)],
    ["Three more. Brass factories are big factories."], [i4], shape="diamond")

# ── 5. Automation ─────────────────────────────────────────────────────────────────────────────
c = Chapter("automation", "Automation", "create:mechanical_arm", 4,
            "Stop doing it yourself.")
chapters.append(c)
a = c.q("crafter", "Mechanical Crafters", 0, 0,
        [task("crafter", "create:mechanical_crafter", Long(5))],
        [r_item("crafter", "create:brass_casing", 4)],
        ["Automated crafting. Arrange them in a path and feed the path."], shape="square")
b = c.q("arm", "Mechanical Arm", -2, 2,
        [task("arm", "create:mechanical_arm", Long(1))],
        [r_item("arm", "create:brass_casing", 4), r_xp("arm", 20)],
        ["One arm replaces a dozen funnels. Set inputs and outputs with a wrench."], [a])
d = c.q("vault", "Item Vaults", 2, 2,
        [task("vault", "create:item_vault", Long(4))],
        [r_item("vault", "minecraft:iron_block", 2)],
        ["Bulk storage that connects to itself. Build it big."], [a])
e = c.q("tunnel", "Smart Tunnels", 0, 4,
        [task("tunnel", "create:brass_tunnel", Long(4))],
        [r_item("tunnel", "create:brass_tunnel", 4)],
        ["Brass tunnels split and sort a belt automatically."], [b, d])
f = c.q("packager", "Packagers", -2, 5,
        [task("packager", "create:packager", Long(2))],
        [r_item("packager", "create:brass_casing", 4)],
        ["Items become packages. Packages travel further than belts sensibly can."], [e])
g = c.q("gauge", "Factory Gauge", 2, 5,
        [task("gauge", "create:factory_gauge", Long(2))],
        [r_item("gauge", "create:precision_mechanism", 1)],
        ["Declare what you want made and how much. The factory works it out."], [e])
h = c.q("fluid", "Fluids", 0, 7,
        [task("pipe", "create:fluid_pipe", Long(8)),
         task("pump", "create:mechanical_pump", Long(1))],
        [r_item("fluid", "minecraft:bucket", 2)],
        ["Pipes need a pump to push. Pipes alone do nothing."], [f, g])
i5 = c.q("tank", "Fluid Tanks", -2, 8,
         [task("tank", "create:fluid_tank", Long(4))],
         [r_item("tank", "create:fluid_tank", 2)],
         ["Store what you pump."], [h])
c.q("clock", "Clockwork", 2, 8,
    [task("clock", "create:cuckoo_clock", Long(1))],
    [r_item("clock", "minecraft:gold_ingot", 8)],
    ["Not useful. Very good."], [h], optional=True)
c.q("claim5", "The Works", 0, 10,
    [task("claim5", "create:precision_mechanism", Long(8))],
    [r_item("claim5", "aeroclaims:claim_block", 3), r_xp("claim5", 40)],
    ["A real factory needs real borders."], [i5], shape="diamond")

# ── 6. Taking Flight ──────────────────────────────────────────────────────────────────────────
c = Chapter("flight", "Taking Flight", "create:mechanical_bearing", 5,
            "The part of the pack that gave it its name.")
chapters.append(c)
a = c.q("goggles", "Engineer's Goggles", 0, 0,
        [task("goggles", "create:goggles", Long(1)),
         task("wrench", "create:wrench", Long(1))],
        [r_item("goggles", "create:brass_ingot", 4)],
        ["Goggles show stress and contents. The wrench rotates and dismantles.",
         "If you have not made these yet, make them now."], shape="square")
b = c.q("pulley", "Rope Pulley", -2, 2,
        [task("pulley", "create:rope_pulley", Long(1))],
        [r_item("pulley", "create:andesite_alloy", 16)],
        ["Your first moving contraption. Straight up, straight down."], [a])
d = c.q("piston", "Mechanical Piston", 2, 2,
        [task("piston", "create:mechanical_piston", Long(1))],
        [r_item("piston", "create:andesite_alloy", 16)],
        ["Same idea, any direction, and it carries whatever is glued to it."], [a])
e = c.q("glue", "Super Glue", 0, 4,
        [task("glue", "create:super_glue", Long(1))],
        [r_item("glue", "create:super_glue", 1)],
        ["Glue decides what moves with a contraption and what stays behind.",
         "Get this wrong and you leave half your ship in the air."], [b, d])
f = c.q("bearing", "Mechanical Bearing", 0, 6,
        [task("bearing", "create:mechanical_bearing", Long(1))],
        [r_item("bearing", "create:brass_casing", 4), r_xp("bearing", 25)],
        ["Rotating contraptions. Windmills, and the first thing that feels like flight."],
        [e], shape="hexagon")
g = c.q("windmill", "Windmill", -3, 7,
        [task("windmill", "create:windmill_bearing", Long(1))],
        [r_item("windmill", "minecraft:string", 16)],
        ["Sails on a bearing, up high. Free power with no water needed."], [f])
h = c.q("steam", "Steam Engine", 3, 7,
        [task("boiler", "create:fluid_tank", Long(4)),
         task("engine", "create:steam_engine", Long(2))],
        [r_item("steam", "create:steam_engine", 2), r_xp("steam", 30)],
        ["Water, fire and a tank. The endgame power source — feed it properly and it is enormous."],
        [f])
i6 = c.q("contraption", "Something That Flies", 0, 9,
         [task("chassis", "create:linear_chassis", Long(4))],
         [r_item("contraption", "create:linear_chassis", 4), r_xp("contraption", 40)],
         ["Chassis hold a contraption together across distance.",
          "From here it is your problem what you build. Make it fly."], [g, h], shape="hexagon")
c.q("claim6", "Airspace", 0, 11,
    [task("claim6", "create:steam_engine", Long(4))],
    [r_item("claim6", "aeroclaims:claim_block", 4), r_xp("claim6", 60)],
    ["The last of the claim blocks. Build something worth defending."], [i6], shape="diamond")



def build_addons():
    """One chapter per Create addon, three quests each, from scripts/_questgen/addons.json.

    Tasks only ever ask for common materials the main line already taught you to make. Rewards are
    what introduce the mod. That inversion is what makes these unbreakable: no quest can be gated
    behind an addon recipe nobody verified, and a mod's own progression is left intact."""
    spec = json.load(open("scripts/_questgen/addons.json", encoding="utf-8"))
    gates = spec["gates"]
    grp = qid("group", "addons")
    titles = ["Meet {}", "{}, Continued", "{}: Kitted Out"]
    for order, a in enumerate(spec["addons"]):
        ch = Chapter("addon_" + a["key"], a["title"], a["icon"], 100 + order, a["blurb"])
        ch.group = grp
        prev = None
        for i, q in enumerate(a["quests"]):
            gate_item, gate_n, gate_name = gates[i % len(gates)]
            rw = [r_item(f'{a["key"]}{i}{k}', it, n) for k, (it, n) in enumerate(q["rewards"])]
            rw.append(r_xp(f'{a["key"]}{i}', 10 + i * 5))
            prev = ch.q(
                f'{a["key"]}_{i}', titles[i].format(a["title"]), 0, i * 2,
                [task(f'{a["key"]}{i}', gate_item, Long(gate_n))],
                rw,
                [q["desc"], "", f"Bring {gate_n} {gate_name}."],
                [prev] if prev else None,
                shape="square" if i == 0 else "")
        chapters.append(ch)
        ADDON_GROUP[0] = grp


ADDON_GROUP = [""]
build_addons()


def main():
    out = os.path.join(ROOT, "quests")
    os.makedirs(os.path.join(out, "chapters"), exist_ok=True)

    for ch in chapters:
        with open(os.path.join(out, "chapters", ch.key + ".snbt"), "w",
                  encoding="utf-8", newline="\n") as fh:
            fh.write(ch.render() + "\n")

    with open(os.path.join(out, "chapter_groups.snbt"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(snbt({"chapter_groups": []}) + "\n")

    with open(os.path.join(out, "data.snbt"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(snbt({
            "default_reward_team": False,
            "default_team_consume_items": False,
            "default_quest_shape": "circle",
            "default_quest_disable_jei": False,
            "emergency_items_cooldown": 300,
            "drop_loot_crates": False,
            "disable_gui": False,
            "grid_scale": Double(0.5),
            "pause_game": False,
            "lock_message": "",
        }) + "\n")

    total = sum(len(ch.quests) for ch in chapters)
    print(f"chapters: {len(chapters)}   quests: {total}")
    for ch in chapters:
        print(f"  {ch.key:14s} {len(ch.quests):3d} quests   {ch.title}")
    print(f"\nwritten to {out}")


if __name__ == "__main__":
    main()
