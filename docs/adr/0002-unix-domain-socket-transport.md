# 2. Unix domain socket as the transport

Date: 2026-06-09

## Status

Accepted

## Context

The plugin must hand JSON to a co-located Elixir process (`raxol_monkwatcher`)
on the same machine. Options considered:

- **TCP loopback** -- works but allocates a port, opens firewall surface,
  needs auth or accept-localhost-only checks.
- **Named pipes / FIFOs** -- single-direction by default; multiplexing
  multiple consumers is awkward.
- **stdin / stdout** -- RuneLite owns the JVM process; not available.
- **Shared memory** -- massive complexity for ~1.7 messages/sec.
- **Unix domain sockets** -- bidirectional, filesystem-permission gated, no
  port allocation, native JDK 17 support via `StandardProtocolFamily.UNIX`.

## Decision

Use a Unix domain socket via JDK 17's `StandardProtocolFamily.UNIX`. Path:

- Linux: `$XDG_RUNTIME_DIR/raxol-monkwatcher.sock`
- macOS: `/tmp/raxol-monkwatcher.sock` (macOS does not honour XDG by default)

Resolution lives in `BridgeConfig.socketPath()` so the user can override.

## Consequences

- No port allocation; no network stack overhead; no firewall surface.
- Filesystem-permission gated -- only the same user can read.
- JDK 17 has native support, so no JNI dependency on `junixsocket` or netty.
- Sets the Java floor at 17. RuneLite is fine with this; pinned in
  `build.gradle.kts` via `JavaLanguageVersion.of(17)`.
- Windows would need a rewrite to named pipes. Out of scope; this is a
  personal tool, macOS + Linux only.
- On macOS, `/tmp` is not XDG-honouring; we hardcode `/tmp` as fallback.
  Acceptable but worth a comment in the config default.
