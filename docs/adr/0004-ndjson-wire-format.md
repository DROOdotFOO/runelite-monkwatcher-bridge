# 4. Newline-delimited JSON, terse field names

Date: 2026-06-09

## Status

Accepted

## Context

Wire format options for the plugin -> Raxol stream:

- **NDJSON** -- one JSON object per line, `\n` delimited.
- **Length-prefixed JSON** -- 4-byte length + JSON body.
- **MessagePack** / **CBOR** -- binary, compact.
- **Protobuf** -- schema-first, codegen on both sides.
- **Custom binary** -- minimum overhead, maximum brittleness.

The consumer is Elixir (Jason parser, ergonomic NDJSON via `Stream.unfold`
on `:gen_tcp.recv` / UDS reads). Throughput is ~1.7 messages/sec --
five orders of magnitude below where parser cost matters. Debuggability
beats raw efficiency at this scale.

## Decision

Newline-delimited JSON, one message per line. Field names are abbreviated
(`t`, `tick`, `anim`, `kbIdleTicks`, ...) to keep tick payloads under
~200 bytes. Tick and event payloads are distinguished by presence of an
`event` key (no envelope on ticks). `@JsonInclude(NON_NULL)` omits the
unset `npc` field when the player is not interacting.

A protocol version field (`"v": 1`) **will be added** to both payload
shapes so a future breaking change can be detected by the consumer without
sniffing field sets.

## Consequences

- Trivially debuggable: `nc -U /tmp/raxol-monkwatcher.sock` prints readable
  lines.
- Zero framing logic. `\n` is the only delimiter.
- No code generation, no schema artifact to ship and version.
- No schema validation at the boundary. Mitigation: payloads are POJOs with
  typed fields; Jackson serialization is the schema.
- Adding `"v"` now costs one field per message (~6 bytes) and avoids a
  hard cutover later.
- If throughput grows past ~1 kHz (it will not), this decision should be
  revisited in favour of MessagePack.
