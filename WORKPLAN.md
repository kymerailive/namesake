# WORKPLAN — Namesake

**The ledger.** What happens next, in order, with exit criteria. Read first, update last.
Where any other document disagrees on sequence, this wins.

- **Status:** session 01 complete. **The attach bet holds** — a persona rides a vanilla `Villager`
  through save, chunk unload and zombification on both loaders. Repo live at
  https://github.com/kymerailive/namesake
- **Target:** 16 sessions to the ship-or-kill test (session 10), playable slice at 15.
- **Companion:** `DESIGN.md` owns *what* we build. This owns *what happens next*.

---

## Status board

| Session | Block | State |
|---|---|---|
| 00 | Repo and skeleton | **done** — 2026-08-13 |
| 01 | Persona, persistence, attach bet | **done** — 2026-08-13 |
| 02 | Authorization layer | **NEXT** |
| 03 | Traits, cultures, settlement detection | pending |
| 04 | Profiler spike | pending |
| 05 | Bonds and deeds | pending |
| 06 | Episodic memory | pending |
| 07 | Headless simulation harness | pending |
| 08 | Gossip and distortion | pending |
| 09 | Dialogue pools and residency | pending |
| 10 | Roads and propagation — **SHIP-OR-KILL** | pending |
| 11 | Notice Board | pending |
| 12 | Standing bands | pending |
| 13 | Day plan I — free slots | pending |
| 14 | Day plan II — ERRAND activity | pending |
| 15 | Art, config, playtest | pending |

---

## Phase 1 · Foundations

### Session 00 — Repo and skeleton
**Build.** `gh repo create kymerailive/namesake --public` (LGPL-3.0). Multiloader Gradle skeleton:
`common` / `fabric` / `neoforge`, hand-rolled after MCA's 96%-common split — *study the structure,
copy no code*. Mod id `namesake`, package `net.namesake`. CI running **build and test** on both
loaders. Commit `CLAUDE.md`, `WORKPLAN.md`, `DESIGN.md`, `.gitignore` (excluding `reference/`).

**Exit.** Empty mod loads in-game on *both* loaders, CI green, and the push is confirmed on origin.

### Session 01 — Persona, persistence, and the attach bet
**Build.** The `Persona` record. Loader-agnostic attachment to the vanilla `Villager` (Fabric
attachment API / NeoForge data attachments behind one common interface). `NpcRegistry extends
SavedData`, UUID-keyed, with `schemaVersion`. A DataFixer that actually runs, plus a load test
against a saved pre-change world.

**Proves.** *The entire architectural bet.* If a persona cannot ride a vanilla villager through
save/load/unload/zombie-conversion, everything downstream changes — and you learn it now, not at
session 8.

**Exit — verify by effect.** Spawn a villager, attach a persona, save, quit, reload: same UUID.
Unload the chunk and reload: survives. Convert to zombie villager and cure: survives. Then bump the
schema version, load the old save, and *watch the datafixer run*.

### Session 02 — The authorization layer
**Build.** `ServerboundVerb` base that cannot be registered without an explicit
`authorize(ServerPlayer sender, Target target)`. Reach, session-token and rate checks in the base.
One trivial packet end to end.

**Exit — revert and watch it fail.** Deliberately add an unguarded handler; the build must go red.
Remove it; green. Only then is the gate real.

---

## Phase 2 · Generation and measurement

### Session 03 — Traits, cultures, settlement detection
**Build.** Eight-axis trait vector with **three-layer rolling**: settlement mean → household ±20 →
individual ±25, clamped to ±100 (never to the parental range). Per-culture phonotactic syllable
grammar — >10⁶ names per culture, **must never exhaust**. Culture palette. Settlement registration
from vanilla POI clusters (bell + workstations), with a one-shot 128-block survey producing
specialty, defensibility and the needs vector.

**Exit.** `/namesake debug dump` lists 20 NPCs with names, traits, culture. Walk to a second
village: the names are audibly from somewhere else, and households are recognisably related.

### Session 04 — The profiler spike *(2 days, highest technical risk)*
**Build.** 400 dummy `Persona` records, the 20-bucket rotating sweep, 8 simulated players each
tracking 12 entities. Profile at `mob tick → villagerBrain` (`Villager.java:283-285`), plus sensing,
navigation, goalSelector.

