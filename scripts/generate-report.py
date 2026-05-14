"""
Coffees Aero SMP - Daily Report Generator
Usage: python scripts/generate-report.py
Outputs: reports/YYYY-MM-DD.pdf
"""

import os
from datetime import date
from fpdf import FPDF, XPos, YPos

ROOT    = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
REPORTS = os.path.join(ROOT, "reports")
os.makedirs(REPORTS, exist_ok=True)

TODAY = str(date.today())          # 2026-05-14
OUT   = os.path.join(REPORTS, f"{TODAY}.pdf")

# ── Content ───────────────────────────────────────────────────────────────────
SECTIONS = [
    ("Project", [
        ("Name",       "Coffees Aero SMP"),
        ("Repository", "https://github.com/CoffeeBench2/Aeronatic-Java-Modpack-project"),
        ("Branch",     "main"),
        ("MC Version", "1.21.1"),
        ("Loader",     "NeoForge 21.1.0"),
        ("Admin",      "MrCoffeeBench"),
    ]),
    ("Session Summary - " + TODAY, [
        ("1. Repo connection",
            "Initialised git in the working directory, added the GitHub remote, "
            "fetched and pulled the main branch. Checked out the main branch "
            "tracking origin/main."),
        ("2. Full project scaffold",
            "Created pack.toml (packwiz manifest), MODLIST.md, CHANGELOG.md, "
            "cf-manifest.json, server-files (start.bat, start.sh, "
            "server.properties), overrides/config for voicechat and claims, "
            "scripts/install-mods.bat + .sh, export scripts, and a full "
            "docs/ folder (INSTALLATION, SERVER_SETUP, MOD_GUIDE)."),
        ("3. Custom mod - CoffeesAeroSMP Core",
            "Built a NeoForge mod in src/AeroCore/ that replaces the main "
            "menu Singleplayer/Multiplayer buttons: Singleplayer is hidden "
            "for all players except the admin (MrCoffeeBench). The Multiplayer "
            "button is replaced with 'Join Coffees Aero SMP' and auto-connects "
            "to the configured server IP. An Admin Settings screen lets the "
            "admin update the IP live without repackaging the mod."),
        ("4. Mod list - 74 mods + 2 custom",
            "Core Create ecosystem (Aeronautics, Sable, Trackwork, Liftoff, "
            "TFMG, Numismatics, Steam 'n' Rails, Big Cannons, etc.), "
            "performance suite (Embeddium, Canary, Iris, FerriteCore, "
            "ModernFix), server tools (Sable Protect, Open Parties, Voice Chat), "
            "world gen (Terralith, Tectonic, Terratonic), villages (CTOV, "
            "Towns & Towers), structures (When Dungeons Arise, Structory, "
            "Explorify), adventure (Alex's Mobs, Alex's Caves, Apotheosis, "
            "YUNG's), dimensions (Aether, Twilight Forest), questing (FTB), "
            "storage (Sophisticated), immersion (Farmer's Delight, Carry On, "
            "Antique Atlas, Waystones, Xaero's)."),
        ("5. Dual-platform distribution",
            "Added CurseForge manifest (cf-manifest.json) and export scripts "
            "for both Modrinth (.mrpack) and CurseForge (.zip). Both use "
            "packwiz as the build pipeline."),
        ("6. Full rename - AeroSMP -> CoffeesAeroSMP",
            "Renamed every occurrence across 30 files: display names, mod IDs, "
            "Java package (com.aerosmp -> com.coffeesaerosmp), mixin JSON, "
            "gradle project name, config keys. Old package directory deleted."),
        ("7. MC version upgrade - 1.20.1 -> 1.21.1",
            "Upgraded pack.toml, cf-manifest, README, server files, AeroCore "
            "mod, and all version references. Confirmed core Aeronautics stack "
            "(Create 6.0, Aeronautics, Sable) available on 1.21.1 NeoForge. "
            "Oculus (no 1.21.1 build) replaced with Iris. Power Loader flagged "
            "as unconfirmed."),
        ("8. Aeronautics immersion mods",
            "Added Vista (TV/camera blocks for cockpit feeds), Vista Aeronautics "
            "Fix (rendering on moving ships), Aeronautics Camera Sync (smooth "
            "camera rotation with ship movement), Sable: Collision Damage "
            "(real impact damage on crash). All confirmed 1.21.1 NeoForge."),
        ("9. World & exploration overhaul",
            "Added Immersive Engineering, Tectonic + Terratonic (Terralith "
            "compatibility), CTOV (biome-specific villages per Terralith biome), "
            "Towns & Towers (watchtowers), When Dungeons Arise (85+ structures "
            "including hostile abandoned airships), Structory, Explorify. "
            "Also Create: Crafts & Additions + Create: New Age (electricity)."),
        ("10. Custom mod - Coffees Power Bridge",
            "Built a second NeoForge mod in src/PowerBridge/ with two blocks: "
            "FE Kinetic Importer (FE -> Create rotation, buffered, "
            "configurable rate) and Kinetic FE Exporter (Create rotation -> FE, "
            "pushes to adjacent blocks and IE wire networks). "
            "Full config (fePerRpmImport, fePerRpmExport, max RPM, buffers). "
            "IE wire compat via NeoForge EnergyStorage capability."),
        ("11. Block textures & asset files",
            "Generated 16x16 pixel art textures in Create's brass/copper/steel "
            "palette via Python/Pillow (generate-assets.py). Blue indicator on "
            "Importer, green on Exporter. Full JSON blockstates, block models, "
            "item models, and en_us lang file wired up."),
        ("12. Panorama",
            "Generated a 6-image 512x512 cubemap panorama (sky gradient + "
            "procedural clouds + airship silhouette). Removed per user request "
            "- user will provide a real in-game screenshot for the menu "
            "background. Resource pack structure kept in overrides/resourcepacks/ "
            "so the user can drop the image in later."),
    ]),
    ("What's Next", [
        ("Menu background",   "User will provide an in-game screenshot. Drop the 6 cropped "
                              "faces into overrides/resourcepacks/CoffeesAeroSMP-Core-RP/"
                              "assets/minecraft/textures/gui/title/background/"),
        ("packwiz setup",     "Install packwiz CLI, run scripts/install-mods.bat from the "
                              "project root to populate all 74 mods with correct hashes, "
                              "then run export scripts."),
        ("Build custom mods", "cd src/AeroCore && gradlew build  (CoffeesAeroSMP Core)\n"
                              "cd src/PowerBridge && gradlew build  (Power Bridge)\n"
                              "Copy JARs into overrides/mods/"),
        ("Server deploy",     "Use server-files/ scripts and properties. Remember "
                              "allow-flight=true is required for Aeronautics."),
        ("FTB Quests",        "Author Aeronautics-themed quest chapters in overrides/config/ftbquests/"),
    ]),
]

