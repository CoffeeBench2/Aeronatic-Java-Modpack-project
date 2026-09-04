package com.coffeesaerosmp.core.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Detects when the pack needs a newer NeoForge than the one running, and works out how to change it.
 *
 * <p>🔑 <b>The updater cannot install a loader by moving files into the game directory.</b> NeoForge
 * is not a file in {@code mods/}; it is a launcher concept. What the launcher actually reads is a
 * version string in its own instance metadata, which lives OUTSIDE the game directory. So the only
 * honest way to "update NeoForge from in-game" is to edit that metadata and let the launcher do the
 * install on its next start. That is what this class plans and {@link LoaderApplier} performs.
 *
 * <p>Two families of launcher, and they need different treatment:
 * <ul>
 *   <li><b>Resolving launchers</b> (Prism/MultiMC): the metadata names a NeoForge <i>component</i>
 *       and the launcher downloads whatever version you name from its own meta service. Patching
 *       the version string is genuinely all that is required.</li>
 *   <li><b>Installing launchers</b> (CurseForge, vanilla): the metadata points at a loader that has
 *       to already exist under a shared install root. Naming a version that was never installed
 *       leaves an instance that cannot launch, so these ALSO need NeoForge's official installer run
 *       headlessly ({@code --installClient}) against that root first.</li>
 * </ul>
 *
 * <p>Modrinth App keeps its profiles in a live SQLite database rather than a file. Writing into a
 * launcher's open database from outside is not a risk worth taking for a convenience feature, so it
 * is detected and reported, not edited — the player is told the two clicks to make instead.
 *
 * <p>Lives in {@code core/update/}, which the {@code -PnoUpdater} CurseForge build strips. That is
 * deliberate: the CurseForge app installs the loader itself when the modpack updates, so this would
 * be both redundant and exactly the kind of outside-the-pack behaviour CF policy objects to.
 */
public final class LoaderUpdater {

    public enum Launcher {
        PRISM("Prism / MultiMC", false),
        CURSEFORGE("CurseForge", true),
        VANILLA("Minecraft Launcher", true),
        MODRINTH("Modrinth App", false),
        UNKNOWN("your launcher", false);

        public final String label;
        /** True when the launcher will NOT fetch the loader itself and the installer must be run. */
        public final boolean needsInstaller;

        Launcher(String label, boolean needsInstaller) { this.label = label; this.needsInstaller = needsInstaller; }
    }

    /** What we found, and what we would do about it. {@code metaFile} is null when we cannot act. */
    public record Plan(Launcher launcher, Path metaFile, Path installRoot,
                       String from, String to, boolean actionable, String note) {}

    private LoaderUpdater() {}

