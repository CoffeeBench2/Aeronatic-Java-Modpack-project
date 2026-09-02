// Aero Toolgun Lockdown — recipe removal
//
// Goes to <server>/kubejs/server_scripts/. Apply with `/kubejs reload server_scripts`
// — no restart.
//
// WHY THIS EXISTS AS WELL AS THE DATAPACK:
//   The datapack (server-assets/datapacks/aero-toolgun-lockdown) confiscates the
//   items. It cannot remove the RECIPES — a vanilla datapack has no true recipe
//   delete, only override-with-something-impossible, which leaves the recipe
//   visible in EMI/JEI and players discovering by trial that it no longer works.
//   KubeJS removes them properly, so they vanish from the recipe viewer too.
//
//   Recipes here, possession there. Both are needed:
//     - recipes only  → creative-given or /give items still work
//     - possession only → players keep crafting them and losing the materials
//
// CONTEXT: the portable-structure capture/place cycle mints a NEW ship UUID each
// time, orphaning the AeroClaims claim and leaving ghost ships that the owner can
// no longer claim. That is the behaviour this locks down.

ServerEvents.recipes(event => {
    // Recipes the mod itself defines. In 1.21.1 that is exactly three:
    //   magnetic_gun, portable_structure_container, survival_structure_tool
    // (creative_magnetic_gun, structure_tool and disposable_vehicle_container have
    //  no recipe at all — they are creative-only or produced by the toolgun.)
    event.remove({ mod: 'create_aeronautics_toolgun' })

    // Belt and braces: anything from ANY mod that outputs a toolgun item. Catches
    // a compat recipe added by another addon, which the filter above would miss
    // because it matches on the recipe's own namespace, not its output.
    event.remove({ output: /^create_aeronautics_toolgun:/ })
})
