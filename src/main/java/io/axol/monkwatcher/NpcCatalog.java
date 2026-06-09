package io.axol.monkwatcher;

import java.util.Set;

public final class NpcCatalog {
    // Verify each ID against the live RuneLite cache before shipping.
    // The chisel.weirdgloop.org NPC lookup is the easiest source of truth.
    // Wrong IDs fail silently -- kill counter stays at zero with no error.
    private static final Set<Integer> MONK_IDS = Set.of(
        4127  // Monk (Edgeville Monastery) -- verify
        // Add Ardougne Monastery monks if training there too.
        // Do NOT include Monks of Zamorak -- different NPC family.
    );

    private NpcCatalog() {}

    public static boolean isMonk(int id) {
        return MONK_IDS.contains(id);
    }
}