**Order matters.** **Baseline vanilla first** — 400 loaded vanilla villagers with zero mod code —
then add ours. Without that number ours means nothing.

**Exit.** Real numbers written into this ledger. Population target confirmed or revised *before*
anything depends on it. Expected: ours ~5.95 µs/tick against ~18 ms/tick of vanilla ticking, which
is what forces the record-is-authority architecture and a 60–100 loaded-entity ceiling.

---

## Phase 3 · The social core

### Session 05 — Bonds and deeds
**Build.** `Bond` (4 signed axes) and `Deed` records. Six deed types: `GIFT_WANTED`,
`GIFT_UNWANTED`, `FED_HUNGRY`, `STRUCK_RESIDENT`, `KILLED_RESIDENT`, `DEFENDED_RAID`. Witness scan —
`AABB.inflate(24)` + `canSee()`, cap 12, **on emit only, never polled**. Daily cap 8, lazy decay
toward `peak × 0.4`, one static `float[8][6]` personality weight table.

**Tests.** Cap holds; negatives bypass both cap and personality softening; warmth floors at 0;
trust and respect floor at −64; decay is lazy and idempotent.

**Exit.** Feed a hungry villager with 3 witnesses: subject +3, each witness +1. Repeat nine times
in a day: cap holds at 8. The same gift lands differently on a suspicious smith than a warm innkeeper.

### Session 06 — Episodic memory
**Build.** 32-entry deed ring per NPC (~768 B). Exact dedupe on `(npcUuid, deedId)` — possible only
because deeds are structs, not sentences.

**Exit.** Emit 40 deeds at one NPC: ring holds newest 32, zero duplicates, survives save/load.

### Session 07 — The headless simulation harness *(highest leverage in the plan)*
**Build.** Fast-forward a settlement N in-game days without rendering, dumping the chronicle and an
earn-rate report. `DialogueStats` SavedData, `/namesake debug stats`, `/namesake debug earnrate`.

**Why here.** LNK set skill gates at 35–205 against an observed max affinity of **32** — zero
players ever reached the lowest gate, and nobody noticed, because nothing measured earn rates. You
cannot tune a system whose payoff is at hour 30 by playing it.

**Exit.** Run 100 in-game days in under a minute; get a readable chronicle and a bond-earn-rate
histogram. **Every band threshold from here is set from this data, never from intuition.**

### Session 08 — Gossip and distortion
**Build.** Per-settlement deque, cap 32. Drain 4/in-game hour. Same-settlement transfer at 0.35;
`confidence × 0.85` per hop; below confidence 50 the actor blurs to "someone from the north". Max 2
hops. **Nothing is ever invented — facts only degrade.**

**Exit.** In the harness: a deed emitted in A is held by 60%+ of A's residents within two in-game
days at descending confidence, with at least one holder unable to name the actor.

---

## Phase 4 · The thesis

### Session 09 — Dialogue pools and residency
**Build.** Four pools — stranger / known / warm / hostile — × 5 registers × ~8 lines ≈ 160 lines.
Per-culture tics: openers, tag questions, address terms, formality bias. The residency threshold and
**the name swap**.

**Rule.** The stranger pool must *explicitly say it does not know you*. Silence reads as permission;
a gap has to be authored as a negative.

**Exit.** Acceptance steps 1–3 pass. Cross residency and villagers stop saying *stranger* and start
using your name.

### Session 10 — Roads and cross-settlement propagation — **SHIP-OR-KILL**
**Build.** Delaunay over settlement centres pruned to the **relative-neighbourhood graph** — a
complete graph looks like nothing, an MST looks like a tree, the RNG between them looks like roads
someone chose. Edge weight from A* over a 16×16-chunk coarse heightmap grid, one edge per tick
off-thread. Materialise the winning path as real road blocks. Cross-settlement gossip hop at 0.15
with a 1200–6000 tick delay.

**Exit — the test that decides the project.** Acceptance steps 4–5 pass, and **a playtester who was
not told it was coming reacts audibly** when a villager in town B mentions what they did in town A.
If they don't, stop and reconsider the design before writing another line.

### Session 11 — The Notice Board
**Build.** A `lectern` with a block entity — zero new art. Computed on GUI open, never ticked.
Per-viewer standing section, deed rows with day and place, hearsay rows naming where the story came
from. **Every section prints its own absence.**

