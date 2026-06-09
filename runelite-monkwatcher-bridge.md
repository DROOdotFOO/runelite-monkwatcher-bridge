# runelite-monkwatcher-bridge

> RuneLite plugin that streams local player state to a Unix domain socket for consumption by `raxol_monkwatcher`. Personal use only — informational read-only plugin, no input automation.

## Status

Personal tool. Side-loaded via RuneLite's `--developer-mode`. Not submitted to plugin-hub.

## Scope (and the line we don't cross)

The plugin **reads** game state from the RuneLite client API and writes JSON to a local socket. It **never** sends input back into the game, never injects actions, never modifies client behaviour. This stays on the legal side of Jagex's rules — RuneLite explicitly permits informational plugins of this shape.

If anyone (you, future-you, a friend) ever wants to add "auto-click monk" — don't. It's a different category of tool with a different risk profile and the defense pure dies.

## Repo layout

```
runelite-monkwatcher-bridge/
├── build.gradle.kts
├── settings.gradle.kts
├── runelite-plugin.properties
├── src/main/java/io/axol/monkwatcher/
│   ├── BridgePlugin.java
│   ├── BridgeConfig.java
│   ├── BridgeSocketServer.java
│   ├── TickPayload.java
│   ├── EventPayload.java
│   └── NpcCatalog.java
├── src/test/java/io/axol/monkwatcher/
│   └── NpcCatalogTest.java
└── README.md
```

## Build

Target Java 17 (RuneLite supports it; UDS NIO bits need Java 16+).

Fork from `runelite/example-plugin` for the gradle skeleton. The `runelite-client` dependency is provided at runtime by the RuneLite jar — declare it `compileOnly`.

`./gradlew shadowJar` produces a fat jar. Drop in `~/.runelite/sideloaded-plugins/` and launch RuneLite with `--developer-mode`.

## Wire protocol

Newline-delimited JSON over a Unix domain socket at:

- Linux: `$XDG_RUNTIME_DIR/raxol-monkwatcher.sock`
- macOS: `/tmp/raxol-monkwatcher.sock` (macOS doesn't honour XDG by default)

Two payload shapes:

### Tick payload (one per game tick, 600ms)

```json
{
  "t": 1701234567890,
  "tick": 8421337,
  "anim": 422,
  "poseAnim": 808,
  "mouseIdleTicks": 12,
  "kbIdleTicks": 18,
  "npc": 4127,
  "isMonk": true,
  "x": 3057,
  "y": 3484,
  "plane": 0,
  "hp": 67,
  "maxHp": 85,
  "prayer": 31,
  "maxPrayer": 82,
  "runEnergy": 84
}
```

Fields are short on purpose — this goes over the wire every 600ms.

### Event payload (discrete game events)

```json
{ "event": "monk_killed", "data": { "id": 4127, "name": "Monk" } }
{ "event": "player_death", "data": null }
{ "event": "game_state", "data": { "state": "LOGGED_OUT" } }
{ "event": "level_up", "data": { "skill": "DEFENCE", "level": 60 } }
```

## File: `BridgePlugin.java`

The entry point. Subscribes to RuneLite events, builds payloads, hands off to the socket server.

```java
package io.axol.monkwatcher;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import java.util.Map;

@PluginDescriptor(
    name = "Raxol Monk Watcher Bridge",
    description = "Streams local player state to Raxol over a Unix socket",
    tags = {"raxol", "personal", "idle"}
)
public class BridgePlugin extends Plugin {
    @Inject private Client client;
    @Inject private BridgeConfig config;
    @Inject private BridgeSocketServer server;

    @Override
    protected void startUp() throws Exception {
        server.start(config.socketPath());
    }

    @Override
    protected void shutDown() {
        server.stop();
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        Player me = client.getLocalPlayer();
        if (me == null) return;

        TickPayload p = new TickPayload();
        p.t = System.currentTimeMillis();
        p.tick = client.getTickCount();
        p.anim = me.getAnimation();
        p.poseAnim = me.getPoseAnimation();
        p.mouseIdleTicks = client.getMouseIdleTicks();
        p.kbIdleTicks = client.getKeyboardIdleTicks();

        Actor target = me.getInteracting();
        if (target instanceof NPC) {
            NPC npc = (NPC) target;
            p.npc = npc.getId();
            p.isMonk = NpcCatalog.isMonk(npc.getId());
        }

        WorldPoint wp = me.getWorldLocation();
        p.x = wp.getX();
        p.y = wp.getY();
        p.plane = wp.getPlane();

        p.hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        p.maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
        p.prayer = client.getBoostedSkillLevel(Skill.PRAYER);
        p.maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
        p.runEnergy = client.getEnergy() / 100;

        server.send(p);
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        if (event.getActor() == client.getLocalPlayer()) {
            server.sendEvent("player_death", null);
            return;
        }
        if (event.getActor() instanceof NPC) {
            NPC npc = (NPC) event.getActor();
            if (NpcCatalog.isMonk(npc.getId())) {
                server.sendEvent("monk_killed", Map.of(
                    "id", npc.getId(),
                    "name", npc.getName() == null ? "Monk" : npc.getName()
                ));
            }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        server.sendEvent("game_state", Map.of(
            "state", event.getGameState().name()
        ));
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        // Optional — fires every XP drop. Filter to actual level-ups.
        // (Implementation: track previous level per skill, compare.)
    }

    @Provides
    BridgeConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(BridgeConfig.class);
    }
}
```

### Notes

- `getMouseIdleTicks()` and `getKeyboardIdleTicks()` are the ticks since input *to the client window*. This is what Jagex's 5-minute idle logout counts. Verify the exact method names against the RuneLite API version you build against — these have shifted between releases.
- `ActorDeath` fires on the death animation. The NPC reference is still valid in the callback; if you wait for despawn it's already gone.
- `getName()` returns `null` for some NPC sub-IDs — defensive `?:` default.
- Don't filter monk kills in the plugin beyond `isMonk` — let Raxol decide what counts.

## File: `BridgeConfig.java`

```java
package io.axol.monkwatcher;

import net.runelite.client.config.*;

@ConfigGroup("monkwatcher")
public interface BridgeConfig extends Config {
    @ConfigItem(
        keyName = "socketPath",
        name = "Socket path",
        description = "Unix domain socket Raxol reads from"
    )
    default String socketPath() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        String base = (runtime != null && !runtime.isEmpty()) ? runtime : "/tmp";
        return base + "/raxol-monkwatcher.sock";
    }
}
```

## File: `BridgeSocketServer.java`

```java
package io.axol.monkwatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Singleton
public class BridgeSocketServer {
    private static final int QUEUE_CAPACITY = 256;
    private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ObjectMapper mapper = new ObjectMapper();
    private ServerSocketChannel serverChannel;
    private Thread acceptThread;
    private volatile boolean running;

    public synchronized void start(String path) throws IOException {
        if (running) return;
        Path sockPath = Path.of(path);
        Files.deleteIfExists(sockPath);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(sockPath));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "monkwatcher-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverChannel != null) serverChannel.close(); }
        catch (IOException ignored) {}
        if (acceptThread != null) acceptThread.interrupt();
    }

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel ch = serverChannel.accept();
                outbox.clear(); // fresh state for fresh consumer
                Thread w = new Thread(() -> writeLoop(ch), "monkwatcher-write");
                w.setDaemon(true);
                w.start();
            } catch (IOException e) {
                if (running) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void writeLoop(SocketChannel ch) {
        try (var writer = Channels.newWriter(ch, StandardCharsets.UTF_8)) {
            while (running && ch.isOpen()) {
                String line = outbox.poll(1, TimeUnit.SECONDS);
                if (line == null) continue;
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        } catch (Exception e) {
            // Consumer disconnected. acceptLoop will accept the next one.
        } finally {
            try { ch.close(); } catch (IOException ignored) {}
        }
    }

    public void send(TickPayload p) {
        try {
            // offer() not put() — drop on overflow rather than block the game thread
            outbox.offer(mapper.writeValueAsString(p));
        } catch (Exception ignored) {}
    }

    public void sendEvent(String type, Map<String, Object> data) {
        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("event", type);
            envelope.put("data", data);
            outbox.offer(mapper.writeValueAsString(envelope));
        } catch (Exception ignored) {}
    }
}
```

### Why bounded queue + `offer`

If Raxol stalls, RuneLite's event thread must never block — that would freeze the game. 256-entry queue at 1 tick = ~2.5 minutes of buffer, then we drop. The drop is silent because frame loss is recoverable (Raxol just sees the state machine "jump"); blocking the game is not.

## File: `TickPayload.java`

```java
package io.axol.monkwatcher;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TickPayload {
    public long t;
    public int tick;
    public int anim = -1;
    public int poseAnim = -1;
    public int mouseIdleTicks;
    public int kbIdleTicks;
    public Integer npc;        // null when not interacting
    public boolean isMonk;
    public int x;
    public int y;
    public int plane;
    public int hp;
    public int maxHp;
    public int prayer;
    public int maxPrayer;
    public int runEnergy;
}
```

## File: `NpcCatalog.java`

```java
package io.axol.monkwatcher;

import java.util.Set;

public final class NpcCatalog {
    // Verify each ID against the live RuneLite cache before shipping.
    // The chisel.weirdgloop.org NPC lookup is the easiest source of truth.
    // Wrong IDs fail silently — kill counter stays at zero with no error.
    private static final Set<Integer> MONK_IDS = Set.of(
        4127  // Monk (Edgeville Monastery) — verify
        // Add Ardougne Monastery monks if you'll train there too.
        // Do NOT include Monks of Zamorak — they're a different NPC family.
    );

    private NpcCatalog() {}

    public static boolean isMonk(int id) {
        return MONK_IDS.contains(id);
    }
}
```

## Testing

`NpcCatalogTest` is a one-liner. The real testing happens in Raxol against recorded socket streams — the plugin itself is thin enough that a manual `nc -U /tmp/raxol-monkwatcher.sock` smoke test before each install is sufficient.

## Smoke test

1. `./gradlew shadowJar`
2. Copy jar into `~/.runelite/sideloaded-plugins/`
3. Launch RuneLite with `--developer-mode`
4. Enable "Raxol Monk Watcher Bridge" in the plugin sidebar
5. In another terminal: `nc -U /tmp/raxol-monkwatcher.sock` (or `socat - UNIX-CONNECT:/tmp/raxol-monkwatcher.sock`)
6. Log into OSRS, see JSON lines streaming once per tick
7. Attack a monk in Edgeville Monastery, see `isMonk: true` and a `monk_killed` event on death

If steps 5-6 work but step 7 shows `isMonk: false`, your monk NPC ID is wrong. Open RuneLite's NPC info overlay (`Ctrl+Shift+D` developer tools), hover the monk, read the ID, update `NpcCatalog`.

## Things deliberately not included

- **No action sending.** No `MenuOptionClicked` injection, no `KeyManager` press simulation. Read-only.
- **No XP tracking.** RuneLite already has a great XP tracker plugin. We're not replacing it.
- **No inventory snapshot.** Out of scope for the watcher loop.
- **No level-up event implementation.** Stubbed in `onStatChanged` but commented out — wire it up only if Raxol's view needs it.
- **No screenshot capture.** Tempting for the watch glance but out of scope and battery-hostile.