# ── PDF builder ───────────────────────────────────────────────────────────────
class Report(FPDF):
    BRAND  = (184, 148, 58)    # brass
    DARK   = (30, 30, 30)
    LIGHT  = (245, 245, 245)
    GREY   = (120, 120, 120)

    def header(self):
        self.set_fill_color(*self.BRAND)
        self.rect(0, 0, 210, 18, "F")
        self.set_text_color(255, 255, 255)
        self.set_font("Helvetica", "B", 13)
        self.set_xy(10, 4)
        self.cell(0, 10, "Coffees Aero SMP  |  Daily Dev Report",
                  new_x=XPos.RIGHT, new_y=YPos.TOP)
        self.set_font("Helvetica", "", 9)
        self.set_xy(0, 6)
        self.cell(200, 8, TODAY, align="R")
        self.ln(14)

    def footer(self):
        self.set_y(-12)
        self.set_text_color(*self.GREY)
        self.set_font("Helvetica", "", 8)
        self.cell(0, 8, f"Page {self.page_no()}  |  Coffees Aero SMP  |  {TODAY}", align="C")

    def section_title(self, title):
        self.ln(4)
        self.set_fill_color(*self.DARK)
        self.set_text_color(255, 255, 255)
        self.set_font("Helvetica", "B", 10)
        self.cell(0, 7, f"  {title}", fill=True,
                  new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(2)

    def kv_row(self, key, value):
        self.set_text_color(*self.DARK)
        # Key
        self.set_font("Helvetica", "B", 9)
        self.set_x(12)
        self.cell(52, 6, key, new_x=XPos.RIGHT, new_y=YPos.TOP)
        # Value
        self.set_font("Helvetica", "", 9)
        self.set_text_color(50, 50, 50)
        self.multi_cell(0, 6, value)
        self.ln(1)

pdf = Report(orientation="P", unit="mm", format="A4")
pdf.set_margins(10, 22, 10)
pdf.set_auto_page_break(auto=True, margin=15)
pdf.add_page()

# Title block
pdf.set_font("Helvetica", "B", 20)
pdf.set_text_color(*Report.BRAND)
pdf.cell(0, 12, "Coffees Aero SMP", align="C",
         new_x=XPos.LMARGIN, new_y=YPos.NEXT)
pdf.set_font("Helvetica", "", 11)
pdf.set_text_color(80, 80, 80)
pdf.cell(0, 7, "Development Session Report", align="C",
         new_x=XPos.LMARGIN, new_y=YPos.NEXT)
pdf.cell(0, 7, TODAY, align="C",
         new_x=XPos.LMARGIN, new_y=YPos.NEXT)
pdf.ln(6)

for section_name, items in SECTIONS:
    pdf.section_title(section_name)
    for key, value in items:
        pdf.kv_row(key, value)

pdf.output(OUT)
print(f"Report saved: {OUT}")