**Doubles as** the entire onboarding surface. There is no tutorial; this is it.

**Exit.** A new player who has read nothing can open the board and correctly explain what the mod
tracks.

### Session 12 — Standing bands — exactly three consumers
**Build.** Five bands driving *only*: trade price multiplier (1.35 / 1.15 / 1.00 / 0.90 / 0.75),
whether one recipe is taught, and which dialogue pool is selected. Thresholds from session 07 data.
**Resist adding a fourth consumer.**

**Exit.** Full acceptance script green, including step 7 — a second player who has done nothing gets
stranger lines and 1.00 prices everywhere, proving per-player scoping.

---

## Phase 5 · Making it alive

### Session 13 — Day plan I, the free slots
**Build.** Slot LUT, boundary offset with the **hard `spread ≥ 64` floor**, the 8-per-tick
transition governor, the `id % 7` path gate. Slots 0, 1, 4, 5, 7-sleep — the five needing no custom
activity. The industry standoff mechanic.

**Verify first.** Confirm `WorkAtPoi`'s **1.73 m** reach in-engine. `closeEnough = 9` is confirmed
at `VillagerGoalPackages.java:85`; the 1.73 half was not verifiable from the sources on disk, and the
whole mechanic rests on that gap.

**Exit.** At 09:00 every workstation has someone within arm's reach — except the ones eight blocks
away doing nothing, and *you can hear which is which*. Lazy villagers demonstrably do not restock.

### Session 14 — Day plan II, the ERRAND activity *(highest risk)*
**Build.** The custom `ERRAND` activity plus slots 2 (HAUL), 3 (NOON), 6b and 7-watch. The
`addActivitySafely` helper. The deactivation watchdog.

**Risk.** The ERRAND escalation loop is the only identified TPS-killer. This is also **the emergency
parachute**: merging slots 2+3 and deleting this activity removes the entire custom-activity risk
class, at the cost of the midday economy being invisible and the village going quiet at 18:00. The
mod still works without it.

**Exit.** The at-a-glance test passes, and a 400-record harness run shows no TPS regression.

### Session 15 — Art, config, playtest
**Build.** ~25 greyscale textures. Renderer swap for `EntityType.VILLAGER` onto the vanilla humanoid
model. Skin and hair colormaps. Culture palette tinting. The documented "gentle" config preset.

**Note.** Art is genuinely deferrable to here — a dividend of the attach architecture. Everything
through session 14 is playable and testable with vanilla villager appearance.

**Exit.** A stranger plays 45 minutes and can describe, unprompted, something a villager remembered
about them.

---

## After the slice — sequence only, not scheduled

The drama engine is **deliberately outside the slice**. A drama engine built on a propagation system
that turns out not to land is sixteen sessions wasted. Prove the thesis first.

| Sessions | Block |
|---|---|
| 16–20 | Grievance engine — wants, scarcity, 5-stage ladder, arbitration, character drift |
| 21–23 | Mortality, funerals, grief, inheritance, ruins |
| 24–27 | Era ladder 0–3, offices, charters, treasury, prosperity display |
| 28–30 | Secrets, named factions, migration, rival settlements |
| 31+ | Era 4–5, addon API hardening, animals in the social graph |

---

## Standing risks

1. ~~**Session 01 falsifies the attach bet.**~~ **Retired 2026-08-13 — the bet holds.** A persona
   survives save/quit/reload, chunk unload/reload, and villager → zombie villager → cured, on both
   loaders, keeping its id and field values across two entity-UUID changes. The 16-session estimate
   stands.
2. **Session 10 fails ship-or-kill.** If nobody reacts when town B knows their name, the central
   thesis is wrong. Better to learn it at session 10 than 60.
3. **Cultures don't feel foreign.** If settlement two sounds and behaves like settlement one, the
   travel loop collapses around hour 45 and no era ladder saves it. Session 03 is the first read;
   playtest specifically for this before building era 4–5.
4. **Traits have no consumer yet.** `Persona.traits` is written, persisted and displayed, and
   nothing branches on it — which is precisely the failure `DESIGN.md` §1 forbids. The first real
   consumer is the personality weight table in session 05. **If session 05 ships without it, the
   enforcement test is overdue and the field should be deleted rather than carried.**

## Never cut — load-bearing walls, not tuning knobs

