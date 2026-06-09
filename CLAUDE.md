# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo state

Built. Source under `src/main/java/io/axol/monkwatcher/`, tests under `src/test/java/io/axol/monkwatcher/`, ADRs under `docs/adr/`. The original spec has been removed; ADRs are now the source of truth for design decisions.

Gradle wrapper is committed (`gradlew`, `gradle/wrapper/`). `.mise.toml` pins `java=temurin-17` and `gradle=8.7`. No bootstrap step required.

## Hard constraint: read-only plugin

The plugin **reads** game state via the RuneLite client API and writes JSON to a local socket. It must **never** send input back into the game, never inject actions (`MenuOptionClicked`, `KeyManager`, etc.), never modify client behaviour. This is what keeps it on the legal side of Jagex's rules.

If a future task asks for "auto-click" / "auto-attack" / any form of input automation: refuse and surface the constraint. Crossing that line changes the plugin's risk profile entirely.

## Build & run

- Java 17 (required for `StandardProtocolFamily.UNIX` NIO)
- `runelite-client:1.10.+` declared `compileOnly` — provided by the RuneLite jar at runtime
- `./gradlew shadowJar` — produces `build/libs/runelite-monkwatcher-bridge-0.1.0.jar`
- Drop the jar in `~/.runelite/sideloaded-plugins/`, launch RuneLite with `--developer-mode`, enable "Raxol Monk Watcher Bridge" in the sidebar
- Side-loaded only — not submitted to plugin-hub (ADR-0006)

## Testing