    /**
     * The NeoForge version actually running, read from the mod list rather than a system property —
     * the property name has moved between FML generations, the mod entry has not.
     */
    public static String running() {
        try {
            return net.neoforged.fml.ModList.get()
                .getModContainerById("neoforge")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    /** True when {@code target} is a strictly newer NeoForge version than {@code current}. */
    public static boolean isNewer(String current, String target) {
        if (current == null || target == null || current.isBlank() || target.isBlank()) return false;
        String[] a = current.split("[.-]"), b = target.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = num(i < a.length ? a[i] : "0"), y = num(i < b.length ? b[i] : "0");
            if (x != y) return y > x;
        }
        return false;
    }

    private static int num(String s) {
        StringBuilder d = new StringBuilder();
        for (char c : s.toCharArray()) if (Character.isDigit(c)) d.append(c);
        return d.isEmpty() ? 0 : Integer.parseInt(d.toString());
    }

    /**
     * Identifies the launcher from files around the game directory.
     *
     * <p>Order matters: CurseForge instances also contain a {@code .minecraft}-shaped tree, so the
     * distinctive per-instance file is checked before any generic fallback.
     */
    public static Plan detect(Path gameDir, String target) {
        String from = running();

        // CurseForge: <instance>/minecraftinstance.json carries baseModLoader.
        //
        // 🔴 DELIBERATELY NOT PATCHED. baseModLoader is not just a version string: it embeds a
        // `versionJson` blob (~21 KB) holding the COMPLETE library manifest for that exact NeoForge
        // build — every library name, URL, sha1 and size, plus its own `id: neoforge-<version>`.
        // Rewriting the three obvious fields and leaving that manifest behind produces an instance
        // whose declared version and actual libraries disagree, which is worse than doing nothing.
        // Verified against a real instance on 2026-09-04: the three fields updated cleanly and every
        // reference inside versionJson still pointed at the old build.
        //
        // Transplanting a freshly generated manifest is possible (the installer writes one) but
        // CurseForge uses its own flavour of the schema, and getting it subtly wrong leaves a player
        // unable to launch at all. Not a trade worth making for a convenience feature — the CF app
        // changes the loader properly in three clicks.
        Path cf = gameDir.resolve("minecraftinstance.json");
        if (Files.isRegularFile(cf)) {
            return new Plan(Launcher.CURSEFORGE, null, null, from, target, false,
                "In the CurseForge app: click the instance, then Settings (cog) > Game Version / "
                + "Modloader, and pick NeoForge " + target + ".");
        }

        // Prism / MultiMC: the instance root is the PARENT of .minecraft, and holds mmc-pack.json.
        Path prism = gameDir.getParent() == null ? null : gameDir.getParent().resolve("mmc-pack.json");
        if (prism != null && Files.isRegularFile(prism)) {
            return new Plan(Launcher.PRISM, prism, null, from, target, true, "");
        }

        // Modrinth App: profiles live in a SQLite database we will not write to.
        Path mr = gameDir.getParent() == null ? null : gameDir.getParent().getParent();
        if (mr != null && Files.isDirectory(mr.resolve("caches"))
                && Files.exists(mr.resolve("app.db"))) {
            return new Plan(Launcher.MODRINTH, null, null, from, target, false,
                "Modrinth App stores profiles in a database. Change the loader version in the app: "
                + "Profile > Options > Loader version.");
        }

        // Vanilla launcher: launcher_profiles.json next to versions/ and libraries/.
        if (Files.isRegularFile(gameDir.resolve("launcher_profiles.json"))) {
            return new Plan(Launcher.VANILLA, gameDir.resolve("launcher_profiles.json"), gameDir,
                from, target, true, "");
        }

        return new Plan(Launcher.UNKNOWN, null, null, from, target, false,
            "could not identify the launcher; set NeoForge " + target + " by hand");
    }

    /**
     * Writes the request and hands off to {@link LoaderApplier}, which waits for this process to
     * exit before touching anything. Editing launcher metadata while the launcher may still hold it
     * open is the one thing guaranteed to corrupt an instance.
     */
    public static boolean stage(Path gameDir, Plan plan) {
        if (!plan.actionable() || plan.metaFile() == null) return false;
        try {
            Path work = gameDir.resolve(".aero-update");
            Files.createDirectories(work);
            Path req = work.resolve("loader.txt");
            Files.write(req, java.util.List.of(
                plan.launcher().name(),
                plan.metaFile().toString(),
                plan.installRoot() == null ? "" : plan.installRoot().toString(),
                plan.from(),
                plan.to()), java.nio.charset.StandardCharsets.UTF_8);

            Path self = InClientUpdaterAccess.selfJar(gameDir);
            Path copy = work.resolve("loaderapplier.jar");
            Files.copy(self, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            ProcessBuilder pb = new ProcessBuilder(javaw(), "-cp", copy.toString(),
                "com.coffeesaerosmp.core.update.LoaderApplier",
                String.valueOf(ProcessHandle.current().pid()), gameDir.toString(), req.toString());
            pb.directory(gameDir.toFile());
            pb.redirectOutput(work.resolve("loader.log").toFile());
            pb.redirectError(work.resolve("loader.log").toFile());
            pb.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String javaw() {
        String home = System.getProperty("java.home", "");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path p = Path.of(home, "bin", win ? "javaw.exe" : "java");
        return Files.exists(p) ? p.toString() : (win ? "javaw.exe" : "java");
    }

    /** Reuses the updater's own jar-locating logic so there is one implementation of that trap. */
    static final class InClientUpdaterAccess {
        static Path selfJar(Path gameDir) throws java.io.IOException {
            try {
                var loc = LoaderUpdater.class.getProtectionDomain().getCodeSource().getLocation();
                if (loc != null) {
                    Path p = Path.of(loc.toURI());
                    if (Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return p;
                }
            } catch (Exception ignored) {}
            Path mods = gameDir.resolve("mods");
            if (Files.isDirectory(mods)) {
                try (var s = Files.list(mods)) {
                    var hit = s.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.startsWith("coffeesaerocore") && n.endsWith(".jar");
                    }).findFirst();
                    if (hit.isPresent()) return hit.get();
                }
            }
            throw new java.io.IOException("could not locate CoffeesAeroCore jar");
        }
    }
}