The `spread ≥ 64` boundary-jitter floor · the 8/tick transition governor · the `id % 7` path gate ·
the player-relative particle emission gate · the `dayDelta ≤ 64` clamp · the `addActivitySafely`
helper · the sleep-skip cold-start mode.

---

## Session log

*(append one entry per session: what shipped, what the exit criterion actually showed, what changed
in this ledger, and the commit range pushed)*

### Session 00 — 2026-08-13 — repo and skeleton

**Shipped.** `67cbbc8..07a49b4`, pushed to `origin/main`.
Repo live at https://github.com/kymerailive/namesake (public, LGPL-3.0).
Hand-rolled multiloader on Gradle 9.6.1 / JDK 21: `common` on NeoForm, `fabric` on Loom 1.17.19,
`neoforge` on ModDev 2.0.141. Version matrix calibrated against MCA's proven 1.21.1 branch.
`net.namesake.platform.Platform` establishes the loader seam via `ServiceLoader`.

**What the exit criteria actually showed.**

- Clean build from scratch green; `namesake-fabric-*.jar` and `namesake-neoforge-*.jar` produced.
- 2 unit tests executed with real JUnit XML — `tests=2 failures=0 errors=0`, not an inferred pass.
- `net/namesake/Namesake.class` present in **both** jars, each compiled against its own remapped
  Minecraft. **The multiloader source-sharing approach is confirmed working.**
- Loaded in-game on both loaders:
  - `[15:01:39] (Namesake) Namesake initialising on Fabric (Minecraft 1.21.1, dev)`
  - `[15:03:22] (Namesake) Namesake initialising on NeoForge (Minecraft 1.21.1, dev)`

**Six defects, all past a green build.** The important one: `fabric.mod.json` declared
`minecraft "[1.21, 1.22)"` — a Maven range. Fabric parses semver predicates and refused to load the
mod entirely with *"requires an unsatisfiable version range"*. It built perfectly and produced a
valid-looking jar. **This is the single best argument for the exit criteria being written as
"loads in-game" rather than "compiles".** Also fixed: generic jar names, a referenced `icon.png`
that does not exist, the wrong NeoForge version range, `minecraftVersion()` wrongly placed on the
`Platform` seam when vanilla already answers it via `SharedConstants`, and `gradlew` heading for
CRLF-without-exec-bit which fails Linux CI.

**New guardrail.** `fabric/build.gradle` now fails the build if a Fabric version range is
Maven-shaped. Verified per rule 3 by feeding it `[1.21, 1.22)` and watching it fail at line 59,
then restoring and watching it pass.

**Carried into session 01.** `$env:JAVA_HOME` must be pinned to JDK 21 (system default is 26).
`runServer` needs Mojang EULA acceptance, so it was deliberately not used — `runClient` proves mod
init on both sides without it. See `HANDOFF_SESSION_01.md`.

**Ledger change.** Session 00 → done, session 01 → NEXT. No plan changes; the 16-session shape
holds.

### Session 01 — 2026-08-13 — persona, persistence, and the attach bet

**Shipped.** `9736e07..17d5009`, pushed to `origin/main`.

**The attach bet holds.** A persona rides a vanilla `Villager` through the whole entity lifecycle on
*both* loaders. Risk 1 is retired and the 16-session estimate stands.

**What shipped.** `Persona` (DESIGN §3) · `PersonaLink` loader seam (Fabric attachment API /
NeoForge data attachments, neither type in `common`) · `NpcRegistry extends SavedData` on the
overworld, UUID-keyed, `schemaVersion` in the NBT from the first save · `NpcSchema` fix ladder ·
`/namesake debug persona|registry|settrait|prune` · `AttachBetHarness`, a dev-only scripted run of
these exit criteria inside a real client.

**Two design calls worth recording.** The entity stores a bare persona **UUID**, not the record: the
registry is the authority, so a persona is never duplicated between two stores that can disagree.
And the persona→entity **binding lives in the registry, not on the persona**, because the entity
UUID changes on every zombification and cure while the identity must not.

**What the exit criteria actually showed.** Both loaders, every leg green:

