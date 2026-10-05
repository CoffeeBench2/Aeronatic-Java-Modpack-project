#!/usr/bin/env python3
r"""
Season 3 self-updating channel: the S3 Modrinth App profile -> a packwiz pack under s3/.

    py scripts/build_s3_channel.py --version 3.0.0 [--no-upload]

S3 instances poll  main/s3/version.json  and install from  main/s3/pack.toml  (see
scripts/s3-overrides/config/coffeesaerosmp_core-client.toml). The ROOT version.json / pack.toml are
Season 2 and every deployed S2 Core reads them, so nothing Season 3 may ever land there -- root
.packwizignore excludes s3/ and this script refuses to run if it doesn't.

  * Files Modrinth hosts byte-for-byte -> Modrinth metafiles (players download from Modrinth's CDN).
  * Everything else (FTB, aerowarptics, the replay mod, our FULL Core) -> uploaded to the GitHub
    release  s3-v<version>, created as a PRE-RELEASE. A normal release would move /releases/latest,
    which the S2 version.json still points at.
  * config/, defaultconfigs/, options.txt -> s3/overrides/, then the S3 Core config on top.

The filename in each metafile is the PROFILE's filename, so an instance created from the profile
(or the S3 mrpack) already matches and the first update is a no-op instead of a re-download.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROFILE = r"C:\Users\disan\AppData\Roaming\ModrinthApp\profiles\Coffees AeroSmp Season 3 v1.0-FULL"
CORE_JAR_DIR = os.path.join(ROOT, "src", "AeroCore", "build", "libs")
S3 = os.path.join(ROOT, "s3")
S3_OVERRIDES_SRC = os.path.join(ROOT, "scripts", "s3-overrides")
PACKWIZ = r"C:\tools\packwiz\packwiz"
REPO = "CoffeeBench2/Aeronatic-Java-Modpack-project"
MODRINTH_PAGE = "https://modrinth.com/modpack/coffees-create-aeronautics-smp"
MC, NEOFORGE = "1.21.1", "21.1.251"
UA = {"User-Agent": "CoffeeBench2/Aeronatic-Java-Modpack-project s3-channel", "Content-Type": "application/json"}

CONTENT_DIRS = ["mods", "resourcepacks", "shaderpacks"]
OVERRIDE_DIRS = ["config", "defaultconfigs"]
OVERRIDE_FILES = ["options.txt"]
# Per-install generated password / orphan of a mod not in the pack. Never ship either.
OVERRIDE_EXCLUDE = {"config/resourceful-config-web.json", "config/ias.json"}
# Profile filenames that make bad release-asset names (gh rewrites spaces). Shipped under the new name.
RENAME = {"reforgedplaymod-1.21.1-0.3 (1).jar": "reforgedplaymod-1.21.1-0.3.jar"}
# These six methods are what UpdaterBridge reflects on; a -cf (no updater) Core has none of them.
UPDATER_CLASS = "com/coffeesaerosmp/core/version/VersionCheck.class"


def sha(path, algo):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", os.path.splitext(name)[0].lower()).strip("-")


def fail(msg):
    sys.exit("ERROR: " + msg)


def guards_before(core):
    ign = open(os.path.join(ROOT, ".packwizignore"), encoding="utf-8").read()
    if not re.search(r"(?m)^s3/\s*$", ign):
        fail("root .packwizignore does not exclude s3/ -- S2 clients would install Season 3")
    cfg = open(os.path.join(S3_OVERRIDES_SRC, "config", "coffeesaerosmp_core-client.toml"), encoding="utf-8").read()
    for key in ("versionCheckUrl", "packTomlUrl"):
        m = re.search(r'(?m)^%s\s*=\s*"([^"]*)"' % key, cfg)
        if not m or "/main/s3/" not in m.group(1):
            fail("S3 Core config %s must point at main/s3/ (got %r)" % (key, m.group(1) if m else None))
    if not re.search(r"(?m)^manualUpdateOnly\s*=\s*false", cfg):
        fail("S3 Core config must have manualUpdateOnly = false")
    if UPDATER_CLASS not in zipfile.ZipFile(core).namelist():
        fail("%s has no updater (a -cf build?) -- the S3 channel needs the FULL Core" % os.path.basename(core))
    if open(os.path.join(PROFILE, "options.txt"), "rb").read().startswith(b"\xef\xbb\xbf"):
        fail("options.txt has a UTF-8 BOM (memory bom-breaks-shipped-configs)")
    iris = open(os.path.join(PROFILE, "config", "iris.properties"), encoding="utf-8").read()
    if not re.search(r"(?m)^enableShaders=false\s*$", iris):
        fail("config/iris.properties must have enableShaders=false")


def find_core(core_version):
    p = os.path.join(CORE_JAR_DIR, "CoffeesAeroCore-%s.jar" % core_version)
    if not os.path.isfile(p):
        fail("full Core not built: %s (gradlew.bat jar, WITHOUT -PnoUpdater)" % p)
    return p


def profile_files(core):
    out = []  # (dir, shipped_name, path)
    for d in CONTENT_DIRS:
        for n in sorted(os.listdir(os.path.join(PROFILE, d))):
            p = os.path.join(PROFILE, d, n)
            if not os.path.isfile(p) or n.endswith((".txt", ".disabled")):
                continue
            if d == "mods" and n.startswith("CoffeesAeroCore-"):
                continue  # the channel's Core is the one built from source, never a stray profile copy
            out.append((d, RENAME.get(n, n), p))
    out.append(("mods", os.path.basename(core), core))
    return out


def modrinth_lookup(files):
    by_sha1 = {sha(p, "sha1"): (d, n, p) for d, n, p in files}
    rq = urllib.request.Request("https://api.modrinth.com/v2/version_files", headers=UA,
                                data=json.dumps({"hashes": list(by_sha1), "algorithm": "sha1"}).encode())
    found = json.load(urllib.request.urlopen(rq, timeout=60))
    return by_sha1, found


_GH_ENV = None


def gh(*args, check=True):
    # Always as the repo owner. The ACTIVE gh account on this PC has been the purged personal one
    # (memory gh-active-account-may-be-personal), and switching it could break something else.
    global _GH_ENV
    if _GH_ENV is None:
        tok = subprocess.run(["gh", "auth", "token", "-u", "CoffeeBench2"], capture_output=True, text=True,
                             check=True).stdout.strip()
        _GH_ENV = dict(os.environ, GH_TOKEN=tok)
    return subprocess.run(["gh", *args], capture_output=True, text=True, check=check, env=_GH_ENV)


def ensure_release(tag, version):
    if gh("release", "view", tag, "--repo", REPO, check=False).returncode == 0:
        return
    gh("release", "create", tag, "--repo", REPO, "--prerelease", "--title",
       "Season 3 channel files %s" % version, "--notes",
       "Self-hosted files for the Season 3 in-client updater (s3/ on main). Pre-release ON PURPOSE: "
       "a normal release would move /releases/latest, which Season 2's version.json points at.")


def url_ok(url):
    try:
        rq = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA["User-Agent"]})
        return urllib.request.urlopen(rq, timeout=30).status == 200
    except Exception:
        return False


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="3.0.0")
    ap.add_argument("--core", default="1.3.54", help="full Core version in src/AeroCore/build/libs")
    ap.add_argument("--notes", default="Season 3.")
    ap.add_argument("--no-upload", action="store_true", help="skip GitHub release uploads (dry layout)")
    a = ap.parse_args()
    tag = "s3-v" + a.version

    core = find_core(a.core)
    guards_before(core)
    files = profile_files(core)
    by_sha1, found = modrinth_lookup(files)

    # Fresh tree every run: a metafile left over from a removed mod would keep installing it.
    for sub in CONTENT_DIRS + ["overrides"]:
        shutil.rmtree(os.path.join(S3, sub), ignore_errors=True)

    hosted = []
    for h, (d, n, p) in by_sha1.items():
        ver = found.get(h)
        if ver:
            fo = next(f for f in ver["files"] if f["hashes"]["sha1"] == h)
            write(os.path.join(S3, d, slug(n) + ".pw.toml"),
                  'name = "%s"\nfilename = "%s"\nside = "both"\n\n[download]\nurl = "%s"\n'
                  'hash-format = "sha512"\nhash = "%s"\n\n[update]\n[update.modrinth]\n'
                  'mod-id = "%s"\nversion = "%s"\n'
                  % (os.path.splitext(n)[0], n, fo["url"], fo["hashes"]["sha512"], ver["project_id"], ver["id"]))
        else:
            hosted.append((d, n, p))

    if not a.no_upload:
        ensure_release(tag, a.version)
    for d, n, p in hosted:
        if not a.no_upload:
            staged = p
            if os.path.basename(p) != n:  # upload under the shipped name
                staged = os.path.join(os.environ.get("TEMP", ROOT), n)
                shutil.copyfile(p, staged)
            gh("release", "upload", tag, staged, "--repo", REPO, "--clobber")
        url = "https://github.com/%s/releases/download/%s/%s" % (REPO, tag, n)
        side = "client" if n.startswith("CoffeesAeroCore-") else "both"
        write(os.path.join(S3, d, slug(n) + ".pw.toml"),
              'name = "%s"\nfilename = "%s"\nside = "%s"\n\n[download]\nurl = "%s"\n'
              'hash-format = "sha256"\nhash = "%s"\n' % (os.path.splitext(n)[0], n, side, url, sha(p, "sha256")))
        if not a.no_upload and not url_ok(url):
            fail("release asset does not answer 200: " + url)

    # Overrides = ONLY what we deliberately manage (scripts/s3-overrides/). NOT the profile's 300+ configs:
    # mods rewrite their own configs at runtime, so every one of those would hash-mismatch on every
    # update and be re-fetched, cache-busted, from GitHub origin -- the 429 silent-failure of 2026-08-17.
    # The full profile config ships ONCE, in the Modrinth mrpack (build_s3_mrpack.py). To manage a
    # config through the channel later, add it to scripts/s3-overrides/.
    ov = os.path.join(S3, "overrides")
    for dp, _, fns in os.walk(S3_OVERRIDES_SRC):
        for fn in fns:
            src = os.path.join(dp, fn)
            dst = os.path.join(ov, os.path.relpath(src, S3_OVERRIDES_SRC))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
    cfg_path = os.path.join(ov, "config", "coffeesaerosmp_core-client.toml")
    cfg = open(cfg_path, encoding="utf-8").read()
    cfg = re.sub(r'(?m)^packVersion\s*=\s*"[^"]*"', 'packVersion = "%s"' % a.version, cfg)
    write(cfg_path, cfg)

    write(os.path.join(S3, "pack.toml"),
          'name = "Coffees Aero SMP Season 3"\nversion = "%s"\npack-format = "packwiz:1.1.0"\n\n'
          '[index]\nfile = "index.toml"\nhash-format = "sha256"\nhash = ""\n\n'
          '[versions]\nminecraft = "%s"\nneoforge = "%s"\n' % (a.version, MC, NEOFORGE))
    write(os.path.join(S3, ".packwizignore"), "# Everything under s3/ ships, except packwiz's own files.\nversion.json\n")
    write(os.path.join(S3, "version.json"), json.dumps({
        "version": a.version, "url": MODRINTH_PAGE, "neoforge": NEOFORGE, "minecraft": MC, "notes": a.notes,
    }, indent=2) + "\n")
    idx_path = os.path.join(S3, "index.toml")
    if not os.path.exists(idx_path):  # packwiz refuses to refresh a pack whose index file is missing
        write(idx_path, 'hash-format = "sha256"\n')
    r = subprocess.run([PACKWIZ, "refresh"], cwd=S3, capture_output=True, text=True)
    if r.returncode != 0:
        fail("packwiz refresh failed:\n" + r.stdout + r.stderr)

    # Guards after: hash chain intact and every file accounted for.
    pack = open(os.path.join(S3, "pack.toml"), encoding="utf-8").read()
    want = re.search(r'(?m)^hash\s*=\s*"([0-9a-f]+)"', pack.split("[index]")[1]).group(1)
    if want != sha(os.path.join(S3, "index.toml"), "sha256"):
        fail("s3/pack.toml [index] hash != sha256(s3/index.toml)")
    idx = open(os.path.join(S3, "index.toml"), encoding="utf-8").read()
    metas = len(re.findall(r"(?m)^metafile = true", idx))
    if metas != len(files):
        fail("index has %d metafiles, expected %d" % (metas, len(files)))
    if "overrides/config/coffeesaerosmp_core-client.toml" not in idx:
        fail("S3 Core config missing from the index")
    print("s3/ channel %s: %d Modrinth + %d release-hosted (%s), %d override files"
          % (a.version, len(files) - len(hosted), len(hosted), tag,
             len(re.findall(r"(?m)^\[\[files\]\]", idx)) - metas))
    for d, n, _ in sorted(hosted):
        print("  hosted:", d, n)


if __name__ == "__main__":
    main()
