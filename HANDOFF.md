# HANDOFF

Snapshot of project state for the next agent picking up this repo. Read this **before** `CLAUDE.md` — CLAUDE.md is steady-state operator guidance; this file is what's true right now and what's still pending.

## Current state

The repo is built end-to-end and locally green. Phases 0-4 of the original plan are complete:

| Phase | What | Status |
|---|---|---|
| 0 | Gradle scaffold + ADRs 0001-0006 | done |
| 1 | Domain (`NpcCatalog`, `TickPayload`, `EventPayload`) + first test | done |
| 2 | `BridgeSocketServer` + UDS round-trip test | done |
| 3 | `BridgePlugin` + `BridgeConfig` (RuneLite integration) | done |
| 4a | Property tests + 4 new socket invariant tests | done |
| 4b | PIT mutation testing wired + thresholds set against observed | done |

Last verified green on this machine:

```
./gradlew test         # 18 tests pass
./gradlew pitest       # 17/23 killed = 74%, 94% line coverage, threshold 70/85 cleared
./gradlew shadowJar    # build/libs/runelite-monkwatcher-bridge-0.1.0.jar (2.3 MB)
```

## What's NOT verified

**The only thing that can fail unexpectedly is the live OSRS smoke test.** I could not run it from this environment. The plugin compiles and the wire format is unit-tested, but it has never been side-loaded into RuneLite against a logged-in OSRS character. The Phase 3 exit criteria are listed in `CLAUDE.md` under "Smoke test."

Likely failure modes if the smoke test misbehaves:

| Symptom | Most likely cause |
|---|---|
| Plugin doesn't appear in sidebar | jar in wrong path; check `~/.runelite/sideloaded-plugins/` |
| `nc -U` connects but no JSON streams | socket path mismatch between plugin config and shell; `BridgeConfig.socketPath()` resolves XDG_RUNTIME_DIR or `/tmp` |
| JSON streams but `isMonk: false` on a real monk | `NpcCatalog.MONK_IDS` (currently just `{4127}`) doesn't include the live ID. Use RuneLite's `Ctrl+Shift+D` NPC overlay and add the actual ID |
| Compile errors against runelite-client API | `runelite-client:1.10.+` is a floating minor pin; pin to a specific version in `build.gradle.kts`. Idle-tick and energy methods (`getMouseIdleTicks`, `getKeyboardIdleTicks`, `getEnergy()`) shift across RuneLite releases and may need adjustment |

## Open Phase 4 items (deferred, not abandoned)

Original plan parked these as "do only if Raxol actually needs them" — confirm with the user before implementing:

- **`level_up` event wire-up.** Stub was dropped in Phase 3 because an empty `@Subscribe` adds an event-bus call per XP drop. Wire up only if Raxol's view consumes it.
- **Dropped-frames counter** on `BridgeSocketServer.outbox.offer()` failure. ADR-0003 documents "silent drops"; instrument only if drops are observed in practice.
- **Single-consumer enforcement.** ADR-0005 says multi-connect is undefined; the current implementation lets two consumers steal messages from each other. Either reject second accept or fan out per-writer queues — only if a "second consumer" use case appears.

## Open infrastructure items

- **No CI yet.** Deferred per user's call ("local green first"). Wiring would be straightforward: a GitHub Actions workflow running `./gradlew test pitest` on push. Note that PIT runs UDS-based tests, so the runner must be Linux or macOS (Windows runners won't work). The `/tmp` path used in tests is short enough on both.
- **`gradle/`, `gradlew`, `gradle/wrapper/gradle-wrapper.jar` are committed** so first-clone doesn't need a `gradle wrapper` bootstrap. `.mise.toml` pins `java=temurin-17`, `gradle=8.7`.
- **No code signing / no release tags.** Personal sideloaded tool (ADR-0006); add only if distribution model changes.

## Gotchas surfaced during this session

Things that bit me — flagging them so the next agent doesn't lose time:

