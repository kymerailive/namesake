# Namesake

*Villagers who remember your name.*

A village-simulation mod for Minecraft **1.21.1**, on Fabric and NeoForge.

Namesake replaces Minecraft's villagers with people who notice what you do, remember it, and tell
each other about it. Help someone in one village and, days later, a stranger two settlements away
may greet you by name — because word travelled the road you built.

**The thesis, in one sentence:** a deed witnessed by one villager changes what a different villager,
in a different settlement, says to you later.

## Status

**Pre-alpha. Not playable.** Session 05 of a planned 16-session build to a vertical slice.
Villagers carry a persistent identity that survives save, chunk unload and zombification; they come
from somewhere, with a culture, a household and eight rolled personality axes taken from a
settlement detected out of a real bell; and those axes now change what happens. Feed a hungry
villager and everyone who could see you do it thinks a little better of you — by a different amount
each, because the same loaf is worth more to some people than to others. Nothing is told to anyone
yet: that is session 08's gossip and session 09's dialogue. See [`WORKPLAN.md`](WORKPLAN.md) for
what is built and what is next.

## Design

- [`DESIGN.md`](DESIGN.md) — what we are building and why. 45 ruled decisions.
- [`WORKPLAN.md`](WORKPLAN.md) — the ledger. What happens next, with exit criteria.
- [`CLAUDE.md`](CLAUDE.md) — orientation and hard rules for anyone working on this.

Three principles the codebase is built to enforce:

1. **Every social value must have a named consumer that is not a display.** If you cannot name the
   `if` statement a field feeds, delete the field.
2. **Every serverbound packet carries its own authorization.** A packet type cannot be registered
   without one, and a test fails the build if a handler skips the gate.
3. **Never ship a persisted schema change without a datafixer** and a load test against a
   pre-change save.

## Architecture

Namesake **attaches** to the vanilla `Villager` rather than replacing it. Every other mod's
`instanceof Villager` check keeps working, and vanilla trades, professions, POI and raids keep
working too.

```
common/     loader-agnostic — the simulation. Target: 96% of all code.
fabric/     Fabric bootstrap + Platform implementation.
neoforge/   NeoForge bootstrap + Platform implementation.
```

Loader differences go behind `net.namesake.platform.Platform`, resolved with `ServiceLoader`.
Common code never references Fabric or NeoForge types.

## Building

Requires JDK 21.

```bash
./gradlew test                            # unit tests
./gradlew :fabric:build :neoforge:build   # both loader jars
./gradlew :fabric:runClient               # dev client, Fabric
./gradlew :neoforge:runClient             # dev client, NeoForge
```

### The attach-bet harness

Some claims can only be checked by playing the game: that a persona survives a chunk unload, a
save/quit/reload, and being zombified and cured. `-Pharness` drives a real client through all of it
and prints a pass/fail line per leg.

```bash
./gradlew :fabric:runClient -Pharness=setup    # build the subjects, then save and quit
./gradlew :fabric:runClient -Pharness=verify   # reopen and check they came back unchanged
```

It creates its own world (`namesake_attachbet`), and it is inert without the flag — it rewrites game
rules, moves the player and kills a villager, so it must never run in a real world.

## Relationship to Minecraft Comes Alive

Namesake is a successor **in spirit** to MCA Reborn and shares **no code with it**. MCA is
GPL-3.0; this project studied its architecture, took ideas, and copied nothing. Where MCA is
referenced in comments it is as a design citation.

## License

LGPL-3.0. Addons may be licensed however you like; changes to Namesake itself stay open.
See [`LICENSE`](LICENSE) and [`LICENSE.GPL`](LICENSE.GPL).
