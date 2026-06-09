# 6. Sideloaded distribution only

Date: 2026-06-09

## Status

Accepted

## Context

RuneLite distributes plugins through a curated plugin-hub or via the
`--developer-mode` sideloaded-plugins directory. Submitting to plugin-hub
implies a maintenance contract with external users, a review process, and
a stable distribution promise.

This plugin exists to feed exactly one external consumer
(`raxol_monkwatcher`) on the author's machine. It is sub-100 LOC of game
integration. There is no audience to maintain a release cadence for.

## Decision

Distribute as a shadowed jar copied to `~/.runelite/sideloaded-plugins/`,
launched via RuneLite's `--developer-mode`. **Do not submit to
plugin-hub.** No versioned releases, no changelog, no compatibility
matrix.

## Consequences

- Zero maintenance commitment to anyone outside the author.
- API drift in `runelite-client` is fixed on the author's schedule, not
  RuneLite's release cadence.
- Other users cannot install this without cloning + building. Acceptable.
- No automated update path. If the plugin breaks after a RuneLite update,
  the author notices immediately (Raxol stops receiving ticks) and fixes
  it locally.
