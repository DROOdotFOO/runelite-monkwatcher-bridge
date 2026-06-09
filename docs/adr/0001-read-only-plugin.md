# 1. Read-only plugin; no input synthesis

Date: 2026-06-09

## Status

Accepted (immutable)

## Context

RuneLite explicitly permits informational plugins but forbids automation that
simulates user input. Jagex's stance on macros is hostile and account-fatal.
Both the author and reviewers will be tempted to "just add auto-attack" later
- the original spec already flags this as the obvious next ask.

The rule needs a written, immutable home so a future contributor (or
future-self at 2am) does not slide the line.

## Decision

No code in this plugin will call `MenuOptionClicked` injection, `KeyManager`
press simulation, `invokeMenuAction`, `sendMenuAction`, or any other RuneLite
API that synthesizes user input. All event handlers are read-only with
respect to game state. Any PR that introduces input synthesis must be
rejected and this ADR cited.

## Consequences

- Stays inside RuneLite's allowed plugin shape; defence-pure preserved.
- Trivially auditable: a single grep for `KeyManager`, `MenuOptionClicked`,
  `sendMenuAction`, `invokeMenuAction` should return zero hits in
  `src/main/java/`.
- Closes off otherwise-interesting features (auto-monk, auto-prayer)
  permanently. That is the point.
- Requires reviewer discipline. Easy to bypass by accident if a contributor
  copies code from another plugin without reading this file.
