# 3. Bounded queue with drop-on-overflow

Date: 2026-06-09

## Status

Accepted

## Context

The producer is the RuneLite event thread; the consumer is a writer thread
draining to an external Elixir process. Three failure modes to consider:

1. Consumer not connected -- producer must still run, queue accumulates
   briefly.
2. Consumer slow -- backpressure must not propagate to the game thread.
3. Consumer crashes mid-write -- producer must continue running.

Queue strategies:

| Strategy | Game-thread behaviour on stall |
|----------|-------------------------------|
| Unbounded + `add` | Memory grows without bound |
| Bounded + `put` | **Blocks the game thread**, freezes the client |
| Bounded + `offer` | Returns immediately, drops the message |

A frozen game client is a far worse failure than dropped telemetry frames.
The downstream consumer (Raxol) is already designed to tolerate state
"jumps" because the JSON is a snapshot of current state, not a delta.

## Decision

`ArrayBlockingQueue<String>` with capacity 256 (~2.5 minutes at 1 tick /
600 ms). `BridgeSocketServer.send()` and `sendEvent()` use
`outbox.offer(...)` and discard the boolean return. Frame loss is silent.

## Consequences

- Game thread never blocks on socket I/O. Hard guarantee.
- Memory bounded at ~256 small JSON strings (negligible).
- Consumer can disconnect, restart, and reconnect cleanly without
  destabilising the plugin.
- Silent frame loss when the consumer stalls > 2.5 min. Raxol must tolerate
  state-jumps -- confirm in the consumer's design before relying on
  derivative metrics (e.g. "ticks since X").
- No visibility into drops. Mitigation deferred: add a dropped-frames
  counter exposed via the next event payload only if drops are observed in
  practice.