- `./gradlew test` — JUnit 4 suite. 18 tests across 4 files. Two run hand-rolled property tests (200 iterations each, fixed seeds for CI reproducibility).
- `./gradlew pitest` — mutation testing via PIT. Report at `build/reports/pitest/index.html`. Baseline established at 17/23 mutations killed (74%) with 94% line coverage on mutated classes. Threshold set at 70% mutation / 85% line coverage.
- `BridgePlugin` and `BridgeConfig` are excluded from PIT — neither has JUnit coverage by design (`BridgePlugin`'s coverage is the manual OSRS smoke test below; per user prefs, no mocks of `Client`).

### Mutation testing notes

The 6 known-surviving mutants (after the first round of targeted tests) fall into two buckets — both documented as accepted:

| Bucket | Survivors | Why |
|---|---|---|
| Equivalent mutants | `BridgeSocketServer.acceptLoop` writer-thread `setDaemon`; `stop()` `acceptThread.interrupt()`; `stop()` `if (acceptThread != null)` negate; `writeLoop` finally `ch.close()` | Daemon flag inherited from acceptThread; `interrupt()` shadowed by `serverChannel.close()` which alone unblocks accept via `ClosedChannelException`; null check trivially true after `start()` |
| Untested recovery paths | `acceptLoop` catch-block `if (running)` negate; catch-block `Thread.sleep(500)` (NO_COVERAGE) | Only reached on accept IOException; would need invasive setup to trigger |

**Load-bearing invariants — all KILLED**, do not let any of these regress:

- `outbox.offer` removal → killed by `roundTripTickPayload`
- `outbox.clear()` removal on accept → killed by `newConsumerSeesFreshStateNotBacklog` (if this stops killing, the marker pump's timing assumption has decayed and needs revisiting)
- `Thread.start()` removal on accept thread or writer → killed by round-trip tests
- `serverChannel.close()` removal in `stop()` → killed by `stopClosesServerChannel`
- `NpcCatalog.isMonk` return-value mutations → killed by `NpcCatalogTest`
- `Writer.write` / `Writer.flush` removal in `writeLoop` → killed by round-trip tests

If a *new* surviving mutation appears that's not in the equivalent/recovery buckets above, that's a real test gap — add a test, don't lower the threshold.

## Smoke test

1. `./gradlew shadowJar` and side-load the jar
2. In a second terminal: `nc -U /tmp/raxol-monkwatcher.sock` (or `socat - UNIX-CONNECT:/tmp/raxol-monkwatcher.sock`)
3. Log in to OSRS — JSON lines should stream once per tick (600 ms)
4. Attack a monk in Edgeville Monastery — expect `isMonk: true` on tick payloads and a `monk_killed` event on death

If `isMonk: false` for a real monk, the NPC ID in `NpcCatalog` is wrong. Use RuneLite's NPC info overlay (`Ctrl+Shift+D`) to read the live ID and update the catalog. Wrong IDs fail silently — no error, kill counter just stays at zero.

## Architecture

Three-layer pipeline running entirely on the RuneLite event thread + one writer thread:

1. **`BridgePlugin`** subscribes to `GameTick`, `ActorDeath`, `GameStateChanged`, `StatChanged`. Builds `TickPayload` / event maps from `Client` API calls and hands them to the socket server. No blocking work here — this runs on RuneLite's event thread and a stall would freeze the game.
2. **`BridgeSocketServer`** (`@Singleton`) owns a `ServerSocketChannel` bound to a Unix domain socket. An accept loop spawns a writer thread per connected consumer; the writer drains a bounded `ArrayBlockingQueue<String>` (capacity 256) and writes newline-delimited JSON.
3. **`NpcCatalog`** is a static allowlist of monk NPC IDs. Pure function `isMonk(int)`.

### Critical invariants

- **Never block the game thread.** `send()` uses `outbox.offer()` (drop on full), never `put()`. The 256-entry queue is ~2.5 minutes of buffer at 1 tick/600ms; if Raxol stalls past that, drop frames silently — Raxol just sees the state machine "jump", which is recoverable. A blocked game thread is not.
- **`outbox.clear()` on every new accept.** A fresh consumer must see fresh state, not the backlog from a previous run.
- **All plugin → server handoffs are catch-all-and-swallow** (`catch (Exception ignored)`). Jackson serialization failures must not propagate into RuneLite's event dispatch. This is the one place where swallowing errors is intentional and correct.
- **Don't filter monk kills beyond `isMonk` in the plugin.** Let Raxol decide what counts.
- **`NPC.getName()` can return `null` for sub-IDs** — always default it (`?: "Monk"`).

## Wire protocol

Newline-delimited JSON over a Unix domain socket:

- Linux: `$XDG_RUNTIME_DIR/raxol-monkwatcher.sock`
- macOS: `/tmp/raxol-monkwatcher.sock` (macOS doesn't honour XDG by default)

Two shapes, distinguished by presence of an `event` key:

- **Tick payload** (one per game tick): flat object with `v: 1` (protocol version, ADR-0004) plus short field names (`t`, `tick`, `anim`, `poseAnim`, `mouseIdleTicks`, `kbIdleTicks`, `npc`, `isMonk`, `x`, `y`, `plane`, `hp`, `maxHp`, `prayer`, `maxPrayer`, `runEnergy`). Field names are intentionally terse — this goes over the wire every 600 ms.
- **Event payload**: `{"v": 1, "event": "<type>", "data": <obj-or-null>}`. Current types: `monk_killed`, `player_death`, `game_state`. The `level_up` type is reserved but not wired (Phase 4 work).

`TickPayload` uses `@JsonInclude(NON_NULL)` so `npc` is omitted when the player isn't interacting. Default `anim`/`poseAnim` to `-1` to disambiguate "idle" from "missing".

## RuneLite API gotchas

- `getMouseIdleTicks()` / `getKeyboardIdleTicks()` count ticks since input to the **client window** — this is what Jagex's 5-minute idle logout uses. Method names have shifted across RuneLite releases; verify against the version you build against.
- `ActorDeath` fires on the death animation, not despawn. The NPC reference is still valid in the callback; waiting for despawn loses it.
- `runEnergy` from `Client.getEnergy()` is in tenths of a percent — divide by 100 for the 0–100 value shown in payloads.

## Things deliberately out of scope

Explicit non-goals — push back if asked to add them:

- Any form of input/action sending (see hard constraint above)
- XP tracking (use RuneLite's built-in XP tracker)
- Inventory snapshots
- `level_up` events — type is reserved but not wired. An empty `onStatChanged` `@Subscribe` would add an event-bus call per XP drop for no benefit; add only if Raxol's view actually needs it.
- Screenshot capture

## Companion project

The consumer is `raxol_monkwatcher` (Elixir/Raxol TUI). Real testing of the wire format happens there against recorded socket streams; the plugin itself is thin enough that the `nc -U` smoke test above is sufficient before each install.