| Leg | Evidence |
|---|---|
| attach | 3/3 spawned villagers carry a persona |
| chunk unload → reload | 0 resident after walking 800 blocks away; ids and trait values identical on return |
| villager → zombie villager | `Persona 9b1a4daa… survived minecraft:villager -> minecraft:zombie_villager` |
| zombie villager → cured | `… -> minecraft:villager`, trait value intact, registry count unchanged |
| save → quit → reload | 3/3 personas back with the same id and values, each still bound to a live entity |
| datafixer | `NPC registry datafixer: schema 1 -> 2 … rewrote 3 record(s)`, then 3/3 records verified holding the new sentinel |

Subject 0 kept persona `9b1a4daa…` across **two entity-UUID changes** and then a full save and
reload. That is the whole architecture in one line.

**Schema 1 was genuinely shipped before the bump.** Commit `9736e07` is schema 1; the worlds used
for the migration test were written by that build, not fabricated. Hard rule 1 followed as written.

**Four defects. Three were found by running the game rather than building it.**

1. **A migration that never reached disk.** The fixer ran, rewrote the records in memory — and
   Minecraft never wrote them back, because a `SavedData` is only saved when dirty and migrating
   does not make it dirty. The file stayed at schema 1 and re-migrated on *every* load. Found only
   by loading the same world a second time and seeing the migration line again. Now `load()` marks
   the registry dirty when a fix ran, with a unit test both ways.
2. **The two loaders fire their conversion event at opposite ends of `Mob#convertTo`.** Fabric
   before the new entity joins the level, NeoForge after — so on NeoForge the entity-join hook has
   already minted the cured villager a persona of its own. That stray is reaped, guarded on both
   "bound to this entity" and "born this tick". Proven by effect in both directions: the reap line
   appears on NeoForge and is absent on Fabric, and the registry count is unchanged either way.
3. **`SavedData.Factory` differs between loaders.** Vanilla's only constructor takes a non-null
   `DataFixTypes` and dereferences it unconditionally; NeoForge patched in a two-arg form that
   permits null. Passing null compiles on both and throws only on Fabric, only once a save exists —
   session 00's failure mode exactly. Caught by reading the decompiled sources for both before
   writing the call.
4. **The fix for defect 1 defeated the damaged-file guard.** Marking the registry dirty for a
   migration ran *before* the unreadable-record check, so a file that both needed migrating and had
   a record we could not parse would have been rewritten — dropping those records permanently, in
   the exact case the guard exists for. Caught on review, not by a test, and the test written for it
   only earns its place because moving the call back to the old position was watched to fail it.
   Position, not just presence, is the invariant.

**Rule 3 applied five times.** Each of these was reverted and watched to fail before being called
done: the read-only guard, the `Persona.equals` override (without it two personas printing identical
field values compare unequal, which would have made every "the fields survived" assertion vacuous),
the fix-ladder test (bumping `CURRENT` with no matching fix turns the build red — hard rule 1 is now
enforced by CI rather than by intention), the migration/damaged-file ordering, and the harness
itself, which produced two genuine red runs before it produced a green one.

One of those reverts is worth remembering: the first attempt at the ordering test passed with the
guard removed, because the guard was redundant with the `setDirty` override. **The test only became
real when the actual original mistake — the call's position — was restored and watched to fail.**
Reverting the fix you wrote is not the same as reverting the defect you had.

**Two of those red runs were the harness lying, not the mod.** Worth knowing before trusting it
again: one 7000-tick `/tick sprint` outruns the chunk loader, so the mob never enters the entity
tick list and simply does not tick — `entityTicks=0` while thousands of server ticks elapsed. It
sprints in 200-tick bursts now. And a zombie left alive among the spare villagers hunted them out of
the loaded area during the cure, which read as "the persona was lost". The subjects are `setNoAi`
fixtures now and the zombie is discarded once it has done its one job.

**Carried into session 02.** `$env:JAVA_HOME` still must be pinned to JDK 21. Kill the dev client
between runs — starting the next one too early hits the world's `session.lock` and the crash blames
the world, not the timing. Fabric and NeoForge disagree about which screen is up when client ticks
begin (NeoForge showed `AccessibilityOnboardingScreen` on a fresh run directory), so gate on "no
level, no overlay, some screen" rather than on `TitleScreen`.

**Ledger change.** Session 01 → done, session 02 → NEXT. Risk 1 retired; a new risk 4 added for
`Persona.traits`, which is persisted and displayed with no consumer branching on it — the exact
failure `DESIGN.md` §1 forbids, due to be paid off by the personality weight table in session 05.
No changes to the 16-session shape.
