package io.axol.monkwatcher;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("monkwatcher")
public interface BridgeConfig extends Config {
    @ConfigItem(
        keyName = "socketPath",
        name = "Socket path",
        description = "Unix domain socket Raxol reads from"
    )
    default String socketPath() {
        // macOS does not honour XDG_RUNTIME_DIR; fall back to /tmp. See ADR-0002.
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        String base = (runtime != null && !runtime.isEmpty()) ? runtime : "/tmp";
        return base + "/raxol-monkwatcher.sock";
    }
}
