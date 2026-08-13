# Namesake — read this first

This file loads at the start of every session. It is deliberately short. Its only job is to point
you at the truth and stop you breaking things before you get there.

## What this is

A Minecraft village-simulation mod for **1.21.1**, Fabric + NeoForge, Java 21. Successor in spirit
to Minecraft Comes Alive Reborn, but sharing **no code with it**.

**The thesis, in one sentence:** a deed witnessed by one villager changes what a different villager,
in a different settlement, says to you later.

## The single source of truth

- **`WORKPLAN.md`** — the ledger. What happens next, session by session, with exit criteria.
  Read it first, update it last. **Where any other document disagrees on sequence, it wins.**
- **`DESIGN.md`** — what we are building and why. 41 ruled decisions.

**Do not write a new plan or handoff document.** Change the ledger. Documents contradicting each
other is the problem, not the format.

## Hard rules — each maps to a failure that has actually happened

1. **Never ship a persisted schema change without a datafixer and a load test against a pre-change
   save.** This is this project's live-state trap. MCA shipped a release that was not backwards
   compatible and told users to back up their worlds.
2. **A change is inert until it is merged and pushed.** Committing is not shipping. The whole cycle:
   `test → merge → verify in game → push to origin`. Push is not a separate errand — the sibling
   LNK project lost 45 commits across two sessions to exactly this.
3. **Verify by effect.** A spent flag, a tautological test and a swallowed exception all read as
   success. Query the data. **A fix is not done until it has been reverted and the test watched to
   fail.**
4. **Baseline vanilla before optimising anything.** 400 loaded vanilla villagers cost ~18 ms/tick
   before a line of our code runs. Our own budget is ~6 µs/tick. Measure the right number.
5. **No social value without a named non-display consumer.** If you cannot name the `if` statement a
   field feeds, delete the field. Enforced by a failing test, not by intention. Both reference
   codebases died on this — MCA's genes and traits all terminate in a renderer; LNK's affinity score
   is never read by any prompt.
6. **Every serverbound packet carries its own authorization.** `ServerboundVerb` cannot be registered
   without an explicit `authorize(sender, target)`. A reflection test fails the build if a handler
   skips the gate. MCA has 29 C2S packets with essentially none of this.

## Read-only territory — do not modify

- **`reference/mca-reborn/`** — upstream MCA source, GPL-3.0, cloned only to read. It is gitignored.
  **Never copy code from it.** We take ideas, not lines. Citing it in a comment is fine.
- **`C:\THT LNK MINI` and `C:\THT LNK BROADCAST`** — the owner's live Discord RPG, and the source of
  this mod's social design. **LNK Mini runs a live bot and often has another Claude session working
  in it.** Read for reference if genuinely needed; never write, never run its tests, never touch
  `players.json` or `lore.db`.

## Environment

- Windows. PowerShell is primary; a Bash tool exists and takes POSIX syntax.
- `gh` is authenticated as `kymerailive`.
- Gradle: `./gradlew` (use `.\gradlew.bat` from PowerShell).
- Java 21 required for the mod; a newer JDK may be the system default — pin the toolchain.

## Working agreement

The owner implements-by-review: **you write the code, they playtest and rule on feel.** When a
session's exit criterion is about how something *feels* rather than whether it works, stop and hand
it to them rather than deciding yourself.