1. **macOS UDS `sun_path` is 104 chars.** Default `java.io.tmpdir` (`/var/folders/xx/.../T/`) eats ~50 of those before the filename, so any UDS test using `java.io.tmpdir` fails with `SocketException: Unix domain path too long`. Test sockets use `/tmp/mw-test-<12char>.sock` directly. If you add new socket tests, follow that pattern via `tempSocketPath()`.

2. **PIT's test worker is a daemon thread.** A naive `assertTrue(thread.isDaemon())` passes whether or not `setDaemon(true)` was called, because newly-created threads inherit daemon-ness from the parent. `acceptThreadIsDaemon` is structured to spawn the server from an explicit non-daemon helper thread for this reason. The `writerThreadIsDaemon` test that originally existed was deleted because it's a genuine equivalent mutant — the writer thread inherits daemon from acceptThread regardless.

3. **6 surviving PIT mutants are documented as accepted.** See the table in `CLAUDE.md` under "Mutation testing notes." Three are equivalent mutants, one is NO_COVERAGE in a recovery path. **Do not chase 100%** by adding contrived tests for these — adding more genuine tests is fine, but the documented exclusions are correct.

4. **Shadow plugin and JDK install had transient network failures during this session.** Both resolved on retry. If a fresh setup fails to resolve `com.github.johnrengelman.shadow:8.1.1` or to download Temurin from GitHub releases, just retry before assuming a real coordinate change.

5. **Hex literal underscores in Java cannot trail a non-digit.** `0xC0FFEE_D00D_L` is illegal (underscore before `L` suffix); `0xC0FFEE_D00DL` is fine. Caught by the first compile.

6. **Property tests use hand-rolled `Random` with fixed seeds**, not a PBT library. On a failure, the error message tags the iter index; re-run with the same seed and bisect via `if (i == FAILING_IDX)` to inspect. Seeds: `0xC0FFEE_D00DL`, `0xBADC0FFE_E0DDF00DL`, `0xCAFEBABE_DEADBEEFL`.

## Considered and explicitly declined

- **TickPayload → `record`.** Weighed against builder and status-quo POJO. Rejected: positional 17-arg constructor at call site loses field-name visibility, `v=1` constant requires `@JsonGetter` workaround (records forbid extra instance fields), test partial-construction becomes painful. The DTO is write-only and lives microseconds — immutability and structural equality buy nothing concrete. Don't reopen unless the wire-format surface area changes significantly.
- **TickPayload → builder.** Same evaluation: ~40 lines of boilerplate for marginal-only test-ergonomics win.
- **`BridgeSocketServer` accept/write loops → reactive streams.** `while (running)` over blocking I/O is the right Java idiom here; Project Reactor / RxJava would be a paradigm shift for "drain a queue, write to a socket."

## Where to look

| Question | File |
|---|---|
| What is this project and why | `README.md` |
| Architecture, invariants, do-not-break rules | `CLAUDE.md` |
| Load-bearing architectural decisions | `docs/adr/0001-…0006-*.md` |
| Build configuration | `build.gradle.kts`, `.mise.toml` |
| What tests exist and what they prove | `src/test/java/io/axol/monkwatcher/*Test.java` |
| Mutation testing report | `build/reports/pitest/index.html` (regenerate with `./gradlew pitest`) |

## Suggested next move

In order of likely user priority:

1. **Run the live OSRS smoke test.** Drop the jar in `~/.runelite/sideloaded-plugins/`, sideload, connect with `nc -U`, log in, and verify JSON streams. If `isMonk: false` on a real monk, update `NpcCatalog.MONK_IDS`.
2. **Confirm the Raxol consumer can parse the `"v": 1` prefix.** ADR-0004 commits to it; the consumer might need a small adapter.
3. **Wire CI** if local smoke test passes and the user wants the threshold guarded automatically.
4. **Pick up a Phase 4 deferred item** only if the user asks; do not pre-implement.
