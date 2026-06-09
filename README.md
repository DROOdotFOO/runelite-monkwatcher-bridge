# runelite-monkwatcher-bridge

RuneLite plugin that streams local player state to a Unix domain socket for consumption by `raxol_monkwatcher`. Personal use, side-loaded only.

**Read-only.** The plugin reads game state from the RuneLite client API and writes JSON. It never sends input back into the game, never injects actions, never modifies client behaviour. See [ADR-0001](docs/adr/0001-read-only-plugin.md).

## Status

Personal tool. Side-loaded via RuneLite's `--developer-mode`. Not submitted to plugin-hub. No release cadence, no compatibility promise.

## Quick start

```bash
gradle wrapper --gradle-version 8.7   # one-time bootstrap
./gradlew shadowJar
cp build/libs/runelite-monkwatcher-bridge-0.1.0.jar ~/.runelite/sideloaded-plugins/
# Launch RuneLite with --developer-mode, enable "Raxol Monk Watcher Bridge"
```

Then in another terminal:

```bash
nc -U /tmp/raxol-monkwatcher.sock   # macOS
nc -U "$XDG_RUNTIME_DIR/raxol-monkwatcher.sock"   # Linux
```

Log into OSRS and JSON lines stream once per game tick (600 ms).

## Wire protocol

Newline-delimited JSON over a Unix domain socket. Two shapes — both carry `"v": 1` for protocol versioning.

Tick payload (one per game tick):

```json
{"v":1,"t":1701234567890,"tick":8421337,"anim":422,"poseAnim":808,
 "mouseIdleTicks":12,"kbIdleTicks":18,"npc":4127,"isMonk":true,
 "x":3057,"y":3484,"plane":0,"hp":67,"maxHp":85,"prayer":31,
 "maxPrayer":82,"runEnergy":84}
```

Event payload (discrete game events):

```json
{"v":1,"event":"monk_killed","data":{"id":4127,"name":"Monk"}}
{"v":1,"event":"player_death","data":null}
{"v":1,"event":"game_state","data":{"state":"LOGGED_OUT"}}
```

Full protocol decisions: [ADR-0004](docs/adr/0004-ndjson-wire-format.md).

## Architecture

Three modules:

- `BridgePlugin` — RuneLite event-bus adapter. Builds payloads, hands off to the socket server.
- `BridgeSocketServer` — Unix domain socket server with a bounded `ArrayBlockingQueue` (cap 256). Producer never blocks the game thread.
- `NpcCatalog` — static allowlist of monk NPC IDs.

Key invariants documented in ADRs:

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-read-only-plugin.md) | No input synthesis, ever |
| [0002](docs/adr/0002-unix-domain-socket-transport.md) | Unix domain socket over TCP/named pipes |
| [0003](docs/adr/0003-bounded-queue-drop-on-overflow.md) | Bounded queue, drop on overflow (never block the game thread) |
| [0004](docs/adr/0004-ndjson-wire-format.md) | NDJSON wire format with `v: 1` versioning |
| [0005](docs/adr/0005-single-consumer-model.md) | Single consumer; multi-connect undefined |
| [0006](docs/adr/0006-sideloaded-distribution-only.md) | Sideloaded only; no plugin-hub |

## Testing

```bash
./gradlew test       # JUnit 4 suite, includes 2 hand-rolled property tests
./gradlew pitest     # mutation testing via PIT; report at build/reports/pitest/index.html
```

The plugin entry point (`BridgePlugin`) is deliberately untested in JUnit — its coverage is the manual OSRS smoke test described in [`CLAUDE.md`](CLAUDE.md).

## Companion project

The consumer is `raxol_monkwatcher`, an Elixir/Raxol TUI. This repo only emits the stream; everything downstream (kill counters, idle detection, dashboards) lives there.

## Repository layout

```
runelite-monkwatcher-bridge/
├── build.gradle.kts
├── settings.gradle.kts
├── runelite-plugin.properties
├── runelite-monkwatcher-bridge.md       # original spec (historical)
├── CLAUDE.md                             # operator guide for Claude Code
├── docs/adr/                             # architecture decision records
├── src/main/java/io/axol/monkwatcher/    # plugin code
└── src/test/java/io/axol/monkwatcher/    # JUnit 4 + hand-rolled PBT
```
