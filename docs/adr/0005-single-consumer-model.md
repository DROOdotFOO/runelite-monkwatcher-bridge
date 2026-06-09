# 5. Single-consumer model

Date: 2026-06-09

## Status

Proposed

## Context

`BridgeSocketServer.acceptLoop` spawns a writer thread per accepted
connection, but all writers share the same `outbox` queue. With two
consumers connected, each message is delivered to whichever writer polls
first -- consumers steal messages from each other rather than seeing the
same stream. Additionally, `outbox.clear()` runs on every accept, nuking
any in-flight tail for a previous consumer.

## Decision

The plugin targets a **single consumer at a time** (Raxol). Multi-connect
behaviour is undefined and may change without notice. If a second consumer
use case appears (e.g. a metrics tee or a dashboard sidecar), revisit by:

- explicitly rejecting the second accept with a clear log line, OR
- giving each writer its own per-connection queue and fan-out from the
  producer side.

## Consequences

- Current implementation is correct for the only consumer that exists.
- A future contributor connecting `nc -U` while Raxol is running will see
  partial / interleaved output. This is the intended trade for
  implementation simplicity.
- Revisit this ADR before any "second consumer" feature lands.
