"""CameraOverhaul v2.0.4 declares a wrong mandatory bound `cloth_config >=16.0.0`
(cloth 16.x only exists on 1.21.2+; 1.21.1 ships cloth 15.x). The mod runs fine on
cloth 15.x (the Fresh and Smooth 1.21.1 pack ships this exact jar with cloth 15.0.140);
cloth is only used for its config screen, not the camera effects. Relax the bound to
>=15.0.0 so NeoForge loads it, repackage to overrides/mods.
"""
import zipfile, os, shutil

SRC = os.path.expanduser(r"~\Downloads\CameraOverhaul-v2.0.4-neoforge+mc[1.21.0-1.21.1].jar")
DST = r"D:\MC Project\untitled\overrides\mods\CameraOverhaul-v2.0.4-neoforge-clothfix.jar"
TOML = "META-INF/neoforge.mods.toml"

if os.path.exists(DST):
    os.remove(DST)

zin = zipfile.ZipFile(SRC, "r")
toml = zin.read(TOML).decode("utf-8")
assert ">=16.0.0" in toml, "expected cloth bound not found"
patched = toml.replace(">=16.0.0", ">=15.0.0")

zout = zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    data = patched.encode("utf-8") if item.filename == TOML else zin.read(item.filename)
    zout.writestr(item, data)
zout.close()
zin.close()

print(f"patched cloth bound 16.0.0 -> 15.0.0")
print(f"output: {DST}")
